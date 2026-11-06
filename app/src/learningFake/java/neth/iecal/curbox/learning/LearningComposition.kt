package neth.iecal.curbox.learning

import neth.iecal.todo.*

object LearningComposition {
    fun createApi(context: android.content.Context): TodoApi = object : TodoApi {
        override fun signIn(email: String, password: String) = TodoSession("fake", "fake")
        override fun catalog(session: TodoSession) = listOf(TodoChild("Sample", mapOf(Subject.MATH to "fake")))
        override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(2, 4)
    }
    fun subjectPackages() = neth.iecal.todo.SubjectPackages()
    fun createScreen(context: android.content.Context) = LearningScreen(createApi(context), LearningStorage(context))
}
