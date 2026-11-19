package neth.iecal.curbox.domain.apprules

import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException

internal data class RestIntervalSaved(val startAge: Long, val endAge: Long)
internal data class RestCycleSaved(val used: Long, val rests: List<RestIntervalSaved>)
internal data class RestCycleSnapshot(val wallAt: Long, val cycles: Map<String, RestCycleSaved>)

/** Stores only completed observations, never a prediction of the current foreground state. */
internal interface RestCycleStore {
    fun beginSession(): Long
    fun load(session: Long): RestCycleSnapshot?
    fun save(session: Long, snapshot: RestCycleSnapshot)

    object None : RestCycleStore {
        override fun beginSession() = 0L
        override fun load(session: Long): RestCycleSnapshot? = null
        override fun save(session: Long, snapshot: RestCycleSnapshot) = Unit
    }
}

internal class AtomicRestCycleStore(directory: File) : RestCycleStore {
    private val file = AtomicFile(File(directory, "app_rule_rest_cycles.json"))
    private val gson = Gson()

    override fun beginSession(): Long = synchronized(lock) { ++generation }

    override fun load(session: Long): RestCycleSnapshot? = synchronized(lock) {
        if (session != generation) return@synchronized null
        try {
            file.openRead().bufferedReader().use { gson.fromJson(it, RestCycleSnapshot::class.java) }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun save(session: Long, snapshot: RestCycleSnapshot) = synchronized(lock) {
        if (session != generation) return@synchronized
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        val lock = Any()
        var generation = 0L
    }
}
