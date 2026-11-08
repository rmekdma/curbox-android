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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import neth.iecal.todo.databinding.FragmentLearningBinding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The host supplies its process-safe dependencies and localized text. */
data class LearningAvailability(val accessibilityEnabled: Boolean, val notificationsEnabled: Boolean, val serviceConnected: Boolean?)

interface LearningHost {
    suspend fun learningAvailability(): LearningAvailability
    fun createLearningScreen(): LearningScreen
    fun learningText(key: LearningText, vararg args: Any): String
}

enum class LearningText { TITLE, EMAIL, PASSWORD, SIGN_IN, CHANGE_ACCOUNT, CANCEL, RETRY, UNKNOWN, EMPTY, ERROR, NETWORK_ERROR, SERVER_ERROR, INVALID_ERROR, STORAGE_ERROR, LOGIN_REQUIRED, SUBJECT_KOREAN, SUBJECT_MATH, SUBJECT_ENGLISH, REPORT, LAST_REPORT, PREVIOUS_DATE, NEXT_DATE, REFRESH, GOAL, SAVE_GOAL, INVALID_GOAL, COMPLETE, INCOMPLETE, MONITOR_START, MONITOR_STOP, MONITOR_REPORT, MONITOR_STATUS, INTERVAL, SAVE_INTERVAL, INVALID_INTERVAL, ACCESSIBILITY_NEEDED, NOTIFICATIONS_NEEDED, SERVICE_UNKNOWN, ACCESSIBILITY_SETTINGS, NOTIFICATION_SETTINGS, PULL_HELP, SELECTED_REPORT, MONITOR_PROGRESS }

class LearningFragment : Fragment() {
    private var binding: FragmentLearningBinding? = null
    private lateinit var screen: LearningScreen
    private var changingAccount = false
    private var showingLogin = false
    private var refreshing = false
    private var availability: LearningAvailability? = null
    private var availabilityJob: kotlinx.coroutines.Job? = null
    private val goalInputs = mutableMapOf<Triple<String?, String, Subject>, android.widget.EditText>()
    private val goalDrafts = mutableMapOf<Triple<String?, String, Subject>, String>()
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
        b.accessibilitySettings.text = text(LearningText.ACCESSIBILITY_SETTINGS)
        b.notificationSettings.text = text(LearningText.NOTIFICATION_SETTINGS)
        b.accessibilitySettings.setOnClickListener { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.notificationSettings.setOnClickListener {
            val intent = if (android.os.Build.VERSION.SDK_INT >= 26) android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + requireContext().packageName))
            startActivity(intent)
        }
        b.title.text = text(LearningText.TITLE)
        b.emailLayout.hint = text(LearningText.EMAIL)
        b.passwordLayout.hint = text(LearningText.PASSWORD)
        b.signIn.text = text(LearningText.SIGN_IN)
        b.changeAccount.text = text(LearningText.CHANGE_ACCOUNT)
        b.cancelAccountChange.text = text(LearningText.CANCEL)
        b.changeAccount.setOnClickListener { changingAccount = true; render() }
        b.cancelAccountChange.setOnClickListener { changingAccount = false; b.password.text?.clear(); render() }
        b.previousDate.text = text(LearningText.PREVIOUS_DATE)
        b.nextDate.text = text(LearningText.NEXT_DATE)
        b.intervalLabel.text = text(LearningText.INTERVAL)
        b.pullHelp.text = text(LearningText.PULL_HELP)
        b.saveInterval.text = text(LearningText.SAVE_INTERVAL)
        b.saveInterval.setOnClickListener {
            val input = b.intervalMinutes.text.toString()
            viewLifecycleOwner.lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { screen.saveInterval(input) }
                if (!saved) b.intervalMinutes.error = text(LearningText.INVALID_INTERVAL)
            }
        }
        screen.selectDate(date())
        b.previousDate.setOnClickListener { changeDate(selectedDate.minusDays(1)) }
        b.nextDate.setOnClickListener { changeDate(selectedDate.plusDays(1)) }
        b.swipeRefresh.setOnRefreshListener { refreshFromGesture() }
        androidx.core.view.ViewCompat.addAccessibilityAction(b.swipeRefresh, text(LearningText.REFRESH)) { _, _ ->
            refreshFromGesture()
            true
        }
        b.signIn.setOnClickListener {
            val email = b.email.text.toString()
            val password = b.password.text.toString()
            b.password.text?.clear()
            val requested = date()
            b.cancelAccountChange.isEnabled = false
            runRequest(onComplete = {
                binding?.cancelAccountChange?.isEnabled = true
                if (screen.signedIn) changingAccount = false
            }) {
                screen.signIn(email, password, requested, onlyIfSelected = true)
            }
        }
        runRequest { screen.restore() }
        viewLifecycleOwner.lifecycleScope.launch {
            screen.monitorChanges.map { it.monitor }.distinctUntilChanged().collectLatest { monitor ->
                screen.acceptMonitor(monitor)
                if (b.intervalMinutes.text.isEmpty()) b.intervalMinutes.setText(monitor.intervalMinutes)
                if (binding != null) render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        availabilityJob?.cancel()
        availabilityJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                availability = host.learningAvailability()
                if (binding != null) renderAvailability()
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    override fun onPause() {
        availabilityJob?.cancel()
        availabilityJob = null
        availability = null
        super.onPause()
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

    private fun refreshFromGesture() {
        val b = binding ?: return
        if (refreshing || !screen.signedIn || changingAccount) {
            if (!refreshing) b.swipeRefresh.isRefreshing = false
            return
        }
        refreshing = true
        val requested = date()
        val scope = screen.retryScope() ?: run {
            refreshing = false
            b.swipeRefresh.isRefreshing = false
            return
        }
        runRequest(onComplete = {
            refreshing = false
            binding?.swipeRefresh?.isRefreshing = false
        }) {
            screen.retryFailures(scope)
            screen.refresh(requested, onlyIfSelected = true, includeFailed = true, expectedSessionRef = scope.sessionRef)
        }
    }

    private fun runRequest(onComplete: () -> Unit = {}, block: () -> Unit) {
        binding?.loading?.visibility = View.VISIBLE
        binding?.signIn?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { screen.storageFailure() }
            finally {
                binding?.loading?.visibility = View.GONE
                binding?.signIn?.isEnabled = true
                onComplete()
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
        val signedIn = screen.signedIn
        val showLogin = !signedIn || changingAccount
        if (showingLogin && !showLogin) {
            val focused = b.root.findFocus()
            focused?.clearFocus()
            val input = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(b.root.windowToken, 0)
        }
        showingLogin = showLogin
        val loginVisibility = if (showLogin) View.VISIBLE else View.GONE
        b.dashboard.visibility = if (showLogin) View.GONE else View.VISIBLE
        b.changeAccount.visibility = if (signedIn && !changingAccount) View.VISIBLE else View.GONE
        b.cancelAccountChange.visibility = if (signedIn && changingAccount) View.VISIBLE else View.GONE
        b.emailLayout.visibility = loginVisibility
        b.passwordLayout.visibility = loginVisibility
        b.signIn.visibility = loginVisibility
        b.selectedDate.text = selectedDate.toString()
        b.nextDate.isEnabled = selectedDate.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")))
        b.status.text = when { screen.loginRequired -> text(LearningText.LOGIN_REQUIRED); state.error -> failureText(state.failure); showLogin -> ""; state.catalogCheckedAt == null -> text(LearningText.UNKNOWN); state.children.isEmpty() -> text(LearningText.EMPTY); else -> "" }
        b.swipeRefresh.isEnabled = !showLogin
        renderAvailability()
        var focused: Triple<String?, String, Subject>? = null
        var selection = 0
        for ((key, input) in goalInputs) {
            goalDrafts[key] = input.text.toString()
            if (input.hasFocus()) { focused = key; selection = input.selectionStart.coerceAtLeast(0) }
        }
        goalInputs.clear()
        b.children.removeAllViews()
        for (child in state.children) {
            b.children.addView(TextView(requireContext()).apply { text = child.name; textSize = 20f })
            b.children.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
                icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_lock_silent_mode_off)
                text = this@LearningFragment.text(if (screen.monitor.accountId == state.accountId && child.name in screen.monitor.registeredChildren()) LearningText.MONITOR_STOP else LearningText.MONITOR_START)
                setOnClickListener { runRequest { screen.toggleMonitor(child.name) } }
            })
            if (screen.monitor.accountId == state.accountId && child.name in screen.monitor.registeredChildren() && screen.monitor.catalogFailureFor(child.name) != null) {
                b.children.addView(TextView(requireContext()).apply { text = failureText(screen.monitor.catalogFailureFor(child.name)) })
            }
            for (subject in screen.visibleSubjects(child.name)) {
                val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                    val margin = (8 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
                }
                val content = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
                card.addView(content)
                b.children.addView(card)
                val label = text(when (subject) { Subject.KOREAN -> LearningText.SUBJECT_KOREAN; Subject.MATH -> LearningText.SUBJECT_MATH; Subject.ENGLISH -> LearningText.SUBJECT_ENGLISH })
                content.addView(TextView(requireContext()).apply { text = label; textSize = 20f })
                val report = state.reports[child.name to subject]
                val value = report?.value?.takeIf { report.date == selected }
                val description = if (value == null) { if (report?.failed == true) failureText(report.failure) else text(LearningText.UNKNOWN) }
                    else text(if (report.failed) LearningText.LAST_REPORT else LearningText.REPORT, value.count, value.minutes, report.date ?: "", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.checkedAt ?: 0)))
                val status = when (screen.goalStatus(child.name, subject, selected)) {
                    GoalStatus.COMPLETE -> text(LearningText.COMPLETE)
                    GoalStatus.INCOMPLETE -> text(LearningText.INCOMPLETE)
                    GoalStatus.UNKNOWN -> text(LearningText.UNKNOWN)
                }
                content.addView(TextView(requireContext()).apply { text = this@LearningFragment.text(LearningText.SELECTED_REPORT, description, status) })
                val monitored = screen.monitor.reports[child.name to subject]
                if (screen.monitor.accountId == state.accountId && child.name in screen.monitor.registeredChildren()) {
                    val today = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)
                    val monitorStatus = when (screen.monitorGoalStatus(child.name, subject, today)) {
                        GoalStatus.COMPLETE -> text(LearningText.COMPLETE)
                        GoalStatus.INCOMPLETE -> text(LearningText.INCOMPLETE)
                        GoalStatus.UNKNOWN -> text(LearningText.UNKNOWN)
                    }
                    val todayValue = monitored?.value?.takeIf { monitored.date == today }
                    val watching = if (todayValue == null) text(LearningText.MONITOR_STATUS, monitorStatus)
                        else text(LearningText.MONITOR_PROGRESS, todayValue.count, screen.goal(child.name, subject), monitorStatus)
                    content.addView(TextView(requireContext()).apply { text = watching; textSize = 12f })
                    if (monitored?.failed == true) {
                        if (monitored.value != null) {
                            val checked = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(monitored.checkedAt ?: 0))
                            content.addView(TextView(requireContext()).apply {
                                text = this@LearningFragment.text(LearningText.MONITOR_REPORT, monitored.value.count, monitored.value.minutes, checked)
                                textSize = 12f
                            })
                        }
                        content.addView(TextView(requireContext()).apply { text = failureText(monitored.failure) })
                    }
                }
                val goalInput = android.widget.EditText(requireContext()).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    hint = this@LearningFragment.text(LearningText.GOAL)
                    val key = Triple(state.accountId, child.name, subject)
                    tag = key
                    setText(goalDrafts[key] ?: screen.goal(child.name, subject).toString())
                }
                goalInputs[Triple(state.accountId, child.name, subject)] = goalInput
                val goalHeader = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                goalHeader.addView(TextView(requireContext()).apply {
                    text = this@LearningFragment.text(LearningText.GOAL)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                })
                content.addView(goalHeader)
                goalHeader.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
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
                content.addView(goalInput, android.widget.LinearLayout.LayoutParams((96 * resources.displayMetrics.density).toInt(), -2))
                if (focused == goalInput.tag) { goalInput.requestFocus(); goalInput.setSelection(selection.coerceAtMost(goalInput.text.length)) }
                if (value != null && report.failed) content.addView(TextView(requireContext()).apply { text = failureText(report.failure) })
            }
        }
    }

    private fun renderAvailability() {
        val b = binding ?: return
        val status = availability
        b.availability.text = when {
            status == null -> text(LearningText.SERVICE_UNKNOWN)
            !status.accessibilityEnabled -> text(LearningText.ACCESSIBILITY_NEEDED)
            !status.notificationsEnabled -> text(LearningText.NOTIFICATIONS_NEEDED)
            status.serviceConnected != true -> text(LearningText.SERVICE_UNKNOWN)
            else -> ""
        }
        b.accessibilitySettings.visibility = if (status?.accessibilityEnabled == false) View.VISIBLE else View.GONE
        b.notificationSettings.visibility = if (status?.notificationsEnabled == false) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        for ((key, input) in goalInputs) goalDrafts[key] = input.text.toString()
        goalInputs.clear()
        binding = null
        showingLogin = false
        super.onDestroyView()
    }
}


