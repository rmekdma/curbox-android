package neth.iecal.plugin

import java.io.File
import java.io.RandomAccessFile

/** Cross-process, time-limited storage for opt-in Todo diagnostics. */
class TodoDiagnosticsFile(private val directory: File) {
    private val lockFile get() = File(directory, "todo-diagnostics.lock")
    private val stateFile get() = File(directory, "todo-diagnostics-window")
    private val logFile get() = File(directory, "todo-diagnostics.log")

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        directory.mkdirs()
        RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use { block() } }
    }

    fun activate(start: Long, end: Long) = locked {
        require(end > start)
        stateFile.writeText("$start $end")
        logFile.appendText("window start=${java.time.Instant.ofEpochMilli(start)} end=${java.time.Instant.ofEpochMilli(end)}\n")
    }
    fun enabled(now: Long): Boolean = locked {
        val parts = if (stateFile.exists()) stateFile.readText().split(' ') else emptyList()
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        start != null && end != null && now >= start && now < end
    }
    fun append(record: String) = locked { if (stateFile.exists()) logFile.appendText(record + "\n") }
    fun read(): String = locked { if (logFile.exists()) logFile.readText() else "" }
    fun clear() = locked { stateFile.delete(); logFile.delete(); Unit }

    private companion object { val processLock = Any() }
}
