package neth.iecal.todo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TodoSession(val accountId: String, val token: String)

class TodoApiException(val authenticationRequired: Boolean = false) : Exception()

data class TodoChild(val name: String, val userIds: Map<Subject, String>)

interface TodoApi {
    fun signIn(email: String, password: String): TodoSession
    fun catalog(session: TodoSession): List<TodoChild>
    fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport
}

class HttpTodoApi(private val baseUrl: String) : TodoApi {
    override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
        val path = when (subject) { Subject.KOREAN -> "hangeul"; Subject.MATH -> "math"; Subject.ENGLISH -> "english" }
        val body = JsonObject().apply {
            addProperty("userId", userId)
            addProperty("yyyymmdd", date)
            addProperty("languageCode", "ko")
        }
        return try { TodoReportParser.parse(subject, post("/v3/todoschool/report/daily/$path", body, session.token).toString()) }
        catch (e: TodoApiException) { throw e } catch (e: Exception) { throw TodoApiException() }
    }

    override fun catalog(session: TodoSession): List<TodoChild> {
        try {
        val children = linkedMapOf<String, MutableMap<Subject, String>>()
        for ((subject, path) in listOf(Subject.KOREAN to "todohangeul", Subject.MATH to "math", Subject.ENGLISH to "english")) {
            val body = JsonObject().apply { add("user", JsonObject().apply { addProperty("accountId", session.accountId) }) }
            val data = try { post("/v3/$path/user", body, session.token).asJsonArray } catch (e: TodoApiException) { throw e } catch (e: Exception) { throw TodoApiException() }
            for (element in data) {
                val user = element.asJsonObject
                val name = user.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: throw TodoApiException()
                val id = user.get("userId")?.takeUnless { it.isJsonNull }?.asString?.trim()
                val subjects = children.getOrPut(name) { linkedMapOf() }
                if (!id.isNullOrBlank() && id != "null") subjects[subject] = id
            }
        }
        return children.map { TodoChild(it.key, it.value.toMap()) }
        } catch (e: TodoApiException) { throw e } catch (e: Exception) { throw TodoApiException() }
    }

    private fun post(path: String, body: JsonObject, token: String): com.google.gson.JsonElement {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json, text/plain, */*")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TodoApiException(response.code == 401 || response.code == 403)
                val envelope = JsonParser.parseString(response.body?.string()).asJsonObject
                if (envelope.get("result")?.asBoolean != true) throw TodoApiException()
                envelope.get("data")?.takeUnless { it.isJsonNull } ?: throw TodoApiException()
            }
        } catch (e: TodoApiException) { throw e } catch (e: Exception) { throw TodoApiException() }
    }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build()

    override fun signIn(email: String, password: String): TodoSession {
        val body = JsonObject().apply {
            addProperty("accountType", "personal")
            addProperty("getSessionId", true)
            addProperty("product", "todoschool")
            addProperty("packageName", "com.enuma.todoschool")
            addProperty("fromWeb", true)
            addProperty("countryCode", "ko")
            addProperty("email", email)
            addProperty("password", password)
        }
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/v3/account/signin")
            .header("Accept", "application/json, text/plain, */*")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TodoApiException(response.code == 401 || response.code == 403)
                val envelope = JsonParser.parseString(response.body?.string()).asJsonObject
                if (envelope.get("result")?.asBoolean != true) throw TodoApiException(true)
                val data = envelope.getAsJsonObject("data")
                val account = data.get("accountId")?.asString?.takeIf { it.isNotBlank() } ?: throw TodoApiException()
                val token = (data.get("authToken")?.takeUnless { it.isJsonNull }?.asString
                    ?: data.get("sessionId")?.takeUnless { it.isJsonNull }?.asString)
                    ?.takeIf { it.isNotBlank() } ?: throw TodoApiException()
                TodoSession(account, token)
            }
        } catch (e: TodoApiException) { throw e } catch (e: Exception) { throw TodoApiException() }
    }
}




