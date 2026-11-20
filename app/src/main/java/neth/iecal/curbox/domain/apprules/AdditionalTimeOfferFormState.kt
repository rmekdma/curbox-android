package neth.iecal.curbox.domain.apprules

/** Editable direct award inputs, including the most recently loaded offer limit. */
data class AdditionalTimeOfferFormState private constructor(
    val currentMinutes: Long,
    val manualText: String,
    val offeredText: String,
    val offerLimit: Long,
    val offerEditRevision: Long
) {
    fun editManual(text: String) = copy(manualText = text)
    fun editOffer(text: String) = copy(offeredText = text, offerEditRevision = offerEditRevision + 1)
    fun receiveOffer(requestedRevision: Long, limit: Long, proposed: Long = limit): AdditionalTimeOfferFormState {
        val safeLimit = limit.coerceAtLeast(0)
        return copy(offerLimit = safeLimit, offeredText = if (requestedRevision == offerEditRevision) proposed.coerceIn(0, safeLimit).toString() else offeredText)
    }
    val totalMinutes: Long?
        get() {
            val manual = manualText.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val offered = offeredText.toLongOrNull()?.takeIf { it in 0..offerLimit } ?: return null
            if (manual > Long.MAX_VALUE - offered || currentMinutes > Long.MAX_VALUE - manual - offered) return null
            return currentMinutes + manual + offered
        }
    fun submission(): Pair<Long, Long>? {
        val total = totalMinutes ?: return null
        if (total <= currentMinutes) return null
        return manualText.toLong() to offeredText.toLong()
    }
    companion object {
        fun initial(currentMinutes: Long) = AdditionalTimeOfferFormState(currentMinutes.coerceAtLeast(0), "0", "0", 0, 0)
    }
}
