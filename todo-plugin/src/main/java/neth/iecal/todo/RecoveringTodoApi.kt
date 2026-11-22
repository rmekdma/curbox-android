package neth.iecal.todo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

/** Recovers a rejected session once, coordinating both app processes through the store. */
internal class RecoveringTodoApi(private val delegate: TodoApi, private val store: LearningStore, private val now: () -> Long) {
    suspend fun catalog(session: TodoSession) = lookup(session) { delegate.catalog(it) }
    suspend fun report(session: TodoSession, subject: Subject, userId: String, date: String) =
        lookup(session) { delegate.report(it, subject, userId, date) }

    private suspend fun <T> lookup(session: TodoSession, operation: (TodoSession) -> T): T {
        val snapshot = store.read()
        val ref = snapshot.sessionRef ?: throw TodoApiException(true)
        if (session.storageRef != null && session.storageRef != ref) throw TodoApiException(true)
        if (snapshot.authFailure == LearningFailure.AUTHENTICATION) throw TodoApiException(true)
        if (snapshot.authFailure != null && snapshot.authRetryAfter > now()) throw TodoApiException(kind = snapshot.authFailure)
        val initial = readSession(ref)?.takeIf { it.accountId == session.accountId } ?: throw TodoApiException(true)
        val generation = snapshot.authGeneration
        try {
            val value = runInterruptible { operation(initial) }
            if (snapshot.authFailure != null) {
                store.authTransaction { doc ->
                    if (doc.sessionRef == ref && doc.authGeneration == generation && doc.authFailure == snapshot.authFailure &&
                        doc.authRetryAfter == snapshot.authRetryAfter) {
                        doc.copy(authFailure = null, authRetryAfter = 0) to Unit
                    } else doc to Unit
                }
            }
            return value
        } catch (failure: TodoApiException) {
            if (!failure.authenticationRequired) throw failure
        }
        val recovered = recover(initial, ref, generation)
        try { return runInterruptible { operation(recovered) } } catch (failure: TodoApiException) {
            if (failure.authenticationRequired) store.authTransaction { doc ->
                if (doc.sessionRef == ref && readSession(ref) == recovered) {
                    doc.copy(authFailure = LearningFailure.AUTHENTICATION, authRetryAfter = 0) to Unit
                } else doc to Unit
            }
            throw failure
        }
    }
    private suspend fun recover(initial: TodoSession, expectedRef: String, generation: Long): TodoSession {
        val claim = java.util.UUID.randomUUID().toString()
        while (true) {
            val step = store.authTransaction { doc ->
                val ref = doc.sessionRef
                val current = if (ref == expectedRef) readSession(expectedRef) else null
                val now = now()
                when {
                    ref != expectedRef || current == null || current.accountId != initial.accountId -> doc to Step.Fail(TodoApiException(true))
                    doc.authFailure == LearningFailure.AUTHENTICATION -> doc to Step.Fail(TodoApiException(true))
                    doc.authGeneration != generation -> doc to Step.Ready(current)
                    doc.authRetryAfter > now -> doc to Step.Fail(TodoApiException(kind = doc.authFailure ?: LearningFailure.NETWORK))
                    current.email == null || current.password == null -> doc.copy(authFailure = LearningFailure.AUTHENTICATION) to Step.Fail(TodoApiException(true))
                    doc.authClaim != null && doc.authClaimUntil > now -> doc to Step.Wait
                    else -> doc.copy(authClaim = claim, authClaimUntil = now + 90_000) to Step.Login(requireNotNull(ref), current)
                }
            }
            when (step) {
                is Step.Ready -> return step.session
                is Step.Fail -> throw step.error
                Step.Wait -> { delay(250); continue }
                is Step.Login -> {
                    val result = try {
                        val next = runInterruptible { delegate.signIn(requireNotNull(step.session.email), requireNotNull(step.session.password)) }
                        if (next.accountId != step.session.accountId) throw TodoApiException(true)
                        Result.success(next.copy(email = step.session.email, password = step.session.password, storageRef = step.ref))
                    } catch (e: TodoApiException) { Result.failure(e) } catch (e: Throwable) {
                        store.authTransaction { doc ->
                            if (doc.authClaim == claim) doc.copy(authClaim = null, authClaimUntil = 0) to Unit else doc to Unit
                        }
                        throw e
                    }
                    return store.authTransaction { doc ->
                        if (doc.authClaim != claim || doc.sessionRef != step.ref) {
                            doc to Result.failure<TodoSession>(TodoApiException(true))
                        } else {
                            val error = result.exceptionOrNull() as? TodoApiException
                            if (error == null) store.writeSession(step.ref, result.getOrThrow())
                            doc.copy(authClaim = null, authClaimUntil = 0, authFailure = error?.kind,
                                authRetryAfter = if (error != null && !error.authenticationRequired) now() + 60_000 else 0,
                                authGeneration = if (error == null) Math.addExact(doc.authGeneration, 1) else doc.authGeneration) to result
                        }
                    }.getOrThrow()
                }
            }
        }
    }

    private fun readSession(ref: String): TodoSession? = try {
        store.readSession(ref)
    } catch (_: LearningSessionUnavailableException) {
        null
    }

    private sealed class Step {
        data class Ready(val session: TodoSession) : Step()
        data class Fail(val error: TodoApiException) : Step()
        data class Login(val ref: String, val session: TodoSession) : Step()
        data object Wait : Step()
    }

}
