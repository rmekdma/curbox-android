package neth.iecal.todo

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

private val testDates = Collections.synchronizedMap(WeakHashMap<LearningScreen, AtomicReference<String?>>())

internal fun testLearningScreen(api: TodoApi, store: LearningStore = MemoryLearningStore(), isCurrentDate: ((String) -> Boolean)? = null, now: () -> Long = System::currentTimeMillis): LearningScreen {
    val date = AtomicReference<String?>(null)
    val screen = LearningScreen(api, store, isCurrentDate) {
        date.get()?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() } ?: now()
    }
    testDates[screen] = date
    return screen
}

internal fun LearningScreen.signIn(email: String, password: String, date: String) {
    requireNotNull(testDates[this]).set(date)
    signIn(email, password)
}

internal fun LearningScreen.refresh(date: String, includeFailed: Boolean = false, expectedSessionRef: String? = null) {
    requireNotNull(testDates[this]).set(date)
    refresh(includeFailed, expectedSessionRef)
}
