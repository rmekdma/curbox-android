package neth.iecal.todo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Orders effects across service runs and rejects snapshots superseded before publication. */
object OrderedLearningPublisher {
    private val mutex = Mutex()

    suspend fun publish(
        store: LearningStore,
        committed: StoredLearning,
        permission: StateFlow<NotificationPermission>,
        expected: NotificationPermission,
        write: suspend (StoredLearning) -> Unit
    ) = mutex.withLock {
        val current = withContext(Dispatchers.IO) { store.read() }
        if (permission.value != expected || current.monitor.ownerEpoch != committed.monitor.ownerEpoch ||
            current.monitor.controlRevision != committed.monitor.controlRevision || current.revision != committed.revision) return@withLock
        write(current)
    }
}
