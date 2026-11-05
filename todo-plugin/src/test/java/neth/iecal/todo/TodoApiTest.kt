package neth.iecal.todo

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TodoApiTest {
    @Test fun `malformed child metadata is an api failure`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"result":true,"data":[false]}"""))
        server.start()
        try {
            assertThrows(TodoApiException::class.java) { HttpTodoApi(server.url("/").toString()).catalog(TodoSession("123", "synthetic")) }
        } finally { server.shutdown() }
    }

    @Test fun `report uses subject route and counts activities`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"result":true,"data":{"contentsHistory":{"daily":[{},{}]},"learningSecondsByCategory":{"study":"61","etc":100}}}"""))
        server.start()
        try {
            val report = HttpTodoApi(server.url("/").toString()).report(TodoSession("123", "synthetic"), Subject.MATH, "child", "20261105")
            val request = server.takeRequest()
            assertEquals("/v3/todoschool/report/daily/math", request.path)
            assertEquals("Bearer synthetic", request.getHeader("Authorization"))
            assertEquals(DailyReport(2, 2), report)
        } finally { server.shutdown() }
    }

    @Test fun `catalog combines subject specific users and rejects failed metadata`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"result":true,"data":[{"name":"Child","userId":"ko"}]}"""))
        server.enqueue(MockResponse().setBody("""{"result":true,"data":[{"name":"Child","userId":"math"}]}"""))
        server.enqueue(MockResponse().setBody("""{"result":false}"""))
        server.start()
        try {
            val api = HttpTodoApi(server.url("/").toString())
            assertThrows(TodoApiException::class.java) { api.catalog(TodoSession("123", "synthetic")) }
        } finally { server.shutdown() }
    }

    @Test fun `sign in sends required fields and accepts session id`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"result":true,"data":{"sessionId":"synthetic","accountId":"123"}}"""))
        server.start()
        try {
            val session = HttpTodoApi(server.url("/").toString()).signIn("sample", "sample")
            val request = server.takeRequest()
            assertEquals("/v3/account/signin", request.path)
            assertTrue(request.body.readUtf8().contains("\"accountType\":\"personal\""))
            assertEquals("synthetic", session.token)
            assertEquals("123", session.accountId)
        } finally { server.shutdown() }
    }
}




class TodoValidationTest {
    @Test fun `numeric child name is rejected`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"result":true,"data":[{"name":123,"userId":"x"}]}"""))
        server.start()
        try {
            assertThrows(TodoApiException::class.java) { HttpTodoApi(server.url("/").toString()).catalog(TodoSession("123", "synthetic")) }
        } finally { server.shutdown() }
    }

    @Test fun `session text does not expose token`() {
        assertFalse(TodoSession("account", "synthetic-secret").toString().contains("synthetic-secret"))
    }
}

class TodoSigninValidationTest {
    @Test fun `numeric account identifier is invalid data`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"result":true,"data":{"accountId":123,"authToken":"sample"}}"""))
        server.start()
        try {
            val failure = assertThrows(TodoApiException::class.java) { HttpTodoApi(server.url("/").toString()).signIn("sample", "sample") }
            assertEquals(LearningFailure.INVALID_DATA, failure.kind)
        } finally { server.shutdown() }
    }
}
