package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.domain.apprules.GuardianApprovalSelection
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.DataStoreManager
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Internal approval surface. It has no exported intent or broadcast write path. */
class GuardianApprovalActivity : AppCompatActivity() {
    private val dataStore by lazy { DataStoreManager(applicationContext) }
    private var denials: List<AppRuleGuardianDenial> = emptyList()
    private var selectedRuleId: String? = null
    private var hasPassword = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        denials = runCatching {
            Gson().fromJson<List<AppRuleGuardianDenial>>(
                intent.getStringExtra(EXTRA_DENIALS).orEmpty(),
                object : TypeToken<List<AppRuleGuardianDenial>>() {}.type
            )
        }.getOrNull().orEmpty()
        if (denials.isEmpty()) {
            finish()
            return
        }
        selectedRuleId = denials.first().ruleId
        render()
        lifecycleScope.launch {
            hasPassword = dataStore.settings.first().guardianAuthConfig.isConfigured
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.guardian_approval_title)
            textSize = 23f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.guardian_approval_message)
            setPadding(0, 12, 0, 12)
        })
        val choices = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        denials.forEachIndexed { index, denial ->
            choices.addView(RadioButton(this).apply {
                id = index + 1
                text = "${denial.ruleName}\n${denial.reason}"
                isChecked = index == 0
                setPadding(0, 8, 0, 8)
            })
        }
        choices.setOnCheckedChangeListener { _, checkedId ->
            selectedRuleId = GuardianApprovalSelection
                .selectedDenial(denials, checkedId - 1)
                ?.ruleId
        }
        root.addView(choices)
        val add = MaterialButton(this).apply {
            text = getString(R.string.guardian_add_time)
            setOnClickListener { requestGrant() }
        }
        val skip = MaterialButton(this).apply {
            text = getString(R.string.guardian_skip_rule)
            setOnClickListener { requestSkip() }
        }
        root.addView(add, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(skip, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun requestGrant() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.guardian_minutes_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_add_time)
            .setView(input)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                val minutes = input.text.toString().toLongOrNull() ?: 0L
                if (minutes <= 0L) {
                    toast(R.string.guardian_invalid_minutes)
                } else {
                    authenticateThen { password -> writeGrant(password, minutes) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestSkip() {
        val labels = arrayOf(
            getString(R.string.guardian_skip_15_minutes),
            getString(R.string.guardian_skip_30_minutes),
            getString(R.string.guardian_skip_until_reset)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_skip_rule)
            .setSingleChoiceItems(labels, 0) { dialog, which ->
                dialog.dismiss()
                authenticateThen { password -> writeSkip(password, which) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun authenticateThen(onAuthenticated: (String) -> Unit) {
        if (!hasPassword) {
            onAuthenticated("")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.guardian_password_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guardian_enter_password)
            .setView(input)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                lifecycleScope.launch {
                    val password = input.text.toString()
                    if (dataStore.guardianPasswordIsValid(password)) {
                        onAuthenticated(password)
                    } else {
                        toast(R.string.guardian_wrong_password)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun writeGrant(password: String, minutes: Long) {
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = dataStore.settings.first()
            val now = System.currentTimeMillis()
            val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
            val useDayId = calculator.idAt(now)
            val success = dataStore.grantAppRuleTime(password, ruleId, useDayId, minutes, now)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (success) finishAndLaunch() else toast(R.string.guardian_write_failed)
            }
        }
    }

    private fun writeSkip(password: String, option: Int) {
        val ruleId = selectedRuleId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = dataStore.settings.first()
            val now = System.currentTimeMillis()
            val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
            val useDayId = calculator.idAt(now)
            val nextReset = calculator.windowFor(useDayId).last + 1L
            val selected = when (option) {
                0 -> now + Duration.ofMinutes(15).toMillis()
                1 -> now + Duration.ofMinutes(30).toMillis()
                else -> nextReset
            }
            val success = dataStore.skipAppRuleUntil(
                password, ruleId, useDayId, selected, nextReset, now
            )
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (success) finishAndLaunch() else toast(R.string.guardian_write_failed)
            }
        }
    }

    private fun finishAndLaunch() {
        intent.getStringExtra(EXTRA_PACKAGE)?.let { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        }
        finish()
    }

    private fun toast(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_DENIALS = "app_rule_denials_json"
        const val EXTRA_PACKAGE = "launch_package"
    }
}
