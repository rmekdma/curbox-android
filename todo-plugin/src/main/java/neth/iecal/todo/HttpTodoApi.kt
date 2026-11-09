package neth.iecal.todo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TodoSession(val accountId: String, val token: String, val email: String? = null) {
    override fun toString(): String = "TodoSession(redacted)"
}

enum class LearningFailure { NETWORK, SERVER, INVALID_DATA, STORAGE, AUTHENTICATION }
class TodoApiException(
    val authenticationRequired: Boolean = false,
    val kind: LearningFailure = if (authenticationRequired) LearningFailure.AUTHENTICATION else LearningFailure.SERVER
) : Exception()

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
        catch (e: TodoApiException) { throw e } catch (e: java.io.IOException) { throw TodoApiException(kind = LearningFailure.NETWORK) } catch (e: Exception) { throw TodoApiException(kind = LearningFailure.INVALID_DATA) }
    }

    override fun catalog(session: TodoSession): List<TodoChild> {
        try {
        val children = linkedMapOf<String, MutableMap<Subject, String>>()
        for ((subject, path) in listOf(Subject.KOREAN to "todohangeul", Subject.MATH to "math", Subject.ENGLISH to "english")) {
            val body = JsonObject().apply { add("user", JsonObject().apply { addProperty("accountId", session.accountId) }) }
            val data = try { post("/v3/$path/user", body, session.token).asJsonArray } catch (e: TodoApiException) { throw e } catch (e: java.io.IOException) { throw TodoApiException(kind = LearningFailure.NETWORK) } catch (e: Exception) { throw TodoApiException(kind = LearningFailure.INVALID_DATA) }
            for (element in data) {
                val user = element.asJsonObject
                val nameElement = user.get("name")
                if (nameElement == null || !nameElement.isJsonPrimitive || !nameElement.asJsonPrimitive.isString) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                val idElement = user.get("userId") ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                if (!idElement.isJsonNull && (!idElement.isJsonPrimitive || !idElement.asJsonPrimitive.isString)) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                val name = nameElement.asString.trim()
                    .takeIf { it.isNotEmpty() } ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                val id = user.get("userId")?.takeUnless { it.isJsonNull }?.asString?.trim()
                val subjects = children.getOrPut(name) { linkedMapOf() }
                if (!id.isNullOrBlank() && id != "null") subjects[subject] = id
            }
        }
        return children.map { TodoChild(it.key, it.value.toMap()) }
        } catch (e: TodoApiException) { throw e } catch (e: java.io.IOException) { throw TodoApiException(kind = LearningFailure.NETWORK) } catch (e: Exception) { throw TodoApiException(kind = LearningFailure.INVALID_DATA) }
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
                val result = envelope.get("result")
                if (result == null || !result.isJsonPrimitive || !result.asJsonPrimitive.isBoolean) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                if (!result.asBoolean) throw TodoApiException()
                envelope.get("data")?.takeUnless { it.isJsonNull } ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
            }
        } catch (e: TodoApiException) { throw e } catch (e: java.io.IOException) { throw TodoApiException(kind = LearningFailure.NETWORK) } catch (e: Exception) { throw TodoApiException(kind = LearningFailure.INVALID_DATA) }
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
                val result = envelope.get("result")
                if (result == null || !result.isJsonPrimitive || !result.asJsonPrimitive.isBoolean) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                if (!result.asBoolean) throw TodoApiException(true)
                val data = envelope.getAsJsonObject("data")
                fun stringField(name: String): String? {
                    val value = data.get(name) ?: return null
                    if (value.isJsonNull) return null
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                    return value.asString.takeIf { it.isNotBlank() }
                }
                val account = stringField("accountId") ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                val token = (stringField("authToken") ?: stringField("sessionId")) ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                TodoSession(account, token)
            }
        } catch (e: TodoApiException) { throw e } catch (e: java.io.IOException) { throw TodoApiException(kind = LearningFailure.NETWORK) } catch (e: Exception) { throw TodoApiException(kind = LearningFailure.INVALID_DATA) }
    }
}





