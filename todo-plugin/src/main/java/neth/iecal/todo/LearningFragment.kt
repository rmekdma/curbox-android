package neth.iecal.todo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.todo.databinding.FragmentLearningBinding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The host supplies its process-safe dependencies and localized text. */
interface LearningHost {
    fun createLearningScreen(): LearningScreen
    fun learningText(key: LearningText, vararg args: Any): String
}

enum class LearningText { TITLE, EMAIL, PASSWORD, SIGN_IN, RETRY, UNKNOWN, EMPTY, ERROR, NETWORK_ERROR, SERVER_ERROR, INVALID_ERROR, STORAGE_ERROR, LOGIN_REQUIRED, SUBJECT_KOREAN, SUBJECT_MATH, SUBJECT_ENGLISH, REPORT, LAST_REPORT, PREVIOUS_DATE, NEXT_DATE, REFRESH, GOAL, SAVE_GOAL, INVALID_GOAL, COMPLETE, INCOMPLETE }

class LearningFragment : Fragment() {
    private var binding: FragmentLearningBinding? = null
    private lateinit var screen: LearningScreen
    private var selectedDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
    private val host get() = requireActivity() as LearningHost
    private fun text(key: LearningText, vararg args: Any) = host.learningText(key, *args)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = host.createLearningScreen()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val view = FragmentLearningBinding.inflate(inflater, container, false)
        binding = view
        return view.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val b = binding ?: return
        b.title.text = text(LearningText.TITLE)
        b.email.hint = text(LearningText.EMAIL)
        b.password.hint = text(LearningText.PASSWORD)
        b.signIn.text = text(LearningText.SIGN_IN)
        b.retry.text = text(LearningText.RETRY)
        b.previousDate.text = text(LearningText.PREVIOUS_DATE)
        b.nextDate.text = text(LearningText.NEXT_DATE)
        b.refresh.text = text(LearningText.REFRESH)
        screen.selectDate(date())
        b.previousDate.setOnClickListener { changeDate(selectedDate.minusDays(1)) }
        b.nextDate.setOnClickListener { changeDate(selectedDate.plusDays(1)) }
        b.refresh.setOnClickListener { refreshSelected() }
        b.signIn.setOnClickListener {
            val email = b.email.text.toString()
            val password = b.password.text.toString()
            b.password.text.clear()
            val requested = date()
            runRequest { screen.signIn(email, password, requested, onlyIfSelected = true) }
        }
        b.retry.setOnClickListener { runRequest { screen.refresh(date(), onlyIfSelected = true) } }
        runRequest { screen.restore() }
    }

    private fun date() = selectedDate.format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun changeDate(next: LocalDate) {
        if (next.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))) return
        selectedDate = next
        screen.selectDate(date())
        render()
        refreshSelected()
    }

    private fun refreshSelected() {
        val requested = date()
        runRequest { screen.refresh(requested, onlyIfSelected = true) }
    }

    private fun runRequest(block: () -> Unit) {
        binding?.loading?.visibility = View.VISIBLE
        binding?.signIn?.isEnabled = false
        binding?.retry?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { screen.storageFailure() }
            finally {
                binding?.loading?.visibility = View.GONE
                binding?.signIn?.isEnabled = true
                binding?.retry?.isEnabled = true
                if (binding != null) render()
            }
        }
    }

    private fun failureText(failure: LearningFailure?): String = text(when (failure) {
        LearningFailure.NETWORK -> LearningText.NETWORK_ERROR
        LearningFailure.SERVER -> LearningText.SERVER_ERROR
        LearningFailure.INVALID_DATA -> LearningText.INVALID_ERROR
        LearningFailure.STORAGE -> LearningText.STORAGE_ERROR
        LearningFailure.AUTHENTICATION -> LearningText.LOGIN_REQUIRED
        null -> LearningText.ERROR
    })

    private fun render() {
        val b = binding ?: return
        val state = screen.state
        val selected = date()
        b.selectedDate.text = selectedDate.toString()
        b.nextDate.isEnabled = selectedDate.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")))
        b.status.text = when { state.loginRequired -> text(LearningText.LOGIN_REQUIRED); state.error -> failureText(state.failure); state.catalogCheckedAt == null -> text(LearningText.UNKNOWN); state.children.isEmpty() -> text(LearningText.EMPTY); else -> "" }
        b.retry.visibility = if (state.error && !state.loginRequired) View.VISIBLE else View.GONE
        b.children.removeAllViews()
        for (child in state.children) {
            b.children.addView(TextView(requireContext()).apply { text = child.name; textSize = 20f })
            for (subject in child.userIds.keys) {
                val label = text(when (subject) { Subject.KOREAN -> LearningText.SUBJECT_KOREAN; Subject.MATH -> LearningText.SUBJECT_MATH; Subject.ENGLISH -> LearningText.SUBJECT_ENGLISH })
                val report = state.reports[child.name to subject]
                val value = report?.value?.takeIf { report.date == selected }
                val description = if (value == null) { if (report?.failed == true) failureText(report.failure) else text(LearningText.UNKNOWN) }
                    else text(if (report.failed) LearningText.LAST_REPORT else LearningText.REPORT, value.count, value.minutes, report.date ?: "", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.checkedAt ?: 0)))
                val status = when (screen.goalStatus(child.name, subject, selected)) {
                    GoalStatus.COMPLETE -> text(LearningText.COMPLETE)
                    GoalStatus.INCOMPLETE -> text(LearningText.INCOMPLETE)
                    GoalStatus.UNKNOWN -> text(LearningText.UNKNOWN)
                }
                b.children.addView(TextView(requireContext()).apply { text = "$label: $description • $status" })
                val goalInput = android.widget.EditText(requireContext()).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = this@LearningFragment.text(LearningText.GOAL)
                    setText(screen.goal(child.name, subject).toString())
                }
                b.children.addView(goalInput)
                b.children.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                    text = this@LearningFragment.text(LearningText.SAVE_GOAL)
                    setOnClickListener {
                        val input = goalInput.text.toString()
                        viewLifecycleOwner.lifecycleScope.launch {
                            val saved = try { withContext(Dispatchers.IO) { screen.saveGoal(child.name, subject, input) } }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { screen.storageFailure(); false }
                            if (binding != null) {
                                if (saved) render() else goalInput.error = this@LearningFragment.text(LearningText.INVALID_GOAL)
                            }
                        }
                    }
                })
                if (value != null && report.failed) b.children.addView(TextView(requireContext()).apply { text = failureText(report.failure) })
                b.children.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                    text = this@LearningFragment.text(LearningText.RETRY)
                    isEnabled = !state.loginRequired && screen.goal(child.name, subject) > 0
                    setOnClickListener { runRequest { screen.retryReport(child.name, subject, selected) } }
                })
            }
        }
    }

    override fun onDestroyView() { binding = null; super.onDestroyView() }
}


