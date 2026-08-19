package neth.iecal.curbox.domain.apprules

/** Small pure policy seam for the service-owned reset command barrier. */
object UsageResetCommandPolicy {
    /** The service acceptance instant is the one boundary shared by every package in a command. */
    fun acceptedAt(serviceMainNowMs: Long): Long = serviceMainNowMs

    /** A request touching an in-flight package must fail as a whole. */
    fun hasPendingOverlap(
        requestedPackages: Set<String>,
        pendingPackages: Set<String>
    ): Boolean = requestedPackages.any { it in pendingPackages }
}
