package neth.iecal.curbox.learning

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import neth.iecal.plugin.PluginHost
import neth.iecal.todo.LearningState
import neth.iecal.todo.StoredLearning
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import neth.iecal.todo.MonitorState
import neth.iecal.todo.LearningSessionUnavailableException
import neth.iecal.todo.LearningStore
import neth.iecal.todo.TodoSession
import neth.iecal.todo.acceptsApiResult
import neth.iecal.todo.withManualState
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LearningStorage(host: PluginHost, fake: Boolean) : LearningStore {
    private val stateStore = host.store(if (fake) "learning-fake-state" else "learning-state")
    private val sessionStore = host.store(if (fake) "learning-fake-session" else "learning-session")
    private val alias = if (fake) "curbox_learning_fake_session" else "curbox_learning_session"
    private val gson = GsonBuilder().enableComplexMapKeySerialization().create()

    private fun document(raw: String): StoredLearning {
        if (raw.isEmpty()) return StoredLearning()
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("revision")?.isJsonPrimitive == true && root.get("revision").asJsonPrimitive.isNumber)
        val state = root.get("state")?.takeIf { it.isJsonObject }?.asJsonObject ?: error("Invalid learning state")
        require(state.get("children")?.isJsonArray == true && (state.get("reports")?.isJsonArray == true || state.get("reports")?.isJsonObject == true))
        val ref = root.get("sessionRef")
        require(ref == null || ref.isJsonNull || (ref.isJsonPrimitive && ref.asJsonPrimitive.isString))
        if (!root.has("monitor")) root.add("monitor", gson.toJsonTree(MonitorState()))
        return requireNotNull(gson.fromJson(root, StoredLearning::class.java))
    }

    /** Runs a settings transaction only while this registration and session remain current. */
    suspend fun <T> withRegistration(scope: TodoRegistrationScope, action: suspend () -> T): T? {
        var result: T? = null
        stateStore.updateData { raw ->
            if (scope.isCurrent(document(raw))) result = action()
            raw
        }
        return result
    }

    override val changes = stateStore.data.map { document(it) }
    override fun read(): StoredLearning = runBlocking { document(stateStore.data.first()) }
    override fun claim(): StoredLearning = runBlocking {
        document(stateStore.updateData { raw ->
            val previous = document(raw)
            gson.toJson(previous.copy(revision = Math.addExact(previous.revision, 1)))
        })
    }
    override fun commit(revision: Long, state: LearningState, sessionRef: String?, expectedSessionRef: String?, catalogSuccess: Boolean): Boolean = runBlocking {
        var committed = false
        stateStore.updateData { raw ->
            val previous = document(raw)
            if (previous.revision == revision && previous.acceptsApiResult(expectedSessionRef)) {
                committed = true
                gson.toJson(previous.withManualState(state, sessionRef, catalogSuccess))
            } else raw
        }
        committed
    }
    override fun updateMonitor(change: (StoredLearning) -> MonitorState?): StoredLearning? = runBlocking {
        var updated: StoredLearning? = null
        stateStore.updateData { raw ->
            val previous = document(raw)
            val monitor = change(previous)
            if (monitor == null) raw else previous.copy(monitor = monitor).also { updated = it }.let(gson::toJson)
        }
        updated
    }
    override fun <T> authTransaction(action: (StoredLearning) -> Pair<StoredLearning, T>): T = runBlocking {
        var result: Result<T>? = null
        stateStore.updateData { raw ->
            val current = document(raw)
            val (next, value) = action(current)
            result = Result.success(value)
            if (next == current) raw else gson.toJson(next)
        }
        checkNotNull(result).getOrThrow()
    }

    override fun clearSessions() { runBlocking {
        sessionStore.updateData { "" }
    } }

    override fun readSession(ref: String): TodoSession = runBlocking {
        val raw = sessionStore.data.first()
        val entries = if (raw.isEmpty()) JsonObject() else JsonParser.parseString(raw).asJsonObject
        val bytes = Base64.decode((entries.get(ref) ?: throw LearningSessionUnavailableException()).asString, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(ref, false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        decodeLearningSession(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()).copy(storageRef = ref)
    }
    override fun writeSession(ref: String, session: TodoSession) { runBlocking {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(ref, true))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(gson.toJson(session).encodeToByteArray()), Base64.NO_WRAP)
        sessionStore.updateData { raw ->
            val entries = if (raw.isEmpty()) JsonObject() else JsonParser.parseString(raw).asJsonObject
            entries.addProperty(ref, encoded)
            entries.toString()
        }
    } }

    private fun key(ref: String, create: Boolean): SecretKey {
        val alias = "${alias}_$ref"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw LearningSessionUnavailableException()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}






internal fun decodeLearningSession(raw: String): TodoSession {
    val decoded = JsonParser.parseString(raw).asJsonObject
    val account = decoded.get("accountId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    val token = decoded.get("token")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    val email = decoded.get("email")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    val password = decoded.get("password")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    return TodoSession(requireNotNull(account), requireNotNull(token), email, password)
}
