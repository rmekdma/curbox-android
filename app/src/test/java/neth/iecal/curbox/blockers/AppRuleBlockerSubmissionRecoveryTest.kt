package neth.iecal.curbox.blockers

import neth.iecal.curbox.domain.apprules.SubmissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleBlockerSubmissionRecoveryTest {
    @Test
    fun repeatedNotReadyRetryIsReportedOnceAndDoesNotPretendTheSubmissionSucceeded() {
        var retryCount = 0
        var recoveryCount = 0

        val result = retryRejectedWorkerSubmission(
            initialResult = SubmissionResult.REJECTED_NOT_READY,
            canRetry = { true },
            retry = {
                retryCount += 1
                SubmissionResult.REJECTED_NOT_READY
            },
            onRepeatedRejection = { recoveryCount += 1 }
        )

        assertEquals(SubmissionResult.REJECTED_NOT_READY, result)
        assertEquals(1, retryCount)
        assertEquals(1, recoveryCount)
    }

    @Test
    fun recoveryCallbackMakesTheNextSubmissionAvailableAfterRepeatedRejection() {
        var workerReady = false
        var recoveryCount = 0

        val firstResult = retryRejectedWorkerSubmission(
            initialResult = SubmissionResult.REJECTED_NOT_READY,
            canRetry = { true },
            retry = { SubmissionResult.REJECTED_NOT_READY },
            onRepeatedRejection = {
                recoveryCount += 1
                workerReady = true
            }
        )
        val nextResult = if (workerReady) {
            SubmissionResult.ACCEPTED
        } else {
            SubmissionResult.REJECTED_NOT_READY
        }

        assertEquals(SubmissionResult.REJECTED_NOT_READY, firstResult)
        assertEquals(1, recoveryCount)
        assertTrue("recovery must leave the next event able to submit", workerReady)
        assertEquals(SubmissionResult.ACCEPTED, nextResult)
    }
}
