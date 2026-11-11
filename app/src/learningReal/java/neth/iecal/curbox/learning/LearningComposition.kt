package neth.iecal.curbox.learning

import neth.iecal.todo.HttpTodoApi
import neth.iecal.todo.LearningScreen

object LearningComposition {
    fun createApi(context: android.content.Context) = HttpTodoApi("https://api.todomath.com")
    fun subjectPackages() = neth.iecal.todo.SubjectPackages()
    fun createScreen(context: android.content.Context) = LearningScreen(createApi(context), LearningStorage(context))
}
