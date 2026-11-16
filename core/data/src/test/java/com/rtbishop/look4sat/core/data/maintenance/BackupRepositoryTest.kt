package com.rtbishop.look4sat.core.data.maintenance

import android.content.Context
import androidx.room.Room
import com.rtbishop.look4sat.core.data.audio.RadioAudioSecretStore
import com.rtbishop.look4sat.core.data.database.Look4SatDb
import com.rtbishop.look4sat.core.data.database.QsoDatabase
import com.rtbishop.look4sat.core.data.database.entity.SatEntry
import com.rtbishop.look4sat.core.data.lotw.LoTWStorage
import com.rtbishop.look4sat.core.data.repository.toEntity
import com.rtbishop.look4sat.core.domain.audio.IRecordingService
import com.rtbishop.look4sat.core.domain.audio.RecordingState
import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.maintenance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var satellites: Look4SatDb
    private lateinit var logs: QsoDatabase
    private lateinit var scope: CoroutineScope
    private val credentials = MemorySecrets()
    private val audio = MemoryAudioSecrets()
    private val preferences get() = context.getSharedPreferences("test-backup", Context.MODE_PRIVATE)
    private lateinit var store: BackupSnapshotStore
    private var stops = 0
    private var failRefresh = false
    private var recordingFailure: Throwable? = null
    private lateinit var repository: BackupRepository
    private val record = QsoRecord(startUtcMillis = 1000, theirCallsign = "JA1ABC", myCallsign = "BA7OPF",
        satelliteName = "FO-29", mode = "MFSK", submode = "JTTY", myGrid = "OL62TI",
        myFullGrids = listOf("OL62TI", "OL63AB"), vuccGrids = listOf("OL62", "OL63"),
        rawMessages = listOf("DE BA7OPF"))

    @Before fun setUp() {
        DataMaintenance.restartRequired = false
        satellites = Room.inMemoryDatabaseBuilder(context, Look4SatDb::class.java).allowMainThreadQueries().build()
        logs = Room.inMemoryDatabaseBuilder(context, QsoDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = BackupSnapshotStore(context, satellites, logs, preferences, credentials, audio)
        store.journal.deleteRecursively()
        store.recordingRoots.values.forEach { it.deleteRecursively() }
        val recorder = Proxy.newProxyInstance(IRecordingService::class.java.classLoader,
            arrayOf(IRecordingService::class.java)) { _, method, _ ->
            when (method.name) {
                "getState" -> MutableStateFlow(RecordingState())
                "getFiles" -> MutableStateFlow(emptyList<Any>())
                "rotateForBackup" -> { recordingFailure?.let { throw it }; Unit }
                else -> Unit
            }
        } as IRecordingService
        repository = BackupRepository(context, scope, store, recorder, "test",
            stopServices = { stops++ }, refreshSettings = {
                if (failRefresh) { failRefresh = false; error("Injected failure after database writes") }
            })
    }

    @After fun close() {
        scope.cancel()
        DataMaintenance.restartRequired = false
        satellites.close()
        logs.close()
    }

    private suspend fun await(vararg phases: BackupPhase) =
        withTimeout(30_000) { repository.state.first { it.phase in phases } }

    private suspend fun backup(categories: Set<BackupCategory>, password: String = ""): File {
        val target = File(temp.root, "backup-${System.nanoTime()}.l4sbackup")
        repository.create(target.toURI().toString(), BackupOptions(categories), password.toCharArray())
        val result = await(BackupPhase.COMPLETE, BackupPhase.FAILED)
        assertEquals(result.error, BackupPhase.COMPLETE, result.phase)
        return target
    }

    private suspend fun inspect(file: File, password: String = "") {
        repository.inspect(file.toURI().toString(), password.toCharArray())
        val result = await(BackupPhase.PREVIEW, BackupPhase.FAILED)
        assertEquals(result.error, BackupPhase.PREVIEW, result.phase)
    }

    @Test fun memoryFailureEndsPreparingAndAllowsRetryWithoutChangingData() = runBlocking {
        preferences.edit().putString("callsign", "BA7OPF").commit()
        recordingFailure = OutOfMemoryError("injected")
        repository.create(File(temp.root, "failed.l4sbackup").toURI().toString(),
            BackupOptions(setOf(BackupCategory.RECORDINGS)), charArrayOf())
        assertEquals(BackupPhase.FAILED, await(BackupPhase.COMPLETE, BackupPhase.FAILED).phase)
        assertEquals("BA7OPF", preferences.getString("callsign", ""))
        assertFalse(repository.state.value.busy)
        recordingFailure = null
        backup(setOf(BackupCategory.SETTINGS))
        Unit
    }

    @Test fun selectedCategoriesReplaceAndPreserveUnselectedData() = runBlocking {
        preferences.edit().putString("callsign", "BA7OPF").commit()
        logs.qsoDao().restoreRecords(listOf(record.toEntity()))
        val backup = backup(setOf(BackupCategory.SETTINGS, BackupCategory.LOGBOOK))
        preferences.edit().putString("callsign", "OTHER").putString("new-setting", "remove").commit()
        logs.qsoDao().clearForRestore()
        inspect(backup)
        repository.preview(setOf(BackupCategory.SETTINGS), RestoreMode.REPLACE)
        val preview = await(BackupPhase.PREVIEW, BackupPhase.FAILED).preview!!
        assertEquals(1L, preview.changed[BackupCategory.SETTINGS])
        assertEquals(1L, preview.removed[BackupCategory.SETTINGS])
        repository.restore(setOf(BackupCategory.SETTINGS), RestoreMode.REPLACE)
        assertTrue(await(BackupPhase.COMPLETE, BackupPhase.FAILED).needsRestart)
        assertEquals("BA7OPF", preferences.getString("callsign", ""))
        assertFalse(preferences.contains("new-setting"))
        assertTrue(logs.qsoDao().snapshotRecords().isEmpty())
        assertEquals(1, stops)
        assertFalse(store.journal.exists())
    }

    @Test fun failedRestoreRollsBackBothDatabasesSettingsAndSecrets() = runBlocking {
        preferences.edit().putString("callsign", "BACKUP").commit()
        logs.qsoDao().restoreRecords(listOf(record.toEntity()))
        credentials.write("certificate", byteArrayOf(1, 2, 3))
        audio.values["radio"] = "alice" to "radio123"
        val categories = setOf(BackupCategory.SETTINGS, BackupCategory.SATELLITES,
            BackupCategory.LOGBOOK, BackupCategory.CREDENTIALS)
        val backup = backup(categories, "portable")
        preferences.edit().putString("callsign", "ORIGINAL").commit()
        logs.qsoDao().clearForRestore()
        credentials.write("certificate", byteArrayOf(4))
        audio.values["radio"] = "bob" to "original"
        val original = SatEntry("LOCAL", 26300.0, 15.0, .001, 50.0, 0.0, 0.0, 0.0, 123, 0.0)
        satellites.look4SatDao().restoreEntries(listOf(original))
        inspect(backup, "portable")
        failRefresh = true
        repository.restore(categories, RestoreMode.REPLACE)
        assertEquals(BackupPhase.FAILED, await(BackupPhase.COMPLETE, BackupPhase.FAILED).phase)
        assertFalse(DataMaintenance.restartRequired)
        assertEquals("ORIGINAL", preferences.getString("callsign", ""))
        assertTrue(logs.qsoDao().snapshotRecords().isEmpty())
        assertEquals(listOf(original), satellites.look4SatDao().snapshotEntries())
        assertArrayEquals(byteArrayOf(4), credentials.read("certificate"))
        assertEquals("bob" to "original", audio.values["radio"])
        assertFalse(store.journal.exists())
    }

    @Test fun credentialsRestoreIntoDifferentStoreAndPasswordIsErased() = runBlocking {
        credentials.write("certificate", byteArrayOf(11, 22))
        audio.values["radio"] = "alice" to "secret"
        preferences.edit().putString("lotwPassword", "private").putString("lotwCallsign", "BA7OPF").commit()
        val file = backup(setOf(BackupCategory.CREDENTIALS), "portable")
        credentials.data.clear(); audio.values.clear(); preferences.edit().clear().commit()
        val password = "portable".toCharArray()
        repository.inspect(file.toURI().toString(), password)
        assertEquals(BackupPhase.PREVIEW, await(BackupPhase.PREVIEW, BackupPhase.FAILED).phase)
        assertTrue(password.all { it == '\u0000' })
        repository.restore(setOf(BackupCategory.CREDENTIALS), RestoreMode.MERGE)
        assertTrue(await(BackupPhase.COMPLETE, BackupPhase.FAILED).needsRestart)
        assertArrayEquals(byteArrayOf(11, 22), credentials.read("certificate"))
        assertEquals("alice" to "secret", audio.values["radio"])
        assertEquals("private", preferences.getString("lotwPassword", ""))
    }

    @Test fun corruptArchiveNeverStopsServicesOrMutatesData() = runBlocking {
        preferences.edit().putString("callsign", "KEEP").commit()
        val bad = temp.newFile().apply { writeText("invalid backup") }
        repository.inspect(bad.toURI().toString(), charArrayOf())
        assertEquals(BackupPhase.FAILED, await(BackupPhase.PREVIEW, BackupPhase.FAILED).phase)
        assertEquals(0, stops)
        assertEquals("KEEP", preferences.getString("callsign", ""))
    }

    @Test fun failedExportRetainsOnlyPortableArchiveAndCanRetry() = runBlocking {
        preferences.edit().putString("lotwPassword", "private").commit()
        repository.create(File(temp.root, "absent/file.l4sbackup").toURI().toString(),
            BackupOptions(setOf(BackupCategory.CREDENTIALS)), "portable".toCharArray())
        val failed = await(BackupPhase.COMPLETE, BackupPhase.FAILED)
        assertEquals(BackupPhase.FAILED, failed.phase)
        assertTrue(failed.retryExportAvailable)
        val work = File(context.noBackupFilesDir, "backup-work")
        assertTrue(work.walkTopDown().none { it.name == "snapshot.json" })
        val target = temp.newFile()
        repository.retryExport(target.toURI().toString())
        assertEquals(BackupPhase.COMPLETE, await(BackupPhase.COMPLETE, BackupPhase.FAILED).phase)
        assertTrue(target.length() > 0)
        assertFalse(repository.state.value.retryExportAvailable)
    }

    @Test fun recordingMergeDeduplicatesAudioAndRecoversMissingSidecar() = runBlocking {
        val directory = store.recordingRoots.getValue("Recordings").apply { mkdirs() }
        val wav = File(directory, "test.wav").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val sidecar = File(directory, "test.wav.json").apply { writeText("""{"satellite":"FO-29"}""") }
        val file = backup(setOf(BackupCategory.RECORDINGS))
        sidecar.delete()
        inspect(file)
        assertEquals(0L, repository.state.value.preview!!.added[BackupCategory.RECORDINGS])
        repository.restore(setOf(BackupCategory.RECORDINGS), RestoreMode.MERGE)
        assertEquals(BackupPhase.COMPLETE, await(BackupPhase.COMPLETE, BackupPhase.FAILED).phase)
        assertEquals(1, directory.listFiles()!!.count { it.extension == "wav" })
        assertEquals(128L, wav.length())
        assertEquals("""{"satellite":"FO-29"}""", sidecar.readText())
    }

    @Test fun settingsIncludeScheduleConditionsWithoutPersistingComputedEvents() {
        val schedule = context.getSharedPreferences("pass_schedule_preferences", Context.MODE_PRIVATE)
        schedule.edit().putStringSet("satellite_ids", setOf("24278")).putFloat("minimum_elevation_value", 20f)
            .putString("events", """[{"cached":true}]""").commit()
        val snapshot = store.snapshot(setOf(BackupCategory.SETTINGS))
        assertEquals(setOf("satellite_ids", "minimum_elevation_value"), snapshot.schedule.keys)
        schedule.edit().putFloat("minimum_elevation_value", 50f).commit()
        store.validate(snapshot, setOf(BackupCategory.SETTINGS))
        store.apply(snapshot, setOf(BackupCategory.SETTINGS), RestoreMode.REPLACE)
        assertEquals(20f, schedule.getFloat("minimum_elevation_value", 0f), 0f)
        assertEquals("""[{"cached":true}]""", schedule.getString("events", ""))
    }

    @Test fun unnamedLegacyPreferencesDoNotPreventBackupOrReplacingNamedSettings() = runBlocking {
        preferences.edit().putBoolean(null, true).putString("callsign", "BA7OPF").commit()
        val schedule = context.getSharedPreferences("pass_schedule_preferences", Context.MODE_PRIVATE)
        schedule.edit().putBoolean(null, true).putBoolean("notify_at_start", true).commit()
        assertTrue(preferences.all.containsKey(null))
        val file = backup(setOf(BackupCategory.SETTINGS))
        inspect(file)
        preferences.edit().putString("callsign", "CHANGED").putString("new-setting", "remove").commit()
        repository.restore(setOf(BackupCategory.SETTINGS), RestoreMode.REPLACE)
        assertTrue(await(BackupPhase.COMPLETE, BackupPhase.FAILED).needsRestart)
        assertEquals("BA7OPF", preferences.getString("callsign", ""))
        assertFalse(preferences.contains("new-setting"))
        assertTrue(preferences.getBoolean(null, false))
        assertTrue(schedule.getBoolean(null, false))
        assertTrue(schedule.getBoolean("notify_at_start", false))
    }

    @Test fun startupJournalRestoresUncommittedChangesAndIsIdempotent() {
        preferences.edit().putString("callsign", "BEFORE").commit()
        logs.qsoDao().restoreRecords(listOf(record.toEntity()))
        val all = BackupCategory.entries.toSet()
        val contents = temp.newFolder()
        store.writeSnapshot(File(contents, "snapshot.json"), store.snapshot(all))
        val file = File(contents, "snapshot.json")
        val manifest = BackupManifest(createdAtMillis = 1, appVersion = "test", categories = all,
            counts = all.associateWith { 0L }, entries = listOf(BackupEntry("snapshot.json", file.length(),
                BackupArchive.digest(file), BackupCategory.SETTINGS)))
        store.journal.mkdirs()
        BackupArchive.pack(contents, manifest, File(store.journal, "rollback.l4sbackup"), "recovery".toCharArray()) {}
        credentials.write("rollback", "recovery".toByteArray())
        File(store.journal, "ready").writeText("SETTINGS,LOGBOOK")
        preferences.edit().putString("callsign", "HALF-WRITTEN").commit()
        logs.qsoDao().clearForRestore()
        BackupRepository.recover(store)
        BackupRepository.recover(store)
        assertEquals("BEFORE", preferences.getString("callsign", ""))
        assertEquals(1, logs.qsoDao().snapshotRecords().size)
        assertFalse(store.journal.exists())
        assertNull(credentials.read("rollback"))
    }

    @Test fun recoveryWorksOnIoWithNormalRoomThreadRestrictions() = runBlocking {
        val satelliteDb = Room.inMemoryDatabaseBuilder(context, Look4SatDb::class.java).build()
        val logDb = Room.inMemoryDatabaseBuilder(context, QsoDatabase::class.java).build()
        val recoveryStore = BackupSnapshotStore(context, satelliteDb, logDb, preferences, credentials, audio)
        try {
            withContext(Dispatchers.IO) {
                logDb.qsoDao().restoreRecords(listOf(record.toEntity()))
                val all = BackupCategory.entries.toSet()
                val contents = temp.newFolder()
                recoveryStore.writeSnapshot(File(contents, "snapshot.json"), recoveryStore.snapshot(all))
                val file = File(contents, "snapshot.json")
                val manifest = BackupManifest(createdAtMillis = 1, appVersion = "test", categories = all,
                    counts = all.associateWith { 0L }, entries = listOf(BackupEntry("snapshot.json", file.length(),
                        BackupArchive.digest(file), BackupCategory.SETTINGS)))
                recoveryStore.journal.mkdirs()
                BackupArchive.pack(contents, manifest, File(recoveryStore.journal, "rollback.l4sbackup"), "recovery".toCharArray()) {}
                credentials.write("rollback", "recovery".toByteArray())
                File(recoveryStore.journal, "ready").writeText("LOGBOOK")
                logDb.qsoDao().clearForRestore()
                BackupRepository.recover(recoveryStore)
                assertEquals(1, logDb.qsoDao().snapshotRecords().size)
            }
        } finally {
            satelliteDb.close()
            logDb.close()
        }
    }

    @Test fun mergeRetainsJttyRawFieldsMultipleMainGridsAndIsIdempotent() {
        val incoming = record.copy(id = 900, lotwConfirmed = true, rawMessages = listOf("73"),
            myFullGrids = listOf("OL64AA"), vuccGrids = listOf("OL64"), comment = "restored")
        val merged = mergeBackupRecords(listOf(record.copy(id = 7)), listOf(incoming))
        assertEquals(1, merged.size)
        assertEquals(7L, merged.single().id)
        assertEquals("JTTY", merged.single().submode)
        assertEquals(listOf("OL62", "OL63", "OL64"), merged.single().vuccGrids)
        assertEquals(listOf("OL62TI", "OL63AB", "OL64AA"), merged.single().myFullGrids)
        assertEquals(listOf("DE BA7OPF", "73"), merged.single().rawMessages)
        assertTrue(merged.single().lotwConfirmed)
        assertEquals(merged, mergeBackupRecords(merged, listOf(incoming)))
    }

    @Test fun preferencesAreFrozenAfterRestoreUntilRestart() {
        val guarded = SnapshotPreferences(preferences)
        DataMaintenance.restartRequired = true
        assertTrue(runCatching { guarded.edit().putString("callsign", "late sync").commit() }
            .exceptionOrNull() is CancellationException)
        assertFalse(preferences.contains("callsign"))
    }

    private class MemorySecrets : LoTWStorage {
        val data = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = data[name]?.copyOf()
        override fun write(name: String, data: ByteArray) { this.data[name] = data.copyOf() }
        override fun delete(name: String) { data.remove(name) }
    }
    private class MemoryAudioSecrets : RadioAudioSecretStore {
        val values = mutableMapOf<String, Pair<String, String>>()
        override fun snapshot() = values.toMap()
        override fun restore(values: Map<String, Pair<String, String>>, replace: Boolean) {
            if (replace) this.values.clear()
            this.values.putAll(values)
        }
    }
}
