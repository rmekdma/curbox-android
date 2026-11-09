package neth.iecal.curbox.learning

import android.content.Context
import android.system.Os
import java.io.FileOutputStream
import com.google.gson.JsonParser
import neth.iecal.todo.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SyntheticFixture private constructor(val revision: String, val children: List<TodoChild>, private val reports: Map<Triple<String, Subject, String>, Result<DailyReport>>) {
    fun report(user: String, subject: Subject, date: String): DailyReport = reports[Triple(user, subject, date)]?.getOrThrow() ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)

    companion object {
        fun parse(text: String): SyntheticFixture {
            require(text.toByteArray(Charsets.UTF_8).size <= 16384)
            val root = JsonParser.parseString(text).asJsonObject
            require(root.keySet() == setOf("revision", "children", "reports"))
            val revision = root.get("revision").asString
            require(revision.matches(Regex("[A-Za-z0-9_]{1,32}")))
            val names = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            val children = root.getAsJsonArray("children").map { element ->
                val obj = element.asJsonObject
                require(obj.keySet() == setOf("name", "users"))
                val name = obj.get("name").asString
                require(name.matches(Regex("Child[0-9]{1,3}")) && names.add(name))
                val users = obj.getAsJsonObject("users").entrySet().associate { (key, value) ->
                    val id = value.asString
                    require(id.matches(Regex("user[0-9]{1,4}")) && ids.add(id))
                    Subject.valueOf(key) to id
                }
                require(users.isNotEmpty())
                TodoChild(name, users)
            }
            require(children.size <= 10)
            val reports = mutableMapOf<Triple<String, Subject, String>, Result<DailyReport>>()
            val list = root.getAsJsonArray("reports")
            require(list.size() <= 100)
            list.forEach { element ->
                val obj = element.asJsonObject
                val user = obj.get("user").asString
                val subject = Subject.valueOf(obj.get("subject").asString)
                val date = obj.get("date").asString
                require(children.any { it.userIds[subject] == user })
                require(date.matches(Regex("[0-9]{8}")))
                LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
                val value = if (obj.has("failure")) {
                    require(obj.keySet() == setOf("user", "subject", "date", "failure"))
                    Result.failure(TodoApiException(kind = LearningFailure.valueOf(obj.get("failure").asString)))
                } else {
                    require(obj.keySet().containsAll(setOf("user", "subject", "date", "count")))
                    require(obj.keySet().all { it in setOf("user", "subject", "date", "count", "minutes", "seconds") })
                    require(obj.get("count").toString().matches(Regex("[0-9]{1,6}")))
                    val count = obj.get("count").asInt
                    require(count in 0..100000)
                    Result.success(DailyReport(count))
                }
                require(reports.put(Triple(user, subject, date), value) == null)
            }
            return SyntheticFixture(revision, children, reports)
        }
    }
}

object SyntheticFixtureFile {
    private fun file(context: Context) = File(context.filesDir, "synthetic-learning-fixture.json")
    fun read(context: Context): SyntheticFixture? {
        val file = file(context)
        if (!file.exists()) return null
        return try {
            require(file.length() <= 16384)
            SyntheticFixture.parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { throw TodoApiException(kind = LearningFailure.INVALID_DATA) }
    }
    @Synchronized fun write(context: Context, text: String): String {
        val parsed = SyntheticFixture.parse(text)
        val target = file(context)
        val temporary = File(context.filesDir, "synthetic-learning-fixture.tmp")
        try {
            FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // Readers in the other process must see a complete old or new document.
            Os.rename(temporary.path, target.path)
        } finally { temporary.delete() }
        return parsed.revision
    }
    @Synchronized fun reset(context: Context) { file(context).delete() }
}

object LearningComposition {
    fun createApi(context: Context): TodoApi = object : TodoApi {
        override fun signIn(email: String, password: String) = TodoSession("fake", "fake")
        override fun catalog(session: TodoSession) = SyntheticFixtureFile.read(context)?.children ?: listOf(TodoChild("Sample", mapOf(Subject.MATH to "fake")))
        override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = SyntheticFixtureFile.read(context)?.report(userId, subject, date) ?: DailyReport(2)
    }
    fun subjectPackages() = SubjectPackages(math = "neth.iecal.curbox.apitester")
    fun createScreen(context: Context) = LearningScreen(createApi(context), LearningStorage(context))
}
