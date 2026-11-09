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

enum class LearningText { TITLE, EMAIL, PASSWORD, SIGN_IN, CHANGE_ACCOUNT, CANCEL, RETRY, UNKNOWN, UNKNOWN_PROGRESS, EMPTY, ERROR, NETWORK_ERROR, SERVER_ERROR, INVALID_ERROR, STORAGE_ERROR, LOGIN_REQUIRED, SUBJECT_KOREAN, SUBJECT_MATH, SUBJECT_ENGLISH, REPORT, REFRESH, GOAL, SAVE_GOAL, INVALID_GOAL, COMPLETE, INCOMPLETE, MONITOR_START, MONITOR_STOP, MONITOR_REPORT, MONITOR_STATUS, INTERVAL, SAVE_INTERVAL, INVALID_INTERVAL, ACCESSIBILITY_NEEDED, NOTIFICATIONS_NEEDED, SERVICE_UNKNOWN, ACCESSIBILITY_SETTINGS, NOTIFICATION_SETTINGS, MONITOR_PROGRESS, SUBJECT_SETTINGS, CONFIRM, WATCHING, NOT_WATCHING, SETTINGS_CHANGED, PROGRESS, PROGRESS_COMPLETE, ZERO_GOAL, MINUTES, UNKNOWN_MINUTES, RELATIVE_MINUTES, RELATIVE_HOURS, MANUAL_ERROR, AUTOMATIC_ERROR, PROGRESS_COUNT }

class LearningFragment : Fragment() {
    private var binding: FragmentLearningBinding? = null
    private lateinit var screen: LearningScreen
    private var accountSubmitting = false
    private var showingLogin = false
    private var refreshing = false
    private var availability: LearningAvailability? = null
    private var availabilityJob: kotlinx.coroutines.Job? = null
    private var settingsDialog: androidx.appcompat.app.AlertDialog? = null
    private val checkedTimes = mutableMapOf<TextView, Long>()
    private var renderedDay = LocalDate.now(ZoneId.of("Asia/Seoul"))
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
        b.changeAccount.contentDescription = text(LearningText.CHANGE_ACCOUNT)
        b.changeAccount.setOnClickListener { showAccountDialog() }
        b.intervalSettings.contentDescription = text(LearningText.INTERVAL)
        b.intervalSettings.setOnClickListener { showIntervalDialog() }
        b.swipeRefresh.setOnRefreshListener { refreshFromGesture() }
        androidx.core.view.ViewCompat.addAccessibilityAction(b.swipeRefresh, text(LearningText.REFRESH)) { _, _ ->
            refreshFromGesture()
            true
        }
        b.signIn.setOnClickListener {
            val email = b.email.text.toString()
            val password = b.password.text.toString()
            b.password.text?.clear()
            runRequest { screen.signIn(email, password) }
        }
        runRequest { screen.restore() }
        viewLifecycleOwner.lifecycleScope.launch {
            screen.monitorChanges.map { it.monitor }.distinctUntilChanged().collectLatest { monitor ->
                screen.acceptMonitor(monitor)
                if (binding != null) render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        availabilityJob?.cancel()
        availabilityJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                if (today != renderedDay) {
                    renderedDay = today
                    render()
                }
                availability = host.learningAvailability()
                if (binding != null) { renderAvailability(); updateCheckedTimes() }
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

    private fun date() = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun refreshFromGesture() {
        val b = binding ?: return
        if (refreshing || !screen.signedIn || accountSubmitting) {
            if (!refreshing) b.swipeRefresh.isRefreshing = false
            return
        }
        refreshing = true
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
            screen.refresh(includeFailed = true, expectedSessionRef = scope.sessionRef)
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
        val showLogin = !signedIn && !accountSubmitting
        if (showLogin) settingsDialog?.dismiss()
        if (showingLogin && !showLogin) {
            val focused = b.root.findFocus()
            focused?.clearFocus()
            val input = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(b.root.windowToken, 0)
        }
        showingLogin = showLogin
        val loginVisibility = if (showLogin) View.VISIBLE else View.GONE
        b.dashboard.visibility = if (showLogin) View.GONE else View.VISIBLE
        b.intervalSettings.visibility = if (showLogin) View.GONE else View.VISIBLE
        b.changeAccount.visibility = if (signedIn || accountSubmitting) View.VISIBLE else View.GONE
        b.emailLayout.visibility = loginVisibility
        b.passwordLayout.visibility = loginVisibility
        b.signIn.visibility = loginVisibility
        b.status.text = when { screen.loginRequired -> text(LearningText.LOGIN_REQUIRED); state.error -> failureText(state.failure); showLogin -> ""; state.catalogCheckedAt == null -> text(LearningText.UNKNOWN); state.children.isEmpty() -> text(LearningText.EMPTY); else -> "" }
        b.swipeRefresh.isEnabled = !showLogin
        renderAvailability()
        checkedTimes.clear()
        b.children.removeAllViews()
        for (child in state.children) {
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
            val subjects = screen.visibleSubjects(child.name)
            val selectedReports = learningDisplayReports(state, screen.monitor, child.name, subjects, selected)
            val total = totalLearningMinutes(selectedReports.values)
            val watching = screen.monitor.accountId == state.accountId && child.name in screen.monitor.registeredChildren()
            val header = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(requireContext()).apply {
                text = child.name + "  " + if (total == null) this@LearningFragment.text(LearningText.UNKNOWN_MINUTES) else this@LearningFragment.text(LearningText.MINUTES, total)
                textSize = 20f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            header.addView(iconButton(R.drawable.learning_tune, text(LearningText.SUBJECT_SETTINGS), R.id.learning_subject_settings) {
                showGoalsDialog(child.name)
            })
            val watchAction = text(if (watching) LearningText.MONITOR_STOP else LearningText.MONITOR_START)
            header.addView(iconButton(if (watching) R.drawable.learning_bell_filled else R.drawable.learning_bell_outline,
                watchAction, R.id.learning_monitor_toggle) { runRequest { screen.toggleMonitor(child.name) } }.apply {
                isSelected = watching
                androidx.core.view.ViewCompat.setStateDescription(this, text(if (watching) LearningText.WATCHING else LearningText.NOT_WATCHING))
            })
            content.addView(header)
            if (screen.monitor.accountId == state.accountId && child.name in screen.monitor.registeredChildren() && screen.monitor.catalogFailureFor(child.name) != null) {
                content.addView(TextView(requireContext()).apply { text = failureText(screen.monitor.catalogFailureFor(child.name)) })
            }
            for (subject in subjects) {
                val report = state.reports[child.name to subject]
                val monitored = screen.monitor.reports[child.name to subject].takeIf { screen.monitor.accountId == state.accountId }
                val selectedReport = selectedReports[subject]
                val value = selectedReport?.value
                val goal = screen.goal(child.name, subject)
                val row = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                }
                content.addView(row)
                row.addView(TextView(requireContext()).apply { text = subjectLabel(subject); textSize = 20f; setSingleLine() })
                val todayValue = value
                if (goal == 0 || (todayValue != null && selectedReport?.failed == false && todayValue.count >= goal)) {
                    row.addView(android.widget.ImageView(requireContext()).apply {
                        val size = (24 * resources.displayMetrics.density).toInt()
                        layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                        setImageResource(R.drawable.learning_check)
                        contentDescription = this@LearningFragment.text(LearningText.COMPLETE)
                    })
                }
                if (goal != 0 && todayValue != null) {
                    row.addView(TextView(requireContext()).apply {
                        text = "  " + this@LearningFragment.text(LearningText.PROGRESS_COUNT, todayValue.count, goal)
                        setSingleLine()
                    })
                } else if (goal != 0 && todayValue == null) {
                    row.addView(TextView(requireContext()).apply { text = "  " + this@LearningFragment.text(LearningText.UNKNOWN_PROGRESS, goal); setSingleLine() })
                }
                if (value != null) {
                    row.addView(TextView(requireContext()).apply {
                        text = "  " + this@LearningFragment.text(LearningText.MINUTES, value.minutes)
                        setSingleLine()
                    })
                }
                row.addView(View(requireContext()), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
                selectedReport?.checkedAt?.let { checkedAt ->
                    val time = TextView(requireContext()).apply {
                        setSingleLine()
                        layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply {
                            marginStart = (8 * resources.displayMetrics.density).toInt()
                        }
                    }
                    checkedTimes[time] = checkedAt
                    row.addView(time)
                }
                if (report?.failed == true) content.addView(TextView(requireContext()).apply { text = this@LearningFragment.text(LearningText.MANUAL_ERROR, failureText(report.failure)) })
                if (monitored?.failed == true) content.addView(TextView(requireContext()).apply { text = this@LearningFragment.text(LearningText.AUTOMATIC_ERROR, failureText(monitored.failure)) })

            }
        }
        updateCheckedTimes()
    }

    private fun updateCheckedTimes() {
        val now = System.currentTimeMillis()
        checkedTimes.forEach { (view, checkedAt) ->
            val minutes = elapsedMinutes(now, checkedAt)
            view.text = if (minutes < 60) text(LearningText.RELATIVE_MINUTES, minutes)
                else text(LearningText.RELATIVE_HOURS, minutes / 60, minutes % 60)
        }
    }

    private fun iconButton(icon: Int, description: String, viewId: Int, action: () -> Unit): android.widget.ImageButton =
        android.widget.ImageButton(requireContext()).apply {
            id = viewId
            val size = (48 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
            val background = android.util.TypedValue()
            requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setImageResource(icon)
            contentDescription = description
            setOnClickListener { action() }
        }

    private fun subjectLabel(subject: Subject): String = text(when (subject) {
        Subject.KOREAN -> LearningText.SUBJECT_KOREAN
        Subject.MATH -> LearningText.SUBJECT_MATH
        Subject.ENGLISH -> LearningText.SUBJECT_ENGLISH
    })

    private fun dialogContent(): android.widget.LinearLayout = android.widget.LinearLayout(requireContext()).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
    }

    private fun numberInput(value: String): android.widget.EditText = android.widget.EditText(requireContext()).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setText(value)
        layoutParams = android.widget.LinearLayout.LayoutParams((120 * resources.displayMetrics.density).toInt(), -2)
    }

    private fun showAccountDialog() {
        val content = dialogContent()
        fun field(hint: String, inputType: Int, initial: String): com.google.android.material.textfield.TextInputEditText {
            val layout = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
                this.hint = hint
                endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
            }
            val input = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
                setSingleLine()
                this.inputType = inputType
                setText(initial)
            }
            layout.addView(input)
            content.addView(layout)
            return input
        }
        val email = field(text(LearningText.EMAIL), android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, screen.accountEmail.orEmpty())
        val password = field(text(LearningText.PASSWORD), android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, "")
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(text(LearningText.CHANGE_ACCOUNT)).setView(content)
            .setNegativeButton(text(LearningText.CANCEL), null)
            .setPositiveButton(text(LearningText.SIGN_IN), null).create()
        settingsDialog = dialog
        dialog.setOnDismissListener {
            password.text?.clear()
            if (settingsDialog === dialog) settingsDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = email.text.toString()
                val secret = password.text.toString()
                password.text?.clear()
                accountSubmitting = true
                dialog.setCancelable(false)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                val owner = viewLifecycleOwner
                owner.lifecycleScope.launch {
                    try { withContext(Dispatchers.IO) { screen.signIn(address, secret) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { screen.storageFailure() }
                    finally {
                        if (binding != null && viewLifecycleOwner === owner) {
                            accountSubmitting = false
                            if (dialog.isShowing) dialog.dismiss()
                            render()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showIntervalDialog() {
        val content = dialogContent()
        val input = numberInput(screen.monitor.intervalMinutes)
        content.addView(input)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(text(LearningText.INTERVAL)).setView(content)
            .setNegativeButton(text(LearningText.CANCEL), null)
            .setPositiveButton(text(LearningText.CONFIRM), null).create()
        settingsDialog = dialog
        dialog.setOnDismissListener { if (settingsDialog === dialog) settingsDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val saved = try { withContext(Dispatchers.IO) { screen.saveInterval(value) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { screen.storageFailure(); false }
                    if (dialog.isShowing) {
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (saved) dialog.dismiss() else input.error = text(LearningText.INVALID_INTERVAL)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showGoalsDialog(childName: String) {
        val scope = screen.goalScope(childName) ?: return
        val content = dialogContent()
        val inputs = Subject.entries.associateWith { subject ->
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(requireContext()).apply {
                text = subjectLabel(subject)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            })
            numberInput(screen.goal(childName, subject).toString()).also {
                it.layoutParams = android.widget.LinearLayout.LayoutParams((96 * resources.displayMetrics.density).toInt(), -2)
                it.contentDescription = subjectLabel(subject)
                row.addView(it)
                content.addView(row)
            }
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(text(LearningText.SUBJECT_SETTINGS)).setView(content)
            .setNegativeButton(text(LearningText.CANCEL), null)
            .setPositiveButton(text(LearningText.CONFIRM), null).create()
        settingsDialog = dialog
        dialog.setOnDismissListener { if (settingsDialog === dialog) settingsDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var valid = true
                inputs.values.forEach { input ->
                    val value = input.text.toString().trim()
                    if (!value.matches(Regex("[0-9]+")) || value.toIntOrNull()?.let { it in 0..99 } != true) {
                        input.error = text(LearningText.INVALID_GOAL)
                        valid = false
                    }
                }
                if (valid) {
                    val values = inputs.mapValues { it.value.text.toString() }
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        val saved = try { withContext(Dispatchers.IO) { screen.saveGoals(scope, values) } }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { screen.storageFailure(); false }
                        if (dialog.isShowing) {
                            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            if (saved) { dialog.dismiss(); render() }
                            else android.widget.Toast.makeText(requireContext(), text(LearningText.SETTINGS_CHANGED), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
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
        settingsDialog?.dismiss()
        settingsDialog = null
        checkedTimes.clear()
        binding = null
        showingLogin = false
        accountSubmitting = false
        super.onDestroyView()
    }
}



internal fun elapsedMinutes(now: Long, checkedAt: Long): Long {
    if (checkedAt >= now) return 0
    val elapsed = try { Math.subtractExact(now, checkedAt) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    return elapsed / 60_000
}
