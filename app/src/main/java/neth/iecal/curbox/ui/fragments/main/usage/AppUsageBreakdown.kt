package neth.iecal.curbox.ui.fragments.main.usage

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentAppUsageBreakdownBinding
import neth.iecal.curbox.domain.apprules.UsageResetUiPolicy
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appRules.CreateAppRuleGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.CreateGrayscaleGroupFragment
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.GuardianOwnedDialog
import neth.iecal.curbox.utils.UsageResetManager
import neth.iecal.curbox.utils.UsageResetStatus
import java.time.LocalDate

class AppUsageBreakdown(
    private val stat: AllAppsUsageFragment.Stat,
    private val canResetUsage: Boolean = false
) : Fragment() {

    private lateinit var binding: FragmentAppUsageBreakdownBinding
    private val viewModel: SetupShortcutViewModel by viewModels()
    private val resetEligibleEpochDay: Long? =
        if (canResetUsage) LocalDate.now().toEpochDay() else null
    private var resetRequested = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentAppUsageBreakdownBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLineChart(binding.lineChart)
        plotUsageData()

        try {
            val appInfo = requireContext().packageManager.getApplicationInfo(stat.packageName, 0)
            binding.appName.text = appInfo.loadLabel(requireContext().packageManager)
            binding.appIcon.setImageDrawable(appInfo.loadIcon(requireContext().packageManager))
        } catch (_: Exception) {}
        
        binding.screentime.text = TimeTools.formatTime(stat.totalTime, false)
        binding.sessions.text = stat.sessions.toString()
        updateResetButtonVisibility()
        if (isResetCurrentlyEligible()) {
            binding.resetUsageButton.setOnClickListener { confirmReset() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settings.collectLatest { settings ->
                if (settings == null) return@collectLatest
                binding.dynamicShortcutsContainer.removeAllViews()

                val matchedAppGroups = settings.appRuleSnapshot.appGroups
                    .filter { it.selectedPackages.contains(stat.packageName) }
                matchedAppGroups.forEach { group ->
                    val groupIsActive = settings.appRuleSnapshot.appRules.any { rule ->
                        val scope = rule.effectiveScope()
                        rule.isActive && (
                            rule.appGroupId == group.id ||
                                group.id in scope.includedGroupIds
                            )
                    }
                    addShortcutCard(
                        title = group.name,
                        subtitle = getString(R.string.app_rules_title),
                        isActive = groupIsActive,
                        iconRes = R.drawable.ic_app_blocker_aesthetic,
                        onToggle = { active -> viewModel.toggleAppGroup(group.id, active) },
                        onClick = {
                            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                                putExtra("fragment", CreateAppRuleGroupFragment.FRAGMENT_ID)
                                putExtra("app_rule_group_id", group.id)
                            })
                        }
                    )
                }

                val matchedGrayscale = settings.grayscaleGroups.filter { it.packages.contains(stat.packageName) }
                matchedGrayscale.forEach { group ->
                    addShortcutCard(
                        title = group.groupName,
                        subtitle = getString(R.string.rule_type_grayscale),
                        isActive = group.isActive,
                        iconRes = R.drawable.ic_grayscale_aesthetic,
                        onToggle = { active -> viewModel.toggleGrayscaleGroup(group.groupId, active) },
                        onClick = {
                            startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                                putExtra("fragment", CreateGrayscaleGroupFragment.FRAGMENT_ID)
                                putExtra("group_id", group.groupId)
                            })
                        }
                    )
                }
            }
        }

        binding.btnCreateNewRule.setOnClickListener {
            val options = arrayOf(getString(R.string.app_rules_title), getString(R.string.rule_type_grayscale))
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.create_new_rule_title)
                .setItems(options) { _, which ->
                    val fragmentId = when (which) {
                        0 -> CreateAppRuleGroupFragment.FRAGMENT_ID
                        1 -> CreateGrayscaleGroupFragment.FRAGMENT_ID
                        else -> return@setItems
                    }
                    startActivity(Intent(requireContext(), FragmentActivity::class.java).apply {
                        putExtra("fragment", fragmentId)
                        putExtra("prefill_package", stat.packageName)
                    })
                }
                .show()
        }
    }

    private fun confirmReset() {
        if (!ensureResetEligible()) return
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.usage_reset_app)
            .setMessage(R.string.usage_reset_app_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.usage_reset_confirm) { _, _ -> resetUsage() }
            .create()
        GuardianOwnedDialog.show(dialog)
    }

    private fun resetUsage() {
        if (!ensureResetEligible()) return
        resetAppUsage()
    }

    private fun isResetCurrentlyEligible(): Boolean =
        UsageResetUiPolicy.isCurrentResetEligible(
            resetEligibleEpochDay = resetEligibleEpochDay,
            currentEpochDay = LocalDate.now().toEpochDay()
        )

    private fun updateResetButtonVisibility() {
        binding.resetUsageButton.visibility =
            if (isResetCurrentlyEligible()) View.VISIBLE else View.GONE
    }

    private fun ensureResetEligible(): Boolean {
        if (isResetCurrentlyEligible()) return true
        updateResetButtonVisibility()
        if (isAdded) {
            Toast.makeText(requireContext(), R.string.usage_reset_only_today, Toast.LENGTH_LONG).show()
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            usageResetReceiver,
            IntentFilter(UsageResetManager.ACTION_USAGE_RESET),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateResetButtonVisibility()
        if (resetRequested) reloadAfterReset()
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(usageResetReceiver) }
        super.onStop()
    }

    private val usageResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != UsageResetManager.ACTION_USAGE_RESET) return
            if (!isAdded) return
            val packages = intent.getStringArrayListExtra(UsageResetManager.EXTRA_PACKAGES).orEmpty()
            if (!UsageResetUiPolicy.completionMatchesPackage(
                    packageName = stat.packageName,
                    completedPackages = packages,
                    resetEligible = isResetCurrentlyEligible(),
                    resetRequested = resetRequested
                )
            ) return
            if (intent.getBooleanExtra(UsageResetManager.EXTRA_RESULT_OK, false)) {
                Toast.makeText(requireContext(), R.string.usage_reset_done, Toast.LENGTH_SHORT).show()
                reloadAfterReset()
            } else {
                resetRequested = false
                Toast.makeText(requireContext(), R.string.usage_reset_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The list screen owns the fresh DB load; leave this immutable detail view on completion. */
    private fun reloadAfterReset() {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        resetRequested = false
        runCatching { parentFragmentManager.popBackStackImmediate() }
    }

    private fun resetAppUsage() {
        if (!ensureResetEligible()) return
        // Set this before entering the manager so a timeout or stopped completion can converge
        // through onStart even when the UI receiver was not registered for the broadcast.
        resetRequested = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val outcome = UsageResetManager(requireContext()).resetApp(stat.packageName)
            launch(Dispatchers.Main) mainLaunch@{
                if (!isAdded) return@mainLaunch
                when (outcome.status) {
                    // The screen-wide completion receiver owns service results. Keeping this
                    // branch silent prevents the manager receiver and UI receiver from acting
                    // on the same broadcast twice.
                    UsageResetStatus.SUCCESS -> Unit
                    UsageResetStatus.FAILED -> {
                        if (outcome.request == null) {
                            resetRequested = false
                            Toast.makeText(requireContext(), R.string.usage_reset_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                    UsageResetStatus.PENDING -> {
                        Toast.makeText(requireContext(), R.string.usage_reset_pending, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun addShortcutCard(
        title: String,
        subtitle: String,
        isActive: Boolean,
        iconRes: Int,
        onToggle: ((Boolean) -> Unit)?,
        onClick: () -> Unit
    ) {
        val item = LayoutInflater.from(requireContext()).inflate(R.layout.item_usage_shortcut, binding.dynamicShortcutsContainer, false)
        item.findViewById<TextView>(R.id.tv_title).text = title
        item.findViewById<TextView>(R.id.tv_subtitle).text = subtitle
        
        try {
            item.findViewById<ImageView>(R.id.icon_type).setImageResource(iconRes)
        } catch (_: Exception) {}

        val switchView = item.findViewById<SwitchMaterial>(R.id.switch_active)
        if (onToggle != null) {
            switchView.visibility = View.VISIBLE
            switchView.isChecked = isActive
            switchView.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        } else {
            switchView.visibility = View.GONE
        }

        item.setOnClickListener { onClick() }
        binding.dynamicShortcutsContainer.addView(item)
    }

    private fun setupLineChart(lineChart: LineChart) {
        lineChart.apply {
            description.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(false)
            setPinchZoom(false)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                labelRotationAngle = 45f
                valueFormatter = HourAxisFormatter()
            }
            axisLeft.apply {
                valueFormatter = MinutesAxisFormatter()
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
            animateX(1000)
        }
    }

    private fun plotUsageData() {
        val hourlyUsage = stat.hourlyUsage
        val entries = hourlyUsage.mapIndexed { hour, durationMs ->
            Entry(hour.toFloat(), durationMs / (1000f * 60f))
        }
        val dataSet = LineDataSet(entries, "Usage Time (minutes)")
        setupChartUI(binding.lineChart, dataSet)
    }

    private fun setupChartUI(chart: LineChart, lineDataSet: LineDataSet) {
        val primaryColor = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(requireContext(), R.color.text_color))
        lineDataSet.apply {
            color = primaryColor
            valueTextColor = primaryColor
            lineWidth = 3f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            cubicIntensity = 0.2f
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            labelCount = 5
            setDrawGridLines(false)
            textColor = primaryColor
        }
        chart.axisLeft.apply {
            isEnabled = true
            setDrawGridLines(false)
            textColor = primaryColor
            valueFormatter = MinutesAxisFormatter()
            axisMinimum = 0f
        }
        chart.apply {
            axisRight.isEnabled = false
            legend.isEnabled = false
            description.isEnabled = false
            animateY(800, Easing.EaseInCubic)
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            data = LineData(lineDataSet)
        }
        chart.invalidate()
    }

    private class HourAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val hour = value.toInt()
            return String.format("%02d:00", hour)
        }
    }

    private class MinutesAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val totalMinutes = value.toInt()
            if (totalMinutes == 0) return "0m"
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (hours > 0) {
                if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            } else {
                "${minutes}m"
            }
        }
    }

    private class MinutesValueFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (value == 0f) return ""
            return "${value.toInt()}m"
        }
    }
}
