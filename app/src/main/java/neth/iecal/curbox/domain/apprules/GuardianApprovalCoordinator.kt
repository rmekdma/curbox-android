package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.GuardianApprovalWorkReceipt

/** Owns one guardian screen request and its current confirmation. */
class GuardianApprovalCoordinator {
    enum class ConfirmationPhase {
        CHECKING,
        OFFERED,
        FINISHED,
        TIMED_OUT
    }

    data class Confirmation(
        val operationId: String,
        val checkId: String,
        val receipt: GuardianApprovalWorkReceipt,
        val phase: ConfirmationPhase,
        val offerId: String? = null
    )

    data class Owner(
        val screenRequestId: String,
        val packageName: String,
        val lifecycleGeneration: LifecycleGeneration,
        val confirmation: Confirmation? = null
    )

    data class CheckIdentity(
        val screenRequestId: String,
        val operationId: String,
        val checkId: String,
        val lifecycleGeneration: LifecycleGeneration
    )

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
        val previous = current.confirmation
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
            confirmation = Confirmation(
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
        val previous = current.confirmation ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            previous.operationId != operationId ||
            previous.phase == ConfirmationPhase.CHECKING ||
            checkId.isBlank() || checkId == previous.checkId ||
            (receipt != null && previous.receipt != receipt)
        ) return@synchronized false
        owner = current.copy(
            confirmation = previous.copy(
                checkId = checkId,
                phase = ConfirmationPhase.CHECKING,
                offerId = null
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

        val previous = current.confirmation
        if (previous != null) {
            if (previous.operationId != operationId || previous.receipt != receipt) {
                return@synchronized false
            }
            if (previous.checkId == checkId) return@synchronized false
        }

        owner = current.copy(
            confirmation = Confirmation(
                operationId = operationId,
                checkId = checkId,
                receipt = receipt,
                phase = ConfirmationPhase.CHECKING
            )
        )
        true
    }

    /** Atomically moves an allowed check to its one-shot UI offer state. */
    fun offerConfirmation(identity: CheckIdentity, offerId: String): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        val confirmation = current.confirmation ?: return@synchronized false
        if (!matches(current, confirmation, identity) ||
            confirmation.phase != ConfirmationPhase.CHECKING || offerId.isBlank()
        ) return@synchronized false
        owner = current.copy(
            confirmation = confirmation.copy(
                phase = ConfirmationPhase.OFFERED,
                offerId = offerId
            )
        )
        true
    }

    /** Reopens a rejected offer as the same read-only check, without changing its receipt. */
    fun rejectConfirmationOffer(identity: CheckIdentity, offerId: String): Boolean =
        synchronized(lock) {
            val current = owner ?: return@synchronized false
            val confirmation = current.confirmation ?: return@synchronized false
            if (!matches(current, confirmation, identity) ||
                confirmation.phase != ConfirmationPhase.OFFERED ||
                confirmation.offerId != offerId
            ) return@synchronized false
            owner = current.copy(
                confirmation = confirmation.copy(
                    phase = ConfirmationPhase.CHECKING,
                    offerId = null
                )
            )
            true
        }

    fun timeOutConfirmation(identity: CheckIdentity): Boolean =
        updateConfirmationPhase(identity, ConfirmationPhase.TIMED_OUT)

    fun completeConfirmation(identity: CheckIdentity): Boolean =
        updateConfirmationPhase(identity, ConfirmationPhase.FINISHED)

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
        val confirmation = current.confirmation ?: return@synchronized false
        if (!matches(current, confirmation, identity) ||
            (confirmation.phase != ConfirmationPhase.CHECKING &&
                confirmation.phase != ConfirmationPhase.OFFERED)
        ) return@synchronized false
        owner = current.copy(confirmation = confirmation.copy(phase = nextPhase))
        true
    }

    private fun matches(
        current: Owner,
        confirmation: Confirmation,
        identity: CheckIdentity
    ): Boolean = current.screenRequestId == identity.screenRequestId &&
        current.lifecycleGeneration == identity.lifecycleGeneration &&
        confirmation.operationId == identity.operationId &&
        confirmation.checkId == identity.checkId

    companion object {
        const val CONFIRMATION_TIMEOUT_MS = 10_000L
    }
}
