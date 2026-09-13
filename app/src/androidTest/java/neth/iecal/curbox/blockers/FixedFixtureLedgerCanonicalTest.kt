package neth.iecal.curbox.blockers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedFixtureLedgerCanonicalTest {

    @Test
    fun canonicalRowSerializationMatchesExactKeyOrderAndEscaping() {
        val row = AttemptLedgerRow(
            runId = "test-run-123",
            attemptOrdinal = 1,
            sampleOrdinal = 1,
            phase = "WARM_UP",
            fixtureLabel = "T27_ALLOW",
            sourceOrderIdentity = "id-001",
            observationKind = "REAL_EVENT",
            lifecycleGeneration = 1L,
            acceptedRuntimeRevision = 1L,
            callbackExitKind = "NORMAL",
            packageDecisionCount = 1,
            decision = "ALLOW",
            denyingRuleCount = 0,
            commitStatus = "COMMITTED",
            publicationStatus = "PUBLISHED",
            callbackStartNs = 1000L,
            callbackReturnNs = 2000L,
            selectedEndNs = 3000L,
            quiescenceStartNs = 3000L,
            quiescenceEndNs = 4000L,
            callbackDurationNs = 1000L,
            selectedDurationNs = 2000L,
            selectedEndMinusCallbackReturnNs = 1000L,
            quiescenceDurationNs = 1000L,
            callbackReturnPresent = true,
            selectedEndPresent = true,
            terminalState = "VALID_SAMPLE",
            exclusionReason = null
        )

        val json = serializeCanonicalSampleRow(row)
        assertTrue("JSON must end with newline", json.endsWith("\n"))
        val trimmed = json.trimEnd('\n')
        assertTrue("JSON must not contain newline before the end", !trimmed.contains("\n"))

        val expectedPrefix = "{\"runId\":\"test-run-123\",\"attemptOrdinal\":1,\"sampleOrdinal\":1,\"phase\":\"WARM_UP\",\"fixtureLabel\":\"T27_ALLOW\","
        assertTrue("JSON must match exact key order at prefix: $trimmed", trimmed.startsWith(expectedPrefix))
        assertTrue("Null field must be lowercase null: $trimmed", trimmed.endsWith("\"exclusionReason\":null}"))
    }
}
