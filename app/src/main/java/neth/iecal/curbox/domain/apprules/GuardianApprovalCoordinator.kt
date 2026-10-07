package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt
import neth.iecal.curbox.data.models.GuardianApprovalGrantOrigin
import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt

/** Owns one guardian screen request and the operation currently allowed to complete it. */
class GuardianApprovalCoordinator {
    enum class ConfirmationPhase {
        CHECKING,
        FINISHED,
        TIMED_OUT
    }

    sealed interface Operation {
        data class Confirmation(
            val operationId: String,
            val checkId: String,
            val receipt: GuardianApprovalWorkReceipt,
            val phase: ConfirmationPhase
        ) : Operation

        data class Legacy(val operationId: String) : Operation
    }

    data class Owner(
        val screenRequestId: String,
        val packageName: String,
        val lifecycleGeneration: LifecycleGeneration,
        val operation: Operation? = null
    )

    data class CheckIdentity(
        val screenRequestId: String,
        val operationId: String,
        val checkId: String,
        val lifecycleGeneration: LifecycleGeneration
    )

    /** Compatibility identity for callers from the direct-grant flow. */
    data class DirectCheckIdentity(
        val screenRequestId: String,
        val operationId: String,
        val checkId: String,
        val lifecycleGeneration: LifecycleGeneration
    ) {
        fun asCheckIdentity() = CheckIdentity(
            screenRequestId,
            operationId,
            checkId,
            lifecycleGeneration
        )
    }

    private val lock = Any()
    private var owner: Owner? = null

    fun openScreen(
        screenRequestId: String,
        packageName: String,
        lifecycleGeneration: LifecycleGeneration
    ): Boolean {
        if (screenRequestId.isBlank() || packageName.isBlank()) return false
        synchronized(lock) {
            val current = owner
            if (current?.screenRequestId == screenRequestId) {
                return current.packageName == packageName &&
                    current.lifecycleGeneration == lifecycleGeneration
            }
            owner = Owner(screenRequestId, packageName, lifecycleGeneration)
            return true
        }
    }

    fun beginConfirmation(
        screenRequestId: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            operationId.isBlank() || checkId.isBlank()
        ) return@synchronized false
        val previous = current.operation as? Operation.Confirmation
        if (previous != null) {
            if (previous.phase == ConfirmationPhase.CHECKING) return@synchronized false
            if (previous.operationId == operationId && previous.checkId == checkId) {
                return@synchronized false
            }
            if (previous.operationId == operationId && previous.receipt != receipt) {
                return@synchronized false
            }
        }
        owner = current.copy(
            operation = Operation.Confirmation(
                operationId = operationId,
                checkId = checkId,
                receipt = receipt,
                phase = ConfirmationPhase.CHECKING
            )
        )
        true
    }

    fun retryConfirmation(
        screenRequestId: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt? = null
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        val previous = current.operation as? Operation.Confirmation ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            previous.operationId != operationId ||
            previous.phase == ConfirmationPhase.CHECKING ||
            checkId.isBlank() || checkId == previous.checkId ||
            (receipt != null && previous.receipt != receipt)
        ) return@synchronized false
        owner = current.copy(
            operation = previous.copy(
                checkId = checkId,
                phase = ConfirmationPhase.CHECKING
            )
        )
        true
    }

    /** Reattaches a live approval receipt after service reconnection or screen re-registration. */
    fun rebindConfirmation(
        screenRequestId: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalWorkReceipt,
        lifecycleGeneration: LifecycleGeneration
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            operationId.isBlank() || checkId.isBlank() ||
            current.lifecycleGeneration != lifecycleGeneration
        ) return@synchronized false

        val previous = current.operation
        if (previous != null) {
            val confirmation = previous as? Operation.Confirmation
                ?: return@synchronized false
            if (confirmation.operationId != operationId || confirmation.receipt != receipt) {
                return@synchronized false
            }
            if (confirmation.checkId == checkId) return@synchronized false
        }

        owner = current.copy(
            operation = Operation.Confirmation(
                operationId = operationId,
                checkId = checkId,
                receipt = receipt,
                phase = ConfirmationPhase.CHECKING
            )
        )
        true
    }

    fun timeOutConfirmation(identity: CheckIdentity): Boolean =
        updateConfirmationPhase(identity, ConfirmationPhase.TIMED_OUT)

    fun completeConfirmation(identity: CheckIdentity): Boolean =
        updateConfirmationPhase(identity, ConfirmationPhase.FINISHED)

    fun beginDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalGrantReceipt
    ): Boolean = beginConfirmation(
        screenRequestId,
        operationId,
        checkId,
        GuardianApprovalWorkReceipt.Grant(
            grant = receipt,
            origin = GuardianApprovalGrantOrigin.DIRECT,
            useDayGenerationStartedAtMs = 0L
        )
    )

    fun retryDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String
    ): Boolean = retryConfirmation(screenRequestId, operationId, checkId)

    fun timeOutDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String
    ): Boolean {
        val generation = currentOwner()?.lifecycleGeneration ?: return false
        return updateConfirmationPhase(
            CheckIdentity(
                screenRequestId,
                operationId,
                checkId,
                generation
            ),
            ConfirmationPhase.TIMED_OUT
        )
    }

    fun timeOutDirectCheck(identity: DirectCheckIdentity): Boolean =
        timeOutConfirmation(identity.asCheckIdentity())

    fun completeDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String
    ): Boolean {
        val generation = currentOwner()?.lifecycleGeneration ?: return false
        return updateConfirmationPhase(
            CheckIdentity(
                screenRequestId,
                operationId,
                checkId,
                generation
            ),
            ConfirmationPhase.FINISHED
        )
    }

    fun completeDirectCheck(identity: DirectCheckIdentity): Boolean =
        completeConfirmation(identity.asCheckIdentity())

    fun beginLegacyOperation(screenRequestId: String, operationId: String): Boolean =
        synchronized(lock) {
            val current = owner ?: return@synchronized false
            if (current.screenRequestId != screenRequestId || operationId.isBlank()) {
                return@synchronized false
            }
            val confirmation = current.operation as? Operation.Confirmation
            if (confirmation?.phase == ConfirmationPhase.CHECKING) return@synchronized false
            owner = current.copy(operation = Operation.Legacy(operationId))
            true
        }

    fun cancelLegacyOperation(screenRequestId: String, operationId: String): Boolean =
        synchronized(lock) {
            val current = owner ?: return@synchronized false
            val legacy = current.operation as? Operation.Legacy ?: return@synchronized false
            if (current.screenRequestId != screenRequestId || legacy.operationId != operationId) {
                return@synchronized false
            }
            owner = current.copy(operation = null)
            true
        }

    fun completeLegacyOperation(screenRequestId: String, operationId: String): Boolean =
        synchronized(lock) {
            val current = owner ?: return@synchronized false
            val legacy = current.operation as? Operation.Legacy ?: return@synchronized false
            if (current.screenRequestId != screenRequestId || legacy.operationId != operationId) {
                return@synchronized false
            }
            owner = null
            true
        }

    fun closeScreen(screenRequestId: String): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (current.screenRequestId != screenRequestId) return@synchronized false
        owner = null
        true
    }

    fun currentOwner(): Owner? = synchronized(lock) { owner }

    private fun updateConfirmationPhase(
        identity: CheckIdentity,
        nextPhase: ConfirmationPhase
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        val confirmation = current.operation as? Operation.Confirmation ?: return@synchronized false
        if (current.screenRequestId != identity.screenRequestId ||
            current.lifecycleGeneration != identity.lifecycleGeneration ||
            confirmation.operationId != identity.operationId ||
            confirmation.checkId != identity.checkId ||
            confirmation.phase != ConfirmationPhase.CHECKING
        ) return@synchronized false
        owner = current.copy(operation = confirmation.copy(phase = nextPhase))
        true
    }

    companion object {
        const val CONFIRMATION_TIMEOUT_MS = 10_000L
    }
}
