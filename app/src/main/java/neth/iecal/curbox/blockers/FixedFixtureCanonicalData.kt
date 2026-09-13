package neth.iecal.curbox.blockers

import com.google.gson.stream.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class AttemptLedgerRow(
    val runId: String,
    val attemptOrdinal: Int,
    val sampleOrdinal: Int?,
    val phase: String,
    val fixtureLabel: String,
    val sourceOrderIdentity: String?,
    val observationKind: String?,
    val lifecycleGeneration: Long?,
    val acceptedRuntimeRevision: Long?,
    val callbackExitKind: String?,
    val packageDecisionCount: Int?,
    val decision: String?,
    val denyingRuleCount: Int?,
    val commitStatus: String?,
    val publicationStatus: String?,
    val callbackStartNs: Long,
    val callbackReturnNs: Long?,
    val selectedEndNs: Long?,
    val quiescenceStartNs: Long?,
    val quiescenceEndNs: Long?,
    val callbackDurationNs: Long?,
    val selectedDurationNs: Long?,
    val selectedEndMinusCallbackReturnNs: Long?,
    val quiescenceDurationNs: Long?,
    val callbackReturnPresent: Boolean,
    val selectedEndPresent: Boolean,
    val terminalState: String,
    val exclusionReason: String?
)

data class FixedFixtureMetadata(
    val runId: String,
    val startedAtUtc: String,
    val endedAtUtc: String,
    val rawDeviceSerial: String,
    val pseudonymousDeviceId: String,
    val sourceCommit: String,
    val harnessCommit: String,
    val protocolRevision: String,
    val appVersion: String,
    val applicationId: String,
    val variant: String,
    val targetApkSha256: String,
    val instrumentationApkSha256: String,
    val buildFingerprint: String,
    val deviceModel: String,
    val androidVersion: String,
    val apiLevel: Int,
    val boundary: String,
    val observationDeadlineNs: Long,
    val recoveryDeadlineNs: Long,
    val exclusionCap: Int,
    val populationOption: String,
    val clockSource: String,
    val stimulusOrder: String,
    val lifecycleGeneration: Long,
    val rootWindowConditions: String,
    val attemptTotals: Map<String, Int>,
    val terminalTotals: Map<String, Int>,
    val exclusionTotals: Map<String, Int>
)

data class EvidenceManifest(
    val manifestSchemaVersion: String,
    val canonicalizationVersion: String,
    val runId: String,
    val pseudonymousDeviceId: String,
    val sourceCommit: String,
    val harnessCommit: String,
    val protocolRevision: String,
    val appVersion: String,
    val applicationId: String,
    val variant: String,
    val targetApkFile: String,
    val targetApkBytes: Long,
    val targetApkSha256: String,
    val instrumentationApkFile: String,
    val instrumentationApkBytes: Long,
    val instrumentationApkSha256: String,
    val samplesJsonlBytes: Long,
    val samplesJsonlSha256: String,
    val metadataJsonBytes: Long,
    val metadataJsonSha256: String,
    val deviceStagingDeleted: Boolean,
    val evidenceCommitParent: String,
    val notes: String
)

data class DeviceDigests(
    val samplesJsonlBytes: Long,
    val samplesJsonlSha256: String,
    val metadataJsonBytes: Long,
    val metadataJsonSha256: String
)

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun serializeCanonicalSampleRow(row: AttemptLedgerRow): String {
    val baos = ByteArrayOutputStream()
    val writer = JsonWriter(OutputStreamWriter(baos, StandardCharsets.UTF_8))
    writer.setIndent("")
    writer.beginObject()
    writer.name("runId").value(row.runId)
    writer.name("attemptOrdinal").value(row.attemptOrdinal)
    if (row.sampleOrdinal != null) writer.name("sampleOrdinal").value(row.sampleOrdinal) else writer.name("sampleOrdinal").nullValue()
    writer.name("phase").value(row.phase)
    writer.name("fixtureLabel").value(row.fixtureLabel)
    if (row.sourceOrderIdentity != null) writer.name("sourceOrderIdentity").value(row.sourceOrderIdentity) else writer.name("sourceOrderIdentity").nullValue()
    if (row.observationKind != null) writer.name("observationKind").value(row.observationKind) else writer.name("observationKind").nullValue()
    if (row.lifecycleGeneration != null) writer.name("lifecycleGeneration").value(row.lifecycleGeneration) else writer.name("lifecycleGeneration").nullValue()
    if (row.acceptedRuntimeRevision != null) writer.name("acceptedRuntimeRevision").value(row.acceptedRuntimeRevision) else writer.name("acceptedRuntimeRevision").nullValue()
    if (row.callbackExitKind != null) writer.name("callbackExitKind").value(row.callbackExitKind) else writer.name("callbackExitKind").nullValue()
    if (row.packageDecisionCount != null) writer.name("packageDecisionCount").value(row.packageDecisionCount) else writer.name("packageDecisionCount").nullValue()
    if (row.decision != null) writer.name("decision").value(row.decision) else writer.name("decision").nullValue()
    if (row.denyingRuleCount != null) writer.name("denyingRuleCount").value(row.denyingRuleCount) else writer.name("denyingRuleCount").nullValue()
    if (row.commitStatus != null) writer.name("commitStatus").value(row.commitStatus) else writer.name("commitStatus").nullValue()
    if (row.publicationStatus != null) writer.name("publicationStatus").value(row.publicationStatus) else writer.name("publicationStatus").nullValue()
    writer.name("callbackStartNs").value(row.callbackStartNs)
    if (row.callbackReturnNs != null) writer.name("callbackReturnNs").value(row.callbackReturnNs) else writer.name("callbackReturnNs").nullValue()
    if (row.selectedEndNs != null) writer.name("selectedEndNs").value(row.selectedEndNs) else writer.name("selectedEndNs").nullValue()
    if (row.quiescenceStartNs != null) writer.name("quiescenceStartNs").value(row.quiescenceStartNs) else writer.name("quiescenceStartNs").nullValue()
    if (row.quiescenceEndNs != null) writer.name("quiescenceEndNs").value(row.quiescenceEndNs) else writer.name("quiescenceEndNs").nullValue()
    if (row.callbackDurationNs != null) writer.name("callbackDurationNs").value(row.callbackDurationNs) else writer.name("callbackDurationNs").nullValue()
    if (row.selectedDurationNs != null) writer.name("selectedDurationNs").value(row.selectedDurationNs) else writer.name("selectedDurationNs").nullValue()
    if (row.selectedEndMinusCallbackReturnNs != null) writer.name("selectedEndMinusCallbackReturnNs").value(row.selectedEndMinusCallbackReturnNs) else writer.name("selectedEndMinusCallbackReturnNs").nullValue()
    if (row.quiescenceDurationNs != null) writer.name("quiescenceDurationNs").value(row.quiescenceDurationNs) else writer.name("quiescenceDurationNs").nullValue()
    writer.name("callbackReturnPresent").value(row.callbackReturnPresent)
    writer.name("selectedEndPresent").value(row.selectedEndPresent)
    writer.name("terminalState").value(row.terminalState)
    if (row.exclusionReason != null) writer.name("exclusionReason").value(row.exclusionReason) else writer.name("exclusionReason").nullValue()
    writer.endObject()
    writer.flush()
    baos.write('\n'.code)
    return baos.toString(StandardCharsets.UTF_8.name())
}

fun serializeCanonicalMetadata(metadata: FixedFixtureMetadata): ByteArray {
    val baos = ByteArrayOutputStream()
    val writer = JsonWriter(OutputStreamWriter(baos, StandardCharsets.UTF_8))
    writer.setIndent("")
    writer.beginObject()
    writer.name("runId").value(metadata.runId)
    writer.name("startedAtUtc").value(metadata.startedAtUtc)
    writer.name("endedAtUtc").value(metadata.endedAtUtc)
    writer.name("rawDeviceSerial").value(metadata.rawDeviceSerial)
    writer.name("pseudonymousDeviceId").value(metadata.pseudonymousDeviceId)
    writer.name("sourceCommit").value(metadata.sourceCommit)
    writer.name("harnessCommit").value(metadata.harnessCommit)
    writer.name("protocolRevision").value(metadata.protocolRevision)
    writer.name("appVersion").value(metadata.appVersion)
    writer.name("applicationId").value(metadata.applicationId)
    writer.name("variant").value(metadata.variant)
    writer.name("targetApkSha256").value(metadata.targetApkSha256)
    writer.name("instrumentationApkSha256").value(metadata.instrumentationApkSha256)
    writer.name("buildFingerprint").value(metadata.buildFingerprint)
    writer.name("deviceModel").value(metadata.deviceModel)
    writer.name("androidVersion").value(metadata.androidVersion)
    writer.name("apiLevel").value(metadata.apiLevel)
    writer.name("boundary").value(metadata.boundary)
    writer.name("observationDeadlineNs").value(metadata.observationDeadlineNs)
    writer.name("recoveryDeadlineNs").value(metadata.recoveryDeadlineNs)
    writer.name("exclusionCap").value(metadata.exclusionCap)
    writer.name("populationOption").value(metadata.populationOption)
    writer.name("clockSource").value(metadata.clockSource)
    writer.name("stimulusOrder").value(metadata.stimulusOrder)
    writer.name("lifecycleGeneration").value(metadata.lifecycleGeneration)
    writer.name("rootWindowConditions").value(metadata.rootWindowConditions)
    writer.name("attemptTotals")
    writer.beginObject()
    for ((k, v) in metadata.attemptTotals) {
        writer.name(k).value(v)
    }
    writer.endObject()
    writer.name("terminalTotals")
    writer.beginObject()
    for ((k, v) in metadata.terminalTotals) {
        writer.name(k).value(v)
    }
    writer.endObject()
    writer.name("exclusionTotals")
    writer.beginObject()
    for ((k, v) in metadata.exclusionTotals) {
        writer.name(k).value(v)
    }
    writer.endObject()
    writer.endObject()
    writer.flush()
    baos.write('\n'.code)
    return baos.toByteArray()
}

fun serializeCanonicalDeviceDigests(digests: DeviceDigests): ByteArray {
    val baos = ByteArrayOutputStream()
    val writer = JsonWriter(OutputStreamWriter(baos, StandardCharsets.UTF_8))
    writer.setIndent("")
    writer.beginObject()
    writer.name("samplesJsonlBytes").value(digests.samplesJsonlBytes)
    writer.name("samplesJsonlSha256").value(digests.samplesJsonlSha256)
    writer.name("metadataJsonBytes").value(digests.metadataJsonBytes)
    writer.name("metadataJsonSha256").value(digests.metadataJsonSha256)
    writer.endObject()
    writer.flush()
    baos.write('\n'.code)
    return baos.toByteArray()
}

fun serializeCanonicalEvidenceManifest(manifest: EvidenceManifest): ByteArray {
    val baos = ByteArrayOutputStream()
    val writer = JsonWriter(OutputStreamWriter(baos, StandardCharsets.UTF_8))
    writer.setIndent("")
    writer.beginObject()
    writer.name("manifestSchemaVersion").value(manifest.manifestSchemaVersion)
    writer.name("canonicalizationVersion").value(manifest.canonicalizationVersion)
    writer.name("runId").value(manifest.runId)
    writer.name("pseudonymousDeviceId").value(manifest.pseudonymousDeviceId)
    writer.name("sourceCommit").value(manifest.sourceCommit)
    writer.name("harnessCommit").value(manifest.harnessCommit)
    writer.name("protocolRevision").value(manifest.protocolRevision)
    writer.name("appVersion").value(manifest.appVersion)
    writer.name("applicationId").value(manifest.applicationId)
    writer.name("variant").value(manifest.variant)
    writer.name("targetApkFile").value(manifest.targetApkFile)
    writer.name("targetApkBytes").value(manifest.targetApkBytes)
    writer.name("targetApkSha256").value(manifest.targetApkSha256)
    writer.name("instrumentationApkFile").value(manifest.instrumentationApkFile)
    writer.name("instrumentationApkBytes").value(manifest.instrumentationApkBytes)
    writer.name("instrumentationApkSha256").value(manifest.instrumentationApkSha256)
    writer.name("samplesJsonlBytes").value(manifest.samplesJsonlBytes)
    writer.name("samplesJsonlSha256").value(manifest.samplesJsonlSha256)
    writer.name("metadataJsonBytes").value(manifest.metadataJsonBytes)
    writer.name("metadataJsonSha256").value(manifest.metadataJsonSha256)
    writer.name("deviceStagingDeleted").value(manifest.deviceStagingDeleted)
    writer.name("evidenceCommitParent").value(manifest.evidenceCommitParent)
    writer.name("notes").value(manifest.notes)
    writer.endObject()
    writer.flush()
    baos.write('\n'.code)
    return baos.toByteArray()
}
