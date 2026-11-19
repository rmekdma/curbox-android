package neth.iecal.curbox.domain.apprules

/** Editable direct award inputs, including the most recently loaded Todo limit. */
data class TodoAwardFormState private constructor(
    val currentMinutes: Long,
    val manualText: String,
    val todoText: String,
    val todoLimit: Long,
    val todoEditRevision: Long
) {
    fun editManual(text: String) = copy(manualText = text)
    fun editTodo(text: String) = copy(todoText = text, todoEditRevision = todoEditRevision + 1)
    fun receiveOffer(requestedRevision: Long, limit: Long): TodoAwardFormState {
        val safeLimit = limit.coerceAtLeast(0)
        return copy(todoLimit = safeLimit, todoText = if (requestedRevision == todoEditRevision) safeLimit.toString() else todoText)
    }
    val totalMinutes: Long?
        get() {
            val manual = manualText.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val todo = todoText.toLongOrNull()?.takeIf { it in 0..todoLimit } ?: return null
            if (manual > Long.MAX_VALUE - todo || currentMinutes > Long.MAX_VALUE - manual - todo) return null
            return currentMinutes + manual + todo
        }
    fun submission(): Pair<Long, Long>? {
        val total = totalMinutes ?: return null
        if (total <= currentMinutes) return null
        return manualText.toLong() to todoText.toLong()
    }
    companion object {
        fun initial(currentMinutes: Long) = TodoAwardFormState(currentMinutes.coerceAtLeast(0), "0", "0", 0, 0)
    }
}
