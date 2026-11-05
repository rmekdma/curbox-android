package neth.iecal.curbox.learning

import neth.iecal.todo.HttpTodoApi
import neth.iecal.todo.LearningScreen

object LearningComposition {
    fun createApi() = HttpTodoApi("https://api.todomath.com")
    fun createScreen(context: android.content.Context) = LearningScreen(createApi(), LearningStorage(context))
}
