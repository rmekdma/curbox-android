package neth.iecal.curbox.utils

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import neth.iecal.curbox.domain.apprules.UsageResetRequest
import neth.iecal.curbox.domain.apprules.UsageResetDelta
import neth.iecal.curbox.domain.apprules.UsageResetResult
import java.util.UUID

/** Process boundary used by the UI.  The service owns the Room transaction and session writer. */
fun interface UsageResetCommandSender {
    fun send(request: UsageResetRequest): Boolean
}

enum class UsageResetStatus {
    SUCCESS,
    FAILED,
    PENDING
}

data class UsageResetOutcome(
    val status: UsageResetStatus,
    val request: UsageResetRequest? = null,
    val result: UsageResetResult? = null
)

/** Entry point for immediate app and group usage reset commands. */
class UsageResetManager(
    context: Context,
    private val commandSender: UsageResetCommandSender =
        BroadcastUsageResetCommandSender(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val dataStore = DataStoreManager(appContext)

    suspend fun resetApp(
        packageName: String,
        resetAtMs: Long = System.currentTimeMillis()
    ): UsageResetOutcome = resetPackages(
        packageNames = setOf(packageName.trim()).filter(String::isNotEmpty).toSet(),
        resetAtMs = resetAtMs
    )

    suspend fun resetGroup(
        groupId: String,
        resetAtMs: Long = System.currentTimeMillis()
    ): UsageResetOutcome {
        val settings = try {
            dataStore.settings.first()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return UsageResetOutcome(UsageResetStatus.FAILED)
        }
        val packages = settings.appRuleSnapshot.appGroups
            .find { it.id == groupId }
            ?.packagesAt(resetAtMs)
            .orEmpty()
        return resetPackages(packages, resetAtMs, settings)
    }

    private suspend fun resetPackages(
        packageNames: Set<String>,
        resetAtMs: Long,
        knownSettings: neth.iecal.curbox.data.models.Settings? = null
    ): UsageResetOutcome {
        if (packageNames.isEmpty()) return UsageResetOutcome(UsageResetStatus.FAILED)
        val settings = try {
            knownSettings ?: dataStore.settings.first()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return UsageResetOutcome(UsageResetStatus.FAILED)
        }
        val calculator = ConfigurableUseDayCalculator(resetTime = settings.useDayResetTime)
        val useDayId = calculator.idAt(resetAtMs)
        val request = UsageResetRequest(
            useDayId = useDayId,
            generationStartedAtMs = settings.useDayGenerationStartedAtMs,
            packageNames = packageNames,
            resetAtMs = resetAtMs,
            requestId = UUID.randomUUID().toString()
        )
        val completion = CompletableDeferred<Boolean>()
        val completionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USAGE_RESET ||
                    intent.getStringExtra(EXTRA_REQUEST_ID) != request.requestId
                ) return
                completion.complete(
                    intent.getBooleanExtra(EXTRA_RESULT_OK, false)
                )
            }
        }
        try {
            ContextCompat.registerReceiver(
                appContext,
                completionReceiver,
                IntentFilter(ACTION_USAGE_RESET),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            // No service request was delivered; the caller must surface this local failure.
            return UsageResetOutcome(UsageResetStatus.FAILED)
        }
        val accepted = try {
            commandSender.send(request)
        } catch (error: CancellationException) {
            runCatching { appContext.unregisterReceiver(completionReceiver) }
            throw error
        } catch (_: Exception) {
            false
        }
        try {
            if (!accepted) {
                // The command never reached the service, so no completion broadcast will arrive.
                return UsageResetOutcome(UsageResetStatus.FAILED)
            }
            val completed = withTimeoutOrNull(10_000L) { completion.await() }
            return when (completed) {
                true -> UsageResetOutcome(
                    status = UsageResetStatus.SUCCESS,
                    request = request,
                    result = UsageResetResult(request, UsageResetDelta(emptyMap(), emptyMap()))
                )
                false -> UsageResetOutcome(UsageResetStatus.FAILED, request)
                null -> UsageResetOutcome(UsageResetStatus.PENDING, request)
            }
        } finally {
            runCatching { appContext.unregisterReceiver(completionReceiver) }
        }
    }

    companion object {
        /** Request consumed by the service-process tracker. */
        const val ACTION_USAGE_RESET_REQUEST = "neth.iecal.curbox.reset.current_usage.request"
        /** Completion signal published only after the Room transaction commits. */
        const val ACTION_USAGE_RESET = "neth.iecal.curbox.reset.current_usage"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_RESET_AT_MS = "reset_at_ms"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RESULT_OK = "result_ok"
    }
}

private class BroadcastUsageResetCommandSender(
    private val appContext: Context
) : UsageResetCommandSender {
    override fun send(request: UsageResetRequest): Boolean {
        val intent = Intent(UsageResetManager.ACTION_USAGE_RESET_REQUEST)
            .setPackage(appContext.packageName)
            .putExtra(UsageResetManager.EXTRA_REQUEST_ID, request.requestId)
            .putStringArrayListExtra(UsageResetManager.EXTRA_PACKAGES, ArrayList(request.packageNames))
            .putExtra(UsageResetManager.EXTRA_RESET_AT_MS, request.resetAtMs)
        appContext.sendBroadcast(intent)
        return true
    }
}
