package neth.iecal.curbox.utils

import android.content.Context
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppRuleGuardianExtraTimeGrantQuery
import neth.iecal.curbox.domain.apprules.AppRulePackageScopeReader
import neth.iecal.curbox.domain.apprules.GuardianExtraTimeGrantQuery

/** Composes the shared grant query from Curbox's existing multi-process data owners. */
object GuardianExtraTimeGrantQueryFactory {
    fun create(context: Context, dataStore: DataStoreManager): GuardianExtraTimeGrantQuery {
        val appContext = context.applicationContext
        val database = AppDatabase.getInstance(appContext)
        return AppRuleGuardianExtraTimeGrantQuery(
            effectiveSettings = dataStore.settings,
            sessionRepository = RoomCurrentUseDaySessionRepository(
                database.foregroundSessionDao()
            ),
            packageScopeReader = AppRulePackageScopeReader.fromContext(appContext)
        )
    }
}
