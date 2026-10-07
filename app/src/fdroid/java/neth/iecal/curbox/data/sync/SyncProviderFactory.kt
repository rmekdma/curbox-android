package neth.iecal.curbox.data.sync

import android.content.Context

@Suppress("UNUSED_PARAMETER") // Keep the factory signature aligned with sync-enabled flavors.
fun createSyncProvider(context: Context): SyncProvider = NoopSyncProvider
