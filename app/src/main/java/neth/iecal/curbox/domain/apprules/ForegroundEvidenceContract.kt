package neth.iecal.curbox.domain.apprules

/** The source-order domain is intentionally distinct from runtime revisions and lifecycle generations. */
@JvmInline
value class SourceOrderIdentity(val value: Long) {
    init {
        require(value >= 0L) { "source order identity must not be negative" }
    }
}

@JvmInline
value class RuntimeRevision(val value: Long) {
    init {
        require(value >= 0L) { "runtime revision must not be negative" }
    }
}

@JvmInline
value class LifecycleGeneration(val value: Long) {
    init {
        require(value >= 0L) { "lifecycle generation must not be negative" }
    }
}

/** The raw observation signal supplied by a source adapter. */
enum class ObservationKind {
    REAL_EVENT,
    SYNTHETIC_RECHECK,
    REFRESH,
    RECONNECT,
    SCREEN_WAKE,
    USER_PRESENT
}

/** A source observation trigger. It contains no framework object or scheduling state. */
data class ObservationTrigger(
    val sourceOrderIdentity: SourceOrderIdentity,
    val kind: ObservationKind,
    val eventPackage: String? = null,
    val requestedAtWallMs: Long,
    val requestedAtElapsedMs: Long
) {
    init {
        require(requestedAtWallMs >= 0L) { "wall time must not be negative" }
        require(requestedAtElapsedMs >= 0L) { "elapsed time must not be negative" }
        require(eventPackage == eventPackage?.trim()) {
            "event package must be normalized before crossing the value seam"
        }
    }
}

/** The one allocator for source-order and runtime-publication domains. */
interface ConnectionScopedSourceOrderSequencer {
    fun nextSourceOrderIdentity(): SourceOrderIdentity

    fun nextRuntimeRevision(): RuntimeRevision
}

/** Simple production-capable connection-scoped allocator. */
class AtomicConnectionScopedSourceOrderSequencer(
    initialSourceOrder: Long = 0L,
    initialRuntimeRevision: Long = 0L
) : ConnectionScopedSourceOrderSequencer {
    private val sourceOrder = java.util.concurrent.atomic.AtomicLong(initialSourceOrder)
    private val runtimeRevision = java.util.concurrent.atomic.AtomicLong(initialRuntimeRevision)

    init {
        require(initialSourceOrder >= 0L) { "source order identity must not be negative" }
        require(initialRuntimeRevision >= 0L) { "runtime revision must not be negative" }
    }

    override fun nextSourceOrderIdentity(): SourceOrderIdentity =
        SourceOrderIdentity(sourceOrder.incrementAndGet())

    override fun nextRuntimeRevision(): RuntimeRevision =
        RuntimeRevision(runtimeRevision.incrementAndGet())
}

/** Raw read state. The evidence module decides what each state means. */
enum class ForegroundReadState {
    AVAILABLE,
    EMPTY,
    FAILED
}

enum class ApplicationWindowsFreshness {
    FRESH,
    STALE
}

enum class DisplayState {
    SCREEN_OFF,
    UNLOCKED,
    KEYGUARD
}

data class SignalFact(
    val kind: ObservationKind = ObservationKind.SYNTHETIC_RECHECK,
    val eventPackage: String? = null,
    val eventWallMs: Long? = null,
    val eventElapsedMs: Long? = null
) {
    init {
        require(eventPackage == eventPackage?.trim()) {
            "event package must be normalized before crossing the value seam"
        }
        require(eventWallMs == null || eventWallMs >= 0L) { "event wall time must not be negative" }
        require(eventElapsedMs == null || eventElapsedMs >= 0L) {
            "event elapsed time must not be negative"
        }
    }

    internal fun normalized(): SignalFact = copy(
        eventPackage = eventPackage?.trim()?.takeIf(String::isNotEmpty)
    )
}

data class ActiveRootFact(
    val packageName: String? = null,
    val readState: ForegroundReadState = ForegroundReadState.EMPTY
) {
    init {
        require(packageName == packageName?.trim()) {
            "active root package must be normalized before crossing the value seam"
        }
    }

    internal fun normalized(): ActiveRootFact = copy(
        packageName = packageName?.trim()?.takeIf(String::isNotEmpty)
    )
}

data class ApplicationWindowsFact(
    val packages: Set<String> = emptySet(),
    val unknownSlotCount: Int = 0,
    val readState: ForegroundReadState = ForegroundReadState.EMPTY,
    val freshness: ApplicationWindowsFreshness = ApplicationWindowsFreshness.FRESH
) {
    init {
        require(unknownSlotCount >= 0) { "unknown application window count must not be negative" }
    }

    /** Alias matching the architecture vocabulary without introducing a second authority. */
    val reportedPackages: Set<String>
        get() = packages

    internal fun normalized(): ApplicationWindowsFact = copy(
        packages = immutablePackageSet(packages)
    )
}

/** Raw framework facts. No policy-derived package set, retry state, or deadline is carried here. */
data class ForegroundFacts(
    val capturedAtWallMs: Long,
    val capturedAtElapsedMs: Long,
    val signal: SignalFact = SignalFact(),
    val activeRoot: ActiveRootFact = ActiveRootFact(),
    val applicationWindows: ApplicationWindowsFact = ApplicationWindowsFact(),
    val displayState: DisplayState = DisplayState.UNLOCKED
) {
    init {
        require(capturedAtWallMs >= 0L) { "capture wall time must not be negative" }
        require(capturedAtElapsedMs >= 0L) { "capture elapsed time must not be negative" }
    }

    internal fun normalized(): ForegroundFacts = copy(
        signal = signal.normalized(),
        activeRoot = activeRoot.normalized(),
        applicationWindows = applicationWindows.normalized()
    )
}

/** The canonical policy input for evidence interpretation. */
data class ForegroundEvidencePolicySnapshot(
    val essentialPackages: Set<String> = emptySet()
) {
    internal fun normalized(): ForegroundEvidencePolicySnapshot = copy(
        essentialPackages = immutablePackageSet(essentialPackages)
    )
}

private fun immutablePackageSet(packages: Set<String>): Set<String> =
    java.util.Collections.unmodifiableSet(
        packages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toCollection(LinkedHashSet())
    )

enum class EvidenceBasis {
    RECENT_REAL_EVENT,
    ACTIVE_ROOT,
    REAL_EVENT_AND_ACTIVE_ROOT,
    APPLICATION_WINDOW,
    DIFFERENT_ACTIVE_ROOT,
    SCREEN_OFF,
    KEYGUARD,
    NO_RELIABLE_EVIDENCE
}

enum class SessionEvidenceEffect {
    RENEW,
    PRESERVE,
    END_WITHOUT_RENEWAL
}

enum class DecisionPermission {
    EVALUATE,
    EVALUATE_FAIL_CLOSED,
    DEFER,
    DO_NOT_EVALUATE
}

sealed class EvidenceValidity {
    data class RenewedUntil(val validUntilElapsedMs: Long) : EvidenceValidity() {
        init {
            require(validUntilElapsedMs >= 0L) { "validity endpoint must not be negative" }
        }
    }

    data object NotRenewed : EvidenceValidity()
}

enum class FollowUpKind {
    NONE,
    RETRY_FOR_RELIABLE_EVIDENCE,
    WAIT_FOR_RELIABLE_EVIDENCE,
    WAIT_FOR_USER_PRESENT
}

sealed class ForegroundEvidenceOutcome {
    abstract val packageName: String?
    abstract val evidenceBasis: EvidenceBasis
    abstract val sessionEffect: SessionEvidenceEffect
    abstract val decisionPermission: DecisionPermission
    abstract val evidenceValidity: EvidenceValidity
    abstract val followUp: FollowUpKind

    data class Visible(
        override val packageName: String,
        override val evidenceBasis: EvidenceBasis,
        override val sessionEffect: SessionEvidenceEffect,
        override val decisionPermission: DecisionPermission,
        override val evidenceValidity: EvidenceValidity,
        override val followUp: FollowUpKind
    ) : ForegroundEvidenceOutcome()

    data class NotVisible(
        override val packageName: String,
        override val evidenceBasis: EvidenceBasis,
        override val sessionEffect: SessionEvidenceEffect,
        override val decisionPermission: DecisionPermission,
        override val evidenceValidity: EvidenceValidity,
        override val followUp: FollowUpKind
    ) : ForegroundEvidenceOutcome()

    data class Unknown(
        val candidatePackage: String?,
        override val evidenceBasis: EvidenceBasis,
        override val sessionEffect: SessionEvidenceEffect,
        override val decisionPermission: DecisionPermission,
        override val evidenceValidity: EvidenceValidity,
        override val followUp: FollowUpKind
    ) : ForegroundEvidenceOutcome() {
        override val packageName: String?
            get() = candidatePackage
    }
}

data class ForegroundEvidenceResult(
    val outcomes: List<ForegroundEvidenceOutcome>
)

interface ForegroundObservationSource {
    fun capture(trigger: ObservationTrigger): ForegroundFacts
}
