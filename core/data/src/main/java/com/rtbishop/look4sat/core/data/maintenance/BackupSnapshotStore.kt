package com.rtbishop.look4sat.core.data.maintenance

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.rtbishop.look4sat.core.data.audio.RadioAudioCredentials
import com.rtbishop.look4sat.core.data.audio.RadioAudioSecretStore
import com.rtbishop.look4sat.core.data.database.Look4SatDb
import com.rtbishop.look4sat.core.data.database.QsoDatabase
import com.rtbishop.look4sat.core.data.database.entity.SatEntry
import com.rtbishop.look4sat.core.data.database.entity.SatRadio
import com.rtbishop.look4sat.core.data.lotw.AndroidLoTWStorage
import com.rtbishop.look4sat.core.data.lotw.LoTWStorage
import com.rtbishop.look4sat.core.data.repository.stableQsoKey
import com.rtbishop.look4sat.core.data.repository.toDomain
import com.rtbishop.look4sat.core.data.repository.toEntity
import com.rtbishop.look4sat.core.domain.logbook.*
import com.rtbishop.look4sat.core.domain.maintenance.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
internal data class StoredPreference(val type: String, val values: List<String>) {
    fun value(): Any = when (type) {
        "string" -> values.single()
        "set" -> values.toSet()
        "boolean" -> values.single().toBooleanStrict()
        "int" -> values.single().toInt()
        "long" -> values.single().toLong()
        "float" -> values.single().toFloat().also { require(it.isFinite()) }
        else -> error("Unsupported preference type")
    }
    companion object {
        fun from(value: Any): StoredPreference = when (value) {
            is String -> StoredPreference("string", listOf(value))
            is Set<*> -> StoredPreference("set", value.map { it as String }.sorted())
            is Boolean -> StoredPreference("boolean", listOf(value.toString()))
            is Int -> StoredPreference("int", listOf(value.toString()))
            is Long -> StoredPreference("long", listOf(value.toString()))
            is Float -> StoredPreference("float", listOf(value.toString()))
            else -> error("Unsupported preference value")
        }
    }
}

@Serializable
internal data class AudioLogin(val username: String, val password: String)

@Serializable
internal data class BackupSnapshot(
    val preferences: Map<String, StoredPreference> = emptyMap(),
    val entries: List<SatEntry> = emptyList(),
    val radios: List<SatRadio> = emptyList(),
    val records: List<QsoRecord> = emptyList(),
    val lotw: Map<String, String> = emptyMap(),
    val audio: Map<String, AudioLogin> = emptyMap(),
    val astronomyUrl: String = "",
    val astronomyGeneration: String? = null,
    val schedule: Map<String, StoredPreference> = emptyMap()
)

internal fun preferenceCategory(key: String): BackupCategory = when {
    key.contains("password", true) || key.contains("apikey", true) || key.startsWith("wavelogUpload") ||
        key in setOf("tiandituKey", "lotwCallsign", "wavelogUploadUrl", "wavelogConfirmationToken") -> BackupCategory.CREDENTIALS
    key.startsWith("type") || key in setOf("selectedIds", "selectedTypes", "numberOfRadios",
        "numberOfSatellites", "updateTimestamp", "databaseContentVersion", "favoriteTransponders") -> BackupCategory.SATELLITES
    key.startsWith("lotwLast") || key.startsWith("wavelogLast") || key.startsWith("wavelogSync") ||
        key in setOf("workedGrids", "workedGridQsos", "roamedGrids", "markedGridStations",
            "waipaManualProvinceOverrides", "vuccSelectedStationIds", "wavelogStations", "logbookSourceLock") -> BackupCategory.LOGBOOK
    else -> BackupCategory.SETTINGS
}

private fun SharedPreferences.namedBackupValues(): Map<String, Any?> {
    // SharedPreferences permits null keys. An older RC-format migration wrote its flag
    // before its key was initialized; it has no named setting to export or replace.
    val stored: Map<String?, Any?> = all
    return buildMap {
        stored.forEach { (key, value) -> if (key != null) put(key, value) }
        if (size != stored.size) Log.w("Look4SatBackup", "Ignoring unnamed legacy preference; named settings preserved")
    }
}

/** Merge identities as the logbook does, without dropping local/raw fields through ADIF. */
internal fun mergeBackupRecords(local: List<QsoRecord>, incoming: List<QsoRecord>): List<QsoRecord> {
    val result = local.toMutableList()
    val byKey = local.mapIndexed { index, record -> stableQsoKey(record) to index }.toMap().toMutableMap()
    incoming.forEach { remote ->
        val key = stableQsoKey(remote)
        val index = byKey[key] ?: result.indices.filter {
            sameConfirmedContact(result[it], remote) && sameContactIdentity(result[it], remote)
        }.singleOrNull()
        if (index == null) {
            byKey[key] = result.size
            result += remote.copy(id = 0)
        } else {
            val previous = result[index]
            val primary = if (contentScore(remote) > contentScore(previous)) remote else previous
            val secondary = if (primary === remote) previous else remote
            result[index] = primary.withConfirmation(secondary).copy(
                id = previous.id,
                lotwUploaded = previous.lotwUploaded || remote.lotwUploaded,
                lotwReceived = previous.lotwReceived || remote.lotwReceived,
                wavelogUploaded = previous.wavelogUploaded || remote.wavelogUploaded,
                wavelogStation = previous.wavelogStation.ifBlank { remote.wavelogStation },
                rawMessages = (previous.rawMessages + remote.rawMessages).distinct(),
                messageEvents = (previous.messageEvents + remote.messageEvents).distinct(),
                theirFullGrids = (previous.theirFullGrids + remote.theirFullGrids).distinct(),
                myFullGrids = (previous.myFullGrids + remote.myFullGrids).distinct(),
                vuccGrids = (previous.vuccGrids + remote.vuccGrids).distinct(),
                theirVuccGrids = (previous.theirVuccGrids + remote.theirVuccGrids).distinct()
            )
            byKey[key] = index
        }
    }
    return result
}

internal class BackupSnapshotStore(
    private val context: Context,
    private val satellites: Look4SatDb,
    private val logs: QsoDatabase,
    private val preferences: SharedPreferences,
    internal val secretStorage: LoTWStorage = AndroidLoTWStorage(context),
    private val audio: RadioAudioSecretStore = RadioAudioCredentials(context)
) {
    internal fun contextForRecovery() = context
    private val lotw get() = secretStorage
    private val astronomy = context.getSharedPreferences("astronomy", Context.MODE_PRIVATE)
    private val schedule = context.getSharedPreferences("pass_schedule_preferences", Context.MODE_PRIVATE)
    val astronomyRoot = File(context.filesDir, "astronomy")
    val recordingRoots: Map<String, File> = listOf("Recordings", "N76").associateWith {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, it)
    }
    val journal = File(context.noBackupFilesDir, "backup-restore-journal")

    fun snapshot(categories: Set<BackupCategory>): BackupSnapshot = synchronized(DataMaintenance.preferencesLock) {
        val entries = if (BackupCategory.SATELLITES in categories) backupStep("satellites") {
            satellites.look4SatDao().snapshotEntries()
        } else emptyList()
        val radios = if (BackupCategory.SATELLITES in categories) backupStep("transponders") {
            satellites.look4SatDao().snapshotRadios()
        } else emptyList()
        val records = if (BackupCategory.LOGBOOK in categories) backupStep("logbook") {
            logs.qsoDao().snapshotRecords().map { it.toDomain() }
        } else emptyList()
        val lotwNames = buildList {
            if (BackupCategory.LOGBOOK in categories) addAll(listOf("station", "receipts"))
            if (BackupCategory.CREDENTIALS in categories) add("certificate")
        }
        BackupSnapshot(
            backupStep("preferences") {
                preferences.namedBackupValues().filterKeys { preferenceCategory(it) in categories }
                    .mapValues { StoredPreference.from(checkNotNull(it.value)) }
            },
            entries, radios, records,
            backupStep("lotw") {
                lotwNames.mapNotNull { name -> lotw.read(name)?.let { bytes ->
                    try { name to Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }
                } }.toMap()
            },
            if (BackupCategory.CREDENTIALS in categories) backupStep("audio-credentials") {
                audio.snapshot().mapValues { AudioLogin(it.value.first, it.value.second) }
            } else emptyMap(),
            if (BackupCategory.SETTINGS in categories) astronomy.getString("downloadUrl", "").orEmpty() else "",
            if (BackupCategory.ASTRONOMY in categories) astronomy.getString("activeDirectory", null) else null,
            if (BackupCategory.SETTINGS in categories) backupStep("schedule") {
                schedule.namedBackupValues().filterKeys { it in scheduleKeys }
                    .mapValues { StoredPreference.from(checkNotNull(it.value)) }
            } else emptyMap()
        )
    }

    fun validate(snapshot: BackupSnapshot, categories: Set<BackupCategory>) {
        snapshot.preferences.forEach { (key, value) ->
            require(preferenceCategory(key) in categories)
            val restored = value.value()
            preferences.all[key]?.let { require(StoredPreference.from(it).type == value.type) { "Preference type mismatch: $key" } }
            require(key.length <= 1024 && (restored !is String || restored.length <= 16 * 1024 * 1024))
        }
        require(snapshot.entries.map { it.catnum }.distinct().size == snapshot.entries.size)
        require(snapshot.radios.map { it.uuid }.distinct().size == snapshot.radios.size)
        snapshot.entries.forEach {
            require(it.catnum > 0 && it.epoch.isFinite() && it.meanmo.isFinite() && it.meanmo > 0 &&
                it.eccn in 0.0..<1.0 && listOf(it.incl, it.raan, it.argper, it.meanan, it.bstar, it.ndot).all(Double::isFinite))
        }
        require(snapshot.lotw.keys.all { it in setOf("certificate", "station", "receipts") })
        require(BackupCategory.CREDENTIALS in categories || snapshot.audio.isEmpty() && "certificate" !in snapshot.lotw)
        require(BackupCategory.SATELLITES in categories || snapshot.entries.isEmpty() && snapshot.radios.isEmpty())
        require(BackupCategory.LOGBOOK in categories || snapshot.records.isEmpty())
        val ids = snapshot.records.map { it.id }.filter { it != 0L }
        require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Duplicate or invalid logbook identifiers" }
        require(BackupCategory.LOGBOOK in categories || snapshot.lotw.keys.none { it == "station" || it == "receipts" })
        snapshot.lotw.values.forEach { Base64.decode(it, Base64.NO_WRAP).fill(0) }
        snapshot.audio.forEach { (identity, credentials) ->
            require(identity.isNotBlank())
            com.rtbishop.look4sat.core.data.audio.IcomLanProtocol.credential(credentials.username)
            com.rtbishop.look4sat.core.data.audio.IcomLanProtocol.credential(credentials.password)
        }
        snapshot.records.forEach { require(it.startUtcMillis >= 0 && it.theirCallsign.length <= 256) }
        snapshot.astronomyGeneration?.let { require(it.matches(Regex("generation-[a-f0-9-]+"))) }
        require(BackupCategory.SETTINGS in categories || snapshot.schedule.isEmpty())
        snapshot.schedule.forEach { (key, value) ->
            require(key in scheduleKeys)
            val expected = when (key) {
                "satellite_ids", "reminder_minutes_set" -> "set"
                "minimum_elevation_value" -> "float"
                "notify_at_start", "notify_before" -> "boolean"
                else -> "int"
            }
            require(value.type == expected) { "Invalid schedule setting: $key" }
            value.value()
        }
    }

    fun counts(snapshot: BackupSnapshot, categories: Set<BackupCategory>) = categories.associateWith {
        when (it) {
            BackupCategory.SETTINGS -> (snapshot.preferences.keys.count { preferenceCategory(it) == BackupCategory.SETTINGS } +
                snapshot.schedule.size).toLong()
            BackupCategory.SATELLITES -> (snapshot.entries.size + snapshot.radios.size).toLong()
            BackupCategory.LOGBOOK -> snapshot.records.size.toLong()
            BackupCategory.CREDENTIALS -> (snapshot.audio.size + snapshot.lotw.keys.count { it == "certificate" } +
                snapshot.preferences.keys.count { preferenceCategory(it) == BackupCategory.CREDENTIALS }).toLong()
            else -> 0L
        }
    }

    fun apply(snapshot: BackupSnapshot, categories: Set<BackupCategory>, mode: RestoreMode, rollback: Boolean = false) {
        synchronized(DataMaintenance.preferencesLock) {
            if (BackupCategory.SATELLITES in categories) satellites.runInTransaction {
                val dao = satellites.look4SatDao()
                val incomingEntries = if (mode == RestoreMode.MERGE) {
                    val local = dao.snapshotEntries().associateBy { it.catnum }
                    snapshot.entries.filter { (local[it.catnum]?.epoch ?: Double.NEGATIVE_INFINITY) <= it.epoch }
                } else {
                    dao.clearEntriesForRestore(); dao.clearRadiosForRestore()
                    snapshot.entries
                }
                dao.restoreEntries(incomingEntries)
                dao.restoreRadios(snapshot.radios)
            }
            if (BackupCategory.LOGBOOK in categories) logs.runInTransaction {
                val dao = logs.qsoDao()
                val restored = if (mode == RestoreMode.MERGE)
                    mergeBackupRecords(dao.snapshotRecords().map { it.toDomain() }, snapshot.records)
                else snapshot.records
                dao.clearForRestore()
                dao.restoreRecords(restored.map { it.toEntity() })
            }
            val editor = preferences.edit()
            if (mode == RestoreMode.REPLACE) {
                preferences.namedBackupValues().keys.filter { preferenceCategory(it) in categories }.forEach(editor::remove)
            }
            snapshot.preferences.filterKeys { preferenceCategory(it) in categories }.forEach { (key, stored) ->
                writePreference(editor, key, stored.value())
            }
            if (!rollback) {
                // A cursor belongs to a server/account generation, not to the restored phone.
                if (BackupCategory.LOGBOOK in categories || BackupCategory.CREDENTIALS in categories) {
                    listOf("lotwLastSyncDate", "lotwLastQslCursor", "wavelogSyncCursors",
                        "wavelogLastSyncEpochMs", "wavelogLastFullSyncEpochMs").forEach(editor::remove)
                }
                if (BackupCategory.SATELLITES in categories)
                    editor.putLong("databaseContentVersion", System.currentTimeMillis())
            }
            check(editor.commit()) { "Could not save restored settings" }
            val names = buildList {
                if (BackupCategory.LOGBOOK in categories) addAll(listOf("station", "receipts"))
                if (BackupCategory.CREDENTIALS in categories) add("certificate")
            }
            names.forEach { name ->
                val bytes = snapshot.lotw[name]?.let { Base64.decode(it, Base64.NO_WRAP) }
                if (bytes != null) {
                    try { lotw.write(name, bytes) } finally { bytes.fill(0) }
                } else if (mode == RestoreMode.REPLACE) lotw.delete(name)
            }
            if (BackupCategory.CREDENTIALS in categories)
                audio.restore(snapshot.audio.mapValues { it.value.username to it.value.password }, mode == RestoreMode.REPLACE)
            if (BackupCategory.SETTINGS in categories) {
                check(astronomy.edit().putString("downloadUrl", snapshot.astronomyUrl).commit())
                val edit = schedule.edit()
                if (mode == RestoreMode.REPLACE) scheduleKeys.forEach(edit::remove)
                snapshot.schedule.forEach { (key, value) -> writePreference(edit, key, value.value()) }
                check(edit.commit())
            }
            if (rollback) {
                val edit = astronomy.edit().remove("verifiedGeneration").remove("verifiedFingerprint")
                snapshot.astronomyGeneration?.let { edit.putString("activeDirectory", it) } ?: edit.remove("activeDirectory")
                check(edit.commit())
            }
        }
    }

    fun writeSnapshot(target: File, snapshot: BackupSnapshot) = backupStep("snapshot-json") {
        target.writeText(backupJson.encodeToString(snapshot), Charsets.UTF_8)
    }

    fun readSnapshot(source: File): BackupSnapshot {
        require(source.length() <= 256L * 1024 * 1024) { "Snapshot metadata is too large" }
        return backupJson.decodeFromString(source.readText(Charsets.UTF_8))
    }

    companion object {
        private val scheduleKeys = setOf("satellite_ids", "minimum_elevation", "minimum_elevation_value",
            "reminder_minutes_set", "notify_at_start", "notify_before", "reminder_minutes")
        internal fun writePreference(editor: SharedPreferences.Editor, key: String, value: Any) {
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("Unsupported preference value")
            }
        }
    }
}
