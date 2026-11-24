package neth.iecal.todo

internal class TodoRequestDiagnostics(
    private val emit: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val id: () -> String = { java.util.UUID.randomUUID().toString() },
    private val enabled: () -> Boolean = { true },
    private val onFailure: (Throwable) -> Unit = {}
) {
    private fun reportFailure(error: Throwable) { try { onFailure(error) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { } }
    fun <T> request(kind: String, subject: Subject?, action: () -> T): T {
        val active = try { enabled() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { reportFailure(e); false }
        if (!active) return action()
        val requestId = id()
        fun log(result: String) { try { emit("${java.time.Instant.ofEpochMilli(now())} $kind $requestId $result" + (subject?.let { " ${it.name}" } ?: "")) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { reportFailure(e) } }
        log("start")
        return try { action().also { log("success") } } catch (e: kotlinx.coroutines.CancellationException) {
            log("cancelled")
            throw e
        } catch (e: Throwable) {
            val reason = when {
                e is TodoApiException && e.authenticationRequired && e.httpStatus != null -> "AUTHENTICATION HTTP_${e.httpStatus}"
                e is TodoApiException && e.httpStatus != null -> "HTTP_${e.httpStatus}"
                e is TodoApiException && e.resultRejected -> "RESULT_FALSE"
                e is TodoApiException -> e.kind.name
                else -> "OTHER"
            }
            log("failure $reason")
            throw e
        }
    }
}

internal class DiagnosticTodoApi(private val delegate: TodoApi, private val diagnostics: TodoRequestDiagnostics, private val kind: String) : TodoApi {
    override fun signIn(email: String, password: String) = delegate.signIn(email, password)
    override fun catalog(session: TodoSession) = diagnostics.request("${kind}_catalog", null) { delegate.catalog(session) }
    override fun report(session: TodoSession, subject: Subject, userId: String, date: String) =
        diagnostics.request(kind, subject) { delegate.report(session, subject, userId, date) }
}
