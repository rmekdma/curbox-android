package neth.iecal.curbox.utils

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.plugins.PluginMigrationException
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class PluginMigrationSafetyTest {
    @Test fun invalidLegacyPluginDataDoesNotReturnDefaultSettings() = runBlocking {
        val serializer = GsonSerializer(Gson(), Settings::class.java, Settings()) { _, _ ->
            throw PluginMigrationException(IllegalArgumentException("Invalid plugin state"))
        }
        val raw = """{}"""
        try {
            serializer.readFrom(ByteArrayInputStream(raw.toByteArray()))
            fail("A failed plugin migration must not replace settings with defaults")
        } catch (_: PluginMigrationException) {
            // The datastore can retry the original file after the migration is fixed.
        }
    }
}
