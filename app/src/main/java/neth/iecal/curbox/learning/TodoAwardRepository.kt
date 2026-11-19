package neth.iecal.curbox.learning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantBasis
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.GuardianExtraTimeGrantWrite
import neth.iecal.curbox.utils.TodoAwardGrantBasis
import neth.iecal.todo.TodoAwardLookup
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal data class TodoAwardOffer(val basis: TodoAwardGrantBasis, val registration: TodoRegistrationScope)

/** Connects award reports to the existing guardian approval transaction. */
internal class TodoAwardRepository(context: Context, private val settings: DataStoreManager) {
    private val appContext = context.applicationContext
    private val learning = LearningStorage(appContext)

    suspend fun lookup(grant: GuardianExtraTimeGrantBasis): TodoAwardOffer? = withContext(Dispatchers.IO) {
        val before = settings.settings.first()
        val link = before.todoAwardConnection ?: return@withContext null
        if (link.ruleId != grant.ruleId) return@withContext null
        val document = learning.read()
        val scope = TodoRegistrationScope.capture(document, link.childName) ?: return@withContext null
        if (scope.accountId != link.accountId) return@withContext null
        val date = LocalDate.parse(grant.useDayId).format(DateTimeFormatter.BASIC_ISO_DATE)
        val minutes = TodoAwardLookup(LearningComposition.createApi(appContext))
            .minutes(learning.readSession(scope.sessionRef), link.childName, date, scope.subjects)
        if (!scope.isCurrent(learning.read())) return@withContext null
        val latest = settings.settings.first()
        if (latest.todoAwardConnection != link) return@withContext null
        val latestGrant = GuardianExtraTimeGrantBasis.capture(latest, grant.ruleId, System.currentTimeMillis())
        if (latestGrant != grant) return@withContext null
        TodoAwardGrantBasis.capture(latest, grant, minutes)?.let { TodoAwardOffer(it, scope) }
    }

    suspend fun isCurrent(offer: TodoAwardOffer, grant: GuardianExtraTimeGrantBasis): Boolean = withContext(Dispatchers.IO) {
        val current = settings.settings.first()
        grant == GuardianExtraTimeGrantBasis.capture(current, grant.ruleId, System.currentTimeMillis()) &&
            TodoAwardGrantBasis.capture(current, grant, offer.basis.reportedMinutes) == offer.basis &&
            offer.registration.isCurrent(learning.read())
    }

    suspend fun grant(password: String, offer: TodoAwardOffer, manual: Long, todo: Long): GuardianExtraTimeGrantWrite.Result =
        withContext(Dispatchers.IO) {
            if (offer.registration.accountId != offer.basis.connection.accountId ||
                offer.registration.childName != offer.basis.connection.childName) return@withContext GuardianExtraTimeGrantWrite.Result.Rejected
            learning.withRegistration(offer.registration) {
                settings.grantTodoAppRuleTime(password, offer.basis, manual, todo)
            } ?: GuardianExtraTimeGrantWrite.Result.Unavailable
        }
}
