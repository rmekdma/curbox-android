package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageResetCommandPolicyTest {
    @Test
    fun allPackagesUseTheServiceAcceptanceInstant() {
        assertEquals(1234L, UsageResetCommandPolicy.acceptedAt(1234L))
    }

    @Test
    fun anyPendingPackageRejectsTheWholeRequest() {
        assertTrue(
            UsageResetCommandPolicy.hasPendingOverlap(
                requestedPackages = setOf("first", "second"),
                pendingPackages = setOf("second")
            )
        )
    }
}
