package neth.iecal.todo

internal fun testLearningScreen(api: TodoApi, store: LearningStore = MemoryLearningStore(), isCurrentDate: (String) -> Boolean = { true }, now: () -> Long = System::currentTimeMillis) = LearningScreen(api, store, isCurrentDate, now)
