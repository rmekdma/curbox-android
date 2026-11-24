package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test

class TodoRequestDiagnosticsTest {
    @Test fun `logging failure is reported without changing request outcome`() {
        val failures = mutableListOf<Throwable>()
        val diagnostics = TodoRequestDiagnostics({ throw java.io.IOException("disk") }, { 123L }, { "request" }, { true }, { failures.add(it) })
        assertEquals(42, diagnostics.request("automatic", Subject.MATH) { 42 })
        assertEquals(2, failures.size)
    }
    @Test fun `clearing diagnostics prevents an in flight completion from recreating the log`() {
        val dir = java.nio.file.Files.createTempDirectory("todo-diagnostics").toFile()
        try {
            val window = neth.iecal.plugin.TodoDiagnosticsFile(dir)
            window.activate(1000, 5000)
            window.append("start")
            window.clear()
            window.append("completion")
            assertEquals("", window.read())
            assertFalse(window.enabled(2000))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `HTTP success with rejected result has its own failure type`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"result":false,"private":"do-not-log"}"""))
        server.start()
        try {
            val records = mutableListOf<String>()
            val api = DiagnosticTodoApi(HttpTodoApi(server.url("/").toString()), TodoRequestDiagnostics({ records.add(it) }, { 123L }, { "request" }), "settlement")
            assertThrows(TodoApiException::class.java) { api.report(TodoSession("private-account", "secret-token"), Subject.ENGLISH, "private-child", "20261125") }
            assertEquals(listOf("1970-01-01T00:00:00.123Z settlement request start ENGLISH", "1970-01-01T00:00:00.123Z settlement request failure RESULT_FALSE ENGLISH"), records)
        } finally { server.shutdown() }
    }
    @Test fun `HTTP authentication rejection is distinct from other HTTP failures`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
        server.start()
        try {
            val records = mutableListOf<String>()
            val api = DiagnosticTodoApi(HttpTodoApi(server.url("/").toString()), TodoRequestDiagnostics({ records.add(it) }, { 123L }, { "request" }), "automatic")
            assertThrows(TodoApiException::class.java) { api.report(TodoSession("private-account", "secret-token"), Subject.MATH, "private-child", "20261125") }
            assertEquals(listOf("1970-01-01T00:00:00.123Z automatic request start MATH", "1970-01-01T00:00:00.123Z automatic request failure AUTHENTICATION HTTP_401 MATH"), records)
        } finally { server.shutdown() }
    }
    @Test fun `settlement catalog failure is recorded before any report is requested`() {
        val records = mutableListOf<String>()
        val diagnostics = TodoRequestDiagnostics({ records.add(it) }, { 456L }, { "request" })
        val api = DiagnosticTodoApi(object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession): List<TodoChild> = throw TodoApiException(kind = LearningFailure.NETWORK)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport = error("unused")
        }, diagnostics, "settlement")
        assertThrows(TodoApiException::class.java) { TodoAwardLookup(api).minutes(TodoSession("account", "token"), "child", "20261125") }
        assertEquals(listOf("1970-01-01T00:00:00.456Z settlement_catalog request start", "1970-01-01T00:00:00.456Z settlement_catalog request failure NETWORK"), records)
    }
    @Test fun `requests outside the diagnostic window are not recorded`() {
        val records = mutableListOf<String>()
        val diagnostics = TodoRequestDiagnostics({ records.add(it) }, { 123L }, { "request" }, { false })
        assertEquals(42, diagnostics.request("automatic", Subject.MATH) { 42 })
        assertTrue(records.isEmpty())
    }
    @Test fun `diagnostic window records its bounds even when no request starts`() {
        val dir = java.nio.file.Files.createTempDirectory("todo-diagnostics").toFile()
        try {
            val window = neth.iecal.plugin.TodoDiagnosticsFile(dir)
            window.activate(1000, 5000)
            assertFalse(window.enabled(999))
            assertTrue(window.enabled(1000))
            assertFalse(window.enabled(5000))
            assertEquals("window start=1970-01-01T00:00:01Z end=1970-01-01T00:00:05Z\n", window.read())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `settlement failure records HTTP status without sensitive details`() {
        val records = mutableListOf<String>()
        val diagnostics = TodoRequestDiagnostics({ records.add(it) }, { 456L }, { "request" })
        val api = DiagnosticTodoApi(object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession) = emptyList<TodoChild>()
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport =
                throw TodoApiException(httpStatus = 503)
        }, diagnostics, "settlement")
        assertThrows(TodoApiException::class.java) { api.report(TodoSession("private-account", "secret-token"), Subject.ENGLISH, "private-child", "20261125") }
        assertEquals(listOf("1970-01-01T00:00:00.456Z settlement request start ENGLISH", "1970-01-01T00:00:00.456Z settlement request failure HTTP_503 ENGLISH"), records)
    }

    @Test fun `automatic request logs start and success with matching identifier`() {
        val records = mutableListOf<String>()
        val diagnostics = TodoRequestDiagnostics({ records.add(it) }, { 123L }, { "request" })
        val api = DiagnosticTodoApi(object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession) = emptyList<TodoChild>()
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(2)
        }, diagnostics, "automatic")
        assertEquals(DailyReport(2), api.report(TodoSession("account", "token"), Subject.MATH, "child-id", "20261125"))
        assertEquals(listOf("1970-01-01T00:00:00.123Z automatic request start MATH", "1970-01-01T00:00:00.123Z automatic request success MATH"), records)
    }
}
