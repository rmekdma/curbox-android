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

/** Identity of one installed serialized worker, distinct from its lifecycle generation. */
@JvmInline
value class WorkerInstanceToken(val value: Long) {
    init {
        require(value >= 0L) { "worker instance token must not be negative" }
    }
}

/** A source observation's ordering pair, reserved as one connection-scoped operation. */
data class SourceOrderReservation(
    val sourceOrderIdentity: SourceOrderIdentity,
    val runtimeRevision: RuntimeRevision
)

/** The raw observation signal supplied by a source adapter. */
enum class ObservationKind {
    REAL_EVENT,
    SYNTHETIC_RECHECK,
    REFRESH,
    RECONNECT,
    SCREEN_WAKE,
    SCREEN_OFF,
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

/**
 * The one allocator for source-order and runtime-publication domains.
 *
 * Non-publication observations use [nextSourceOrderIdentity]. Settings and refresh publications
 * use [reserveRuntimePublication], which reserves both values as one atomic pair.
 */
interface ConnectionScopedSourceOrderSequencer {
    /** Reserves the identity for a non-publication source observation. */
    fun nextSourceOrderIdentity(): SourceOrderIdentity

    /** Atomically reserves the source identity and revision for one runtime publication. */
    fun reserveRuntimePublication(): SourceOrderReservation
}

/** Simple production-capable connection-scoped allocator. */
class AtomicConnectionScopedSourceOrderSequencer(
    initialSourceOrder: Long = 0L,
    initialRuntimeRevision: Long = 0L
) : ConnectionScopedSourceOrderSequencer {
    private val sourceOrder = java.util.concurrent.atomic.AtomicLong(initialSourceOrder)
    private val runtimeRevision = java.util.concurrent.atomic.AtomicLong(initialRuntimeRevision)
    private val allocationLock = Any()

    init {
        require(initialSourceOrder >= 0L) { "source order identity must not be negative" }
        require(initialRuntimeRevision >= 0L) { "runtime revision must not be negative" }
    }

    override fun nextSourceOrderIdentity(): SourceOrderIdentity = synchronized(allocationLock) {
        SourceOrderIdentity(sourceOrder.incrementAndGet())
    }

    override fun reserveRuntimePublication(): SourceOrderReservation =
        synchronized(allocationLock) {
            SourceOrderReservation(
                sourceOrderIdentity = SourceOrderIdentity(sourceOrder.incrementAndGet()),
                runtimeRevision = RuntimeRevision(runtimeRevision.incrementAndGet())
            )
        }
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
    KEYGUARD,
    UNKNOWN
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

class ApplicationWindowsFact(
    packages: Set<String> = emptySet(),
    val unknownSlotCount: Int = 0,
    val readState: ForegroundReadState = ForegroundReadState.EMPTY,
    val freshness: ApplicationWindowsFreshness = ApplicationWindowsFreshness.FRESH,
    stalePackages: Set<String> = emptySet()
) {
    val packages: Set<String> = immutablePackageSet(packages)
    val stalePackages: Set<String> = immutablePackageSet(stalePackages - this.packages)

    init {
        require(unknownSlotCount >= 0) { "unknown application window count must not be negative" }
    }

    /** Alias matching the architecture vocabulary without introducing a second authority. */
    val reportedPackages: Set<String>
        get() = packages

    internal fun normalized(): ApplicationWindowsFact = copy(
        packages = packages
    )

    fun copy(
        packages: Set<String> = this.packages,
        unknownSlotCount: Int = this.unknownSlotCount,
        readState: ForegroundReadState = this.readState,
        freshness: ApplicationWindowsFreshness = this.freshness,
        stalePackages: Set<String> = this.stalePackages
    ): ApplicationWindowsFact = ApplicationWindowsFact(
        packages = packages,
        unknownSlotCount = unknownSlotCount,
        readState = readState,
        freshness = freshness,
        stalePackages = stalePackages
    )

    operator fun component1(): Set<String> = packages

    operator fun component2(): Int = unknownSlotCount

    operator fun component3(): ForegroundReadState = readState

    operator fun component4(): ApplicationWindowsFreshness = freshness

    operator fun component5(): Set<String> = stalePackages

    override fun equals(other: Any?): Boolean =
        this === other || (other is ApplicationWindowsFact &&
            packages == other.packages &&
            stalePackages == other.stalePackages &&
            unknownSlotCount == other.unknownSlotCount &&
            readState == other.readState &&
            freshness == other.freshness)

    override fun hashCode(): Int = ((((packages.hashCode() * 31 + stalePackages.hashCode()) * 31 +
        unknownSlotCount) * 31 + readState.hashCode()) * 31 + freshness.hashCode())

    override fun toString(): String =
        "ApplicationWindowsFact(packages=$packages, stalePackages=$stalePackages, " +
            "unknownSlotCount=$unknownSlotCount, readState=$readState, freshness=$freshness)"
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
class ForegroundEvidencePolicySnapshot(
    essentialPackages: Set<String> = emptySet()
) {
    val essentialPackages: Set<String> = immutablePackageSet(essentialPackages)

    internal fun normalized(): ForegroundEvidencePolicySnapshot = copy(
        essentialPackages = essentialPackages
    )

    fun copy(
        essentialPackages: Set<String> = this.essentialPackages
    ): ForegroundEvidencePolicySnapshot = ForegroundEvidencePolicySnapshot(essentialPackages)

    operator fun component1(): Set<String> = essentialPackages

    override fun equals(other: Any?): Boolean =
        this === other || (other is ForegroundEvidencePolicySnapshot &&
            essentialPackages == other.essentialPackages)

    override fun hashCode(): Int = essentialPackages.hashCode()

    override fun toString(): String =
        "ForegroundEvidencePolicySnapshot(essentialPackages=$essentialPackages)"
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

class ForegroundEvidenceResult(
    outcomes: List<ForegroundEvidenceOutcome>
) {
    val outcomes: List<ForegroundEvidenceOutcome> =
        java.util.Collections.unmodifiableList(java.util.ArrayList(outcomes))

    fun copy(
        outcomes: List<ForegroundEvidenceOutcome> = this.outcomes
    ): ForegroundEvidenceResult = ForegroundEvidenceResult(outcomes)

    operator fun component1(): List<ForegroundEvidenceOutcome> = outcomes

    override fun equals(other: Any?): Boolean =
        this === other || (other is ForegroundEvidenceResult && outcomes == other.outcomes)

    override fun hashCode(): Int = outcomes.hashCode()

    override fun toString(): String = "ForegroundEvidenceResult(outcomes=$outcomes)"
}

interface ForegroundObservationSource {
    fun capture(trigger: ObservationTrigger): ForegroundFacts
}
