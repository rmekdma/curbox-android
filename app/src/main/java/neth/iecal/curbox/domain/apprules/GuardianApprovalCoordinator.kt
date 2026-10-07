package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.GuardianApprovalGrantReceipt

/** Owns one guardian screen request and the operation currently allowed to complete it. */
class GuardianApprovalCoordinator {
    enum class DirectCheckPhase {
        CHECKING,
        FINISHED,
        TIMED_OUT
    }

    sealed interface Operation {
        data class Direct(
            val operationId: String,
            val checkId: String,
            val receipt: GuardianApprovalGrantReceipt,
            val phase: DirectCheckPhase
        ) : Operation

        data class Legacy(val operationId: String) : Operation
    }

    data class Owner(
        val screenRequestId: String,
        val packageName: String,
        val lifecycleGeneration: LifecycleGeneration,
        val operation: Operation? = null
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

    fun beginDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String,
        receipt: GuardianApprovalGrantReceipt
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            operationId.isBlank() || checkId.isBlank()
        ) return@synchronized false
        when (val previous = current.operation) {
            is Operation.Direct -> {
                if (previous.phase == DirectCheckPhase.CHECKING) return@synchronized false
                if (previous.operationId == operationId && previous.checkId == checkId) {
                    return@synchronized false
                }
                if (previous.operationId == operationId && previous.receipt != receipt) {
                    return@synchronized false
                }
            }
            else -> Unit
        }
        owner = current.copy(
            operation = Operation.Direct(
                operationId = operationId,
                checkId = checkId,
                receipt = receipt,
                phase = DirectCheckPhase.CHECKING
            )
        )
        true
    }

    fun retryDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        val previous = current.operation as? Operation.Direct ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            previous.operationId != operationId ||
            previous.phase == DirectCheckPhase.CHECKING ||
            checkId.isBlank() || checkId == previous.checkId
        ) return@synchronized false
        owner = current.copy(
            operation = previous.copy(
                checkId = checkId,
                phase = DirectCheckPhase.CHECKING
            )
        )
        true
    }

    fun timeOutDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String
    ): Boolean = updateDirectPhase(
        screenRequestId,
        operationId,
        checkId,
        DirectCheckPhase.TIMED_OUT
    )

    fun completeDirectCheck(
        screenRequestId: String,
        operationId: String,
        checkId: String
    ): Boolean = updateDirectPhase(
        screenRequestId,
        operationId,
        checkId,
        DirectCheckPhase.FINISHED
    )

    fun beginLegacyOperation(screenRequestId: String, operationId: String): Boolean =
        synchronized(lock) {
            val current = owner ?: return@synchronized false
            if (current.screenRequestId != screenRequestId || operationId.isBlank()) {
                return@synchronized false
            }
            val direct = current.operation as? Operation.Direct
            if (direct?.phase == DirectCheckPhase.CHECKING) return@synchronized false
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

    private fun updateDirectPhase(
        screenRequestId: String,
        operationId: String,
        checkId: String,
        nextPhase: DirectCheckPhase
    ): Boolean = synchronized(lock) {
        val current = owner ?: return@synchronized false
        val direct = current.operation as? Operation.Direct ?: return@synchronized false
        if (current.screenRequestId != screenRequestId ||
            direct.operationId != operationId ||
            direct.checkId != checkId ||
            direct.phase != DirectCheckPhase.CHECKING
        ) return@synchronized false
        owner = current.copy(operation = direct.copy(phase = nextPhase))
        true
    }

    companion object {
        const val CONFIRMATION_TIMEOUT_MS = 10_000L
    }
}
