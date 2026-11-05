package neth.iecal.curbox.learning

import neth.iecal.todo.HttpTodoApi
import neth.iecal.todo.LearningScreen

object LearningComposition {
    fun createScreen(context: android.content.Context) = LearningScreen(HttpTodoApi("https://api.todomath.com"), LearningStorage(context))
}
