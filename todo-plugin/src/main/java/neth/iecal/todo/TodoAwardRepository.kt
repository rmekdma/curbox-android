package neth.iecal.todo

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.curbox.learning.LearningStorage
import neth.iecal.curbox.learning.TodoRegistrationScope
import neth.iecal.plugin.AdditionalTimeOffer
import neth.iecal.plugin.AdditionalTimeProvider
import neth.iecal.plugin.PluginTimeHost
import neth.iecal.plugin.PreparedTimeContribution
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Loads reports and prepares state changes for the host's atomic time transactions. */
class TodoAwardRepository(
    private val host: PluginTimeHost,
    private val storage: LearningStorage,
    private val api: TodoApi,
    private val displayName: String,
    private val onFailure: (Throwable) -> Unit = {}
) : AdditionalTimeProvider {
    private val gson = Gson()
    private fun decode(raw: String?): TodoAwardState = if (raw.isNullOrBlank()) TodoAwardState() else
        requireNotNull(gson.fromJson(raw, TodoAwardState::class.java))

    override fun recordRollover(state: neth.iecal.plugin.PluginTimeState, previouslySettledUseDayId: String?, settledUseDayId: String): String? {
        val data = decode(state.data)
        val link = data.connection ?: return null
        if (state.linkedRuleId != link.ruleId) return null
        val rule = state.rules.singleOrNull { it.id == link.ruleId }
        val eligible = rule?.eligibleForContributions == true && rule.rolloverEnabled
        val updated = TodoSettlement.recordPending(data, previouslySettledUseDayId, settledUseDayId, eligible, rule?.unlockDays ?: emptySet())
        return if (updated == data) null else gson.toJson(updated)
    }

    override suspend fun offer(ruleId: String): AdditionalTimeOffer? = withContext(Dispatchers.IO) {
        val before = host.read()
        val state = decode(before.data)
        val link = state.connection ?: return@withContext null
        if (before.linkedRuleId != ruleId || link.ruleId != ruleId ||
            before.rules.singleOrNull { it.id == ruleId }?.eligibleForContributions != true) return@withContext null
        val scope = TodoRegistrationScope.capture(storage.read(), link.childName) ?: return@withContext null
        if (scope.accountId != link.accountId) return@withContext null
        val date = LocalDate.parse(before.useDayId).format(DateTimeFormatter.BASIC_ISO_DATE)
        val reported = TodoAwardLookup(api).minutes(storage.readSession(scope.sessionRef), link.childName, date, scope.subjects)
        if (!scope.isCurrent(storage.read()) || host.read() != before) return@withContext null
        val basis = TodoAwardBasis.capture(state, ruleId, before.useDayId, reported) ?: return@withContext null
        AdditionalTimeOffer(displayName, basis.availableMinutes, basis.availableMinutes, before,
            PreparedTimeContribution { minutes, commit ->
                val updated = TodoAwardGrantWrite.apply(state, basis, minutes) ?: return@PreparedTimeContribution false
                storage.withRegistration(scope) { commit(gson.toJson(updated)) } == true
            }, isCurrent = { withContext(Dispatchers.IO) { scope.isCurrent(storage.read()) } })
    }

    override suspend fun settleEnded(): Boolean = withContext(Dispatchers.IO) {
        var snapshot = host.read()
        var state = decode(snapshot.data)
        val link = state.connection ?: return@withContext false
        if (snapshot.linkedRuleId != link.ruleId) return@withContext false
        val rule = snapshot.rules.singleOrNull { it.id == link.ruleId }
        if (rule?.eligibleForContributions != true || !rule.rolloverEnabled || !link.autoSettlementEnabled ||
            rule.lastSettledUseDayId != snapshot.useDayId
        ) return@withContext false
        val today = LocalDate.parse(snapshot.useDayId)
        state = TodoSettlement.prunePending(state, today, rule.unlockDays)
        val days = state.pending.map { it.useDayId }
        if (days.isEmpty()) return@withContext false
        val scope = TodoRegistrationScope.capture(storage.read(), link.childName) ?: return@withContext true
        if (scope.accountId != link.accountId) return@withContext true
        for (day in days) {
            if (state.pending.none { it.connection == link && it.useDayId == day }) continue
            val reported = try {
                TodoAwardLookup(api).minutes(storage.readSession(scope.sessionRef), link.childName,
                    LocalDate.parse(day).format(DateTimeFormatter.BASIC_ISO_DATE), scope.subjects)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailure(IllegalStateException("Todo settlement lookup failed"))
                continue
            }
            val (updated, unpaid) = TodoSettlement.complete(state, link, day, reported, today, rule.unlockDays) ?: continue
            val committed = storage.withRegistration(scope) { host.settle(snapshot, day, unpaid, gson.toJson(updated)) } == true
            if (!committed) return@withContext true
            snapshot = host.read()
            state = decode(snapshot.data)
            if (state.connection != link || snapshot.useDayId != today.toString() ||
                snapshot.rules.singleOrNull { it.id == link.ruleId } != rule) return@withContext true
        }
        TodoSettlement.prunePending(state, today, rule.unlockDays).pending.isNotEmpty()
    }
}

/** Converts the old settings fields without changing their ledger or pending entries. */
object TodoAwardLegacyMigration {
    fun onLinkedTimeRevoked(data: String?, currentUseDayId: String): String? {
        if (data.isNullOrBlank()) return data
        val gson = Gson()
        val state = gson.fromJson(data, TodoAwardState::class.java)
        val link = state.connection ?: return data
        val revised = link.copy(connectedUseDayId = currentUseDayId, revision = java.util.UUID.randomUUID().toString())
        return gson.toJson(state.copy(connection = revised))
    }

    val keys = setOf("todoAwardConnection", "todoAwardLedger", "todoPendingSettlements", "todoAwardConnectionRevision")
    fun migrate(fields: Map<String, String>): Pair<String, String?>? {
        if (fields.keys.none { it in keys }) return null
        val gson = Gson()
        fun field(name: String) = fields[name]?.let(JsonParser::parseString)
        val root = com.google.gson.JsonObject().apply {
            field("todoAwardConnection")?.let { add("connection", it) }
            add("ledger", field("todoAwardLedger") ?: gson.toJsonTree(emptyList<Any>()))
            add("pending", field("todoPendingSettlements") ?: gson.toJsonTree(emptyList<Any>()))
        }
        val state = gson.fromJson(root, TodoAwardState::class.java)
        return gson.toJson(state) to state.connection?.ruleId
    }
}
