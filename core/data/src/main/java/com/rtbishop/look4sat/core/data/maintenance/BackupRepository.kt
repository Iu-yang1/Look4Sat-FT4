package com.rtbishop.look4sat.core.data.maintenance

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.rtbishop.look4sat.core.data.astro.AstroDataPackage
import com.rtbishop.look4sat.core.data.astro.OrekitRuntime
import com.rtbishop.look4sat.core.domain.audio.IRecordingService
import com.rtbishop.look4sat.core.domain.maintenance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.SecureRandom
import java.util.UUID

internal class BackupRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: BackupSnapshotStore,
    private val recordings: IRecordingService,
    private val version: String,
    private val stopServices: suspend () -> Unit,
    private val refreshSettings: () -> Unit
) : IBackupRepository {
    private val history = context.getSharedPreferences("backup-history", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(BackupState(
        lastBackupMillis = history.getLong("time", 0), lastBackupName = history.getString("name", "").orEmpty()
    ))
    override val state = mutable.asStateFlow()
    private var active: Job? = null
    private var estimateJob: Job? = null
    private var pending: File? = null
    private var manifest: BackupManifest? = null
    private var encrypted = false
    private val root = File(context.noBackupFilesDir, "backup-work")
    private val cleanOldWork = scope.async(Dispatchers.IO) {
        root.listFiles().orEmpty().forEach(::deleteWork)
    }

    @Synchronized private fun launch(phase: BackupPhase, password: CharArray = charArrayOf(), block: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.needsRestart) { password.fill('\u0000'); return }
        val previous = active
        mutable.update { it.copy(phase = phase, error = null, completedBytes = 0, totalBytes = 0) }
        active = scope.launch(Dispatchers.IO) {
            try { previous?.join(); cleanOldWork.await(); block() }
            catch (cancelled: CancellationException) {
                mutable.update { it.copy(phase = BackupPhase.IDLE) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(phase = BackupPhase.FAILED, error = error.message ?: error.javaClass.simpleName) }
            } finally { password.fill('\u0000') }
        }
    }

    override fun create(destinationUri: String, options: BackupOptions, password: CharArray) =
        launch(BackupPhase.PREPARING, password) {
            require(options.categories.isNotEmpty())
            require(BackupCategory.CREDENTIALS !in options.categories || password.isNotEmpty()) { "A password is required" }
            pending?.let(::deleteWork)
            pending = null
            manifest = null
            mutable.update { it.copy(preview = null, retryExportAvailable = false) }
            val task = currentCoroutineContext()
            val work = newWork()
            val archive = File(work, "archive.l4sbackup")
            val snapshotRoot = File(work, "contents").apply { check(mkdirs()) }
            try {
                if (BackupCategory.RECORDINGS in options.categories) recordings.rotateForBackup()
                val snapshot = DataMaintenance.write {
                    val value = store.snapshot(options.categories)
                    store.writeSnapshot(File(snapshotRoot, "snapshot.json"), value)
                    if (BackupCategory.RECORDINGS in options.categories) {
                        store.recordingRoots.forEach { (name, directory) ->
                            directory.listFiles().orEmpty().filter {
                                it.isFile && (it.name.endsWith(".wav") || it.name.endsWith(".wav.json"))
                            }.forEach { file ->
                                copyFile(file, File(snapshotRoot, "recordings/$name/${file.name}"), task::ensureActive)
                            }
                        }
                    }
                    value
                }
                if (BackupCategory.ASTRONOMY in options.categories) {
                    snapshot.astronomyGeneration?.let { generation ->
                        val directory = File(store.astronomyRoot, generation)
                        val metadata = AstroDataPackage.metadata(directory)
                        if (!metadata.builtIn) {
                            directory.listFiles().orEmpty().filter(File::isFile).forEach {
                                copyFile(it, File(snapshotRoot, "astronomy/${it.name}"), task::ensureActive)
                            }
                        }
                    }
                }
                val entries = entries(snapshotRoot, options.categories, task::ensureActive)
                val counts = store.counts(snapshot, options.categories).toMutableMap()
                if (BackupCategory.RECORDINGS in options.categories)
                    counts[BackupCategory.RECORDINGS] = entries.count { it.category == BackupCategory.RECORDINGS && it.path.endsWith(".wav") }.toLong()
                if (BackupCategory.ASTRONOMY in options.categories)
                    counts[BackupCategory.ASTRONOMY] = if (entries.any { it.path == "astronomy/manifest.json" }) 1 else 0
                val description = BackupManifest(
                    createdAtMillis = System.currentTimeMillis(), appVersion = version,
                    categories = options.categories, counts = counts, entries = entries
                )
                BackupArchive.pack(snapshotRoot, description, archive, password, task::ensureActive)
                password.fill('\u0000')
                check(snapshotRoot.deleteRecursively()) { "Could not remove backup staging data" }
                mutable.update { it.copy(phase = BackupPhase.EXPORTING, totalBytes = archive.length()) }
                val uri = Uri.parse(destinationUri)
                try {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open backup destination" }.use { output ->
                        archive.inputStream().use { input ->
                            BackupArchive.copy(input, output, check = {
                                task.ensureActive()
                                mutable.update { it.copy(completedBytes = archive.length().coerceAtMost(it.completedBytes + 65536)) }
                            })
                        }
                    }
                } catch (error: Exception) {
                    // Retain the fully validated local artifact for retry after provider failure.
                    pending?.let(::deleteWork)
                    pending = work
                    mutable.update { it.copy(retryExportAvailable = true) }
                    throw error
                }
                check(history.edit().putLong("time", description.createdAtMillis)
                    .putString("name", uri.lastPathSegment.orEmpty()).commit())
                mutable.update { it.copy(
                    phase = BackupPhase.COMPLETE, lastBackupMillis = description.createdAtMillis,
                    lastBackupName = uri.lastPathSegment.orEmpty(), completedBytes = archive.length(), retryExportAvailable = false
                ) }
            } finally { if (pending != work) deleteWork(work) }
        }

    override fun estimate(options: BackupOptions) {
        estimateJob?.cancel()
        estimateJob = scope.launch(Dispatchers.IO) {
            try {
                val snapshot = DataMaintenance.write { store.snapshot(options.categories) }
                val counts = store.counts(snapshot, options.categories).toMutableMap()
                var bytes = 1024L + backupJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size
                if (BackupCategory.RECORDINGS in options.categories) {
                    val files = store.recordingRoots.values.flatMap { it.listFiles().orEmpty().filter {
                        file -> file.isFile && (file.name.endsWith(".wav") || file.name.endsWith(".wav.json"))
                    } }
                    counts[BackupCategory.RECORDINGS] = files.count { it.extension == "wav" }.toLong()
                    bytes += files.sumOf { it.length() }
                }
                if (BackupCategory.ASTRONOMY in options.categories) {
                    val files = snapshot.astronomyGeneration?.let { File(store.astronomyRoot, it) }
                        ?.takeIf { it.isDirectory }?.let { directory ->
                            if (AstroDataPackage.metadata(directory).builtIn) emptyList() else directory.listFiles().orEmpty().toList()
                        }.orEmpty()
                    counts[BackupCategory.ASTRONOMY] = if (files.isEmpty()) 0 else 1
                    bytes += files.sumOf { it.length() }
                }
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(estimatedBytes = bytes, availableCounts = counts) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(estimatedBytes = null, availableCounts = emptyMap()) } }
        }
    }

    override fun retryExport(destinationUri: String) = launch(BackupPhase.EXPORTING) {
        val work = checkNotNull(pending)
        val archive = File(work, "archive.l4sbackup")
        check(mutable.value.retryExportAvailable && archive.isFile)
        val task = currentCoroutineContext()
        mutable.update { it.copy(totalBytes = archive.length()) }
        checkNotNull(context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")).use { output ->
            archive.inputStream().use { input -> BackupArchive.copy(input, output, check = task::ensureActive) }
        }
        val savedAt = System.currentTimeMillis()
        val name = Uri.parse(destinationUri).lastPathSegment.orEmpty()
        check(history.edit().putLong("time", savedAt).putString("name", name).commit())
        mutable.update { it.copy(phase = BackupPhase.COMPLETE, lastBackupMillis = savedAt, lastBackupName = name,
            retryExportAvailable = false) }
        deleteWork(work); pending = null
    }

    override fun inspect(sourceUri: String, password: CharArray) = launch(BackupPhase.READING, password) {
        val task = currentCoroutineContext()
        pending?.let(::deleteWork)
        manifest = null
        mutable.update { it.copy(preview = null, retryExportAvailable = false) }
        val work = newWork()
        pending = work
        val incoming = File(work, "incoming.l4sbackup")
        val contents = File(work, "contents")
        try {
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(sourceUri))) { "Cannot open backup" }.use { input ->
                incoming.outputStream().buffered().use { output ->
                    BackupArchive.copy(input, output, freeSpace(work), task::ensureActive)
                }
            }
            val (description, secured) = BackupArchive.unpack(incoming, contents, password, freeSpace(work), task::ensureActive)
            password.fill('\u0000')
            val snapshot = store.readSnapshot(File(contents, "snapshot.json"))
            store.validate(snapshot, description.categories)
            validateFiles(description, contents, task::ensureActive)
            manifest = description
            encrypted = secured
            incoming.delete()
            updatePreview(description.categories, RestoreMode.MERGE)
        } catch (error: Exception) {
            deleteWork(work); pending = null
            throw error
        }
    }

    override fun preview(categories: Set<BackupCategory>, mode: RestoreMode) {
        if (mutable.value.busy || mutable.value.needsRestart) return
        launch(BackupPhase.READING) { updatePreview(categories, mode) }
    }

    private suspend fun updatePreview(categories: Set<BackupCategory>, mode: RestoreMode) {
        val description = checkNotNull(manifest)
        require(categories.isNotEmpty() && description.categories.containsAll(categories))
        val snapshot = store.readSnapshot(File(checkNotNull(pending), "contents/snapshot.json"))
        val existing = DataMaintenance.write { store.snapshot(categories) }
        val newCounts = description.counts.filterKeys { it in categories }
        val added = mutableMapOf<BackupCategory, Long>()
        val changed = mutableMapOf<BackupCategory, Long>()
        val removed = mutableMapOf<BackupCategory, Long>()
        categories.forEach { category ->
            val after = newCounts[category] ?: 0
            when (category) {
                BackupCategory.LOGBOOK -> {
                    val merged = mergeBackupRecords(existing.records, snapshot.records)
                    val old = existing.records.associateBy(::recordKey)
                    val incoming = snapshot.records.associateBy(::recordKey)
                    added[category] = if (mode == RestoreMode.MERGE) (merged.size - existing.records.size).toLong()
                        else incoming.keys.count { it !in old }.toLong()
                    changed[category] = if (mode == RestoreMode.MERGE) merged.take(existing.records.size).zip(existing.records)
                        .count { it.first != it.second }.toLong()
                        else incoming.count { (key, record) -> old[key]?.let { it.copy(id = 0) != record.copy(id = 0) } == true }.toLong()
                    if (mode == RestoreMode.REPLACE) removed[category] = old.keys.count { it !in incoming }.toLong()
                }
                BackupCategory.SETTINGS, BackupCategory.CREDENTIALS -> {
                    added[category] = snapshot.preferences.keys.count {
                        preferenceCategory(it) == category && it !in existing.preferences
                    }.toLong()
                    changed[category] = snapshot.preferences.keys.count {
                        preferenceCategory(it) == category && it in existing.preferences &&
                            snapshot.preferences[it] != existing.preferences[it]
                    }.toLong()
                    if (mode == RestoreMode.REPLACE) removed[category] = existing.preferences.keys.count {
                        preferenceCategory(it) == category && it !in snapshot.preferences
                    }.toLong()
                    if (category == BackupCategory.SETTINGS) {
                        added[category] = checkNotNull(added[category]) + snapshot.schedule.keys.count { it !in existing.schedule }
                        changed[category] = checkNotNull(changed[category]) + snapshot.schedule.keys.count {
                            it in existing.schedule && snapshot.schedule[it] != existing.schedule[it]
                        }
                        if (mode == RestoreMode.REPLACE) removed[category] = checkNotNull(removed[category]) +
                            existing.schedule.keys.count { it !in snapshot.schedule }
                    }
                    if (category == BackupCategory.CREDENTIALS) {
                        added[category] = checkNotNull(added[category]) + snapshot.audio.keys.count { it !in existing.audio } +
                            snapshot.lotw.keys.count { it == "certificate" && it !in existing.lotw }
                        changed[category] = checkNotNull(changed[category]) + snapshot.audio.keys.count {
                            it in existing.audio && snapshot.audio[it] != existing.audio[it]
                        } + snapshot.lotw.keys.count { it == "certificate" && it in existing.lotw && snapshot.lotw[it] != existing.lotw[it] }
                        if (mode == RestoreMode.REPLACE) removed[category] = checkNotNull(removed[category]) +
                            existing.audio.keys.count { it !in snapshot.audio } +
                            existing.lotw.keys.count { it == "certificate" && it !in snapshot.lotw }
                    }
                }
                BackupCategory.SATELLITES -> {
                    val entryMap = existing.entries.associateBy { it.catnum }
                    val radioMap = existing.radios.associateBy { it.uuid }
                    added[category] = (snapshot.entries.count { it.catnum !in entryMap } + snapshot.radios.count { it.uuid !in radioMap }).toLong()
                    changed[category] = (snapshot.entries.count { it.catnum in entryMap && it != entryMap[it.catnum] &&
                        (mode == RestoreMode.REPLACE || it.epoch >= checkNotNull(entryMap[it.catnum]).epoch) } +
                        snapshot.radios.count { it.uuid in radioMap && it != radioMap[it.uuid] }).toLong()
                    if (mode == RestoreMode.REPLACE) removed[category] =
                        (entryMap.keys.minus(snapshot.entries.map { it.catnum }.toSet()).size +
                            radioMap.keys.minus(snapshot.radios.map { it.uuid }.toSet()).size).toLong()
                }
                BackupCategory.RECORDINGS -> {
                    val files = description.entries.filter { it.category == category && it.path.endsWith(".wav") }
                    var newFiles = 0L
                    for (entry in files) {
                        val folder = entry.path.split('/')[1]
                        val directory = checkNotNull(store.recordingRoots[folder])
                        if (directory.listFiles().orEmpty().none { it.isFile && it.extension == "wav" && it.length() == entry.bytes &&
                                BackupArchive.digest(it) == entry.sha256 }) newFiles++
                    }
                    added[category] = newFiles
                    if (mode == RestoreMode.REPLACE) {
                        val hashes = files.map { it.sha256 }.toSet()
                        removed[category] = store.recordingRoots.values.sumOf { directory ->
                            directory.listFiles().orEmpty().count { it.isFile && it.extension == "wav" &&
                                BackupArchive.digest(it) !in hashes }.toLong()
                        }
                    }
                }
                BackupCategory.ASTRONOMY -> added[category] = after
            }
        }
        mutable.update { it.copy(phase = BackupPhase.PREVIEW, preview = BackupContents(
            description.createdAtMillis, description.appVersion, description.counts,
            description.entries.sumOf { it.bytes }, encrypted, added, changed, removed
        )) }
    }

    override fun restore(categories: Set<BackupCategory>, mode: RestoreMode) = launch(BackupPhase.RESTORING) {
        val description = checkNotNull(manifest)
        require(categories.isNotEmpty() && description.categories.containsAll(categories))
        val work = checkNotNull(pending)
        val contents = File(work, "contents")
        val snapshot = store.readSnapshot(File(contents, "snapshot.json"))
        store.validate(snapshot, description.categories)
        val task = currentCoroutineContext()
        validateFiles(description, contents, task::ensureActive)
        stopServices()
        DataMaintenance.write {
            withContext(NonCancellable) {
                synchronized(DataMaintenance.preferencesLock) {
                    val recoveryBytes = prepareRollback(categories, task::ensureActive)
                    val incomingBytes = description.entries.filter { it.category in categories }.sumOf { it.bytes }
                    if (recoveryBytes + incomingBytes > freeSpace(work)) {
                        discardJournal(store)
                        error("Not enough storage to restore and retain recovery data")
                    }
                    // Preferences and database writers stay excluded through commit and the restart barrier.
                    try {
                        store.apply(snapshot, categories, mode)
                        restoreFiles(description, contents, categories, mode)
                        refreshSettings()
                        AtomicFile(File(store.journal, "committed")).run {
                            val stream = startWrite()
                            try { stream.write(1); finishWrite(stream) }
                            catch (error: Exception) { failWrite(stream); throw error }
                        }
                    } catch (error: Exception) {
                        try {
                            recover(store)
                            refreshSettings()
                        } catch (recoveryError: Exception) {
                            DataMaintenance.restartRequired = true
                            mutable.update { it.copy(needsRestart = true) }
                            recoveryError.addSuppressed(error)
                            throw recoveryError
                        }
                        throw error
                    }
                    DataMaintenance.restartRequired = true
                    mutable.update { it.copy(needsRestart = true,
                        needsRebinding = BackupCategory.SETTINGS in categories) }
                    discardJournal(store)
                }
            }
        }
        recordings.refreshFiles()
        mutable.update { it.copy(phase = BackupPhase.COMPLETE, needsRebinding = BackupCategory.SETTINGS in categories,
            needsRestart = true) }
        deleteWork(work); pending = null; manifest = null
    }

    private fun prepareRollback(categories: Set<BackupCategory>, checkCancelled: () -> Unit): Long {
        check(!store.journal.exists()) { "A previous restore needs recovery" }
        val staging = File(store.journal, "staging").apply { check(mkdirs()) }
        try {
            val snapshot = store.snapshot(BackupCategory.entries.toSet())
            store.writeSnapshot(File(staging, "snapshot.json"), snapshot)
            if (BackupCategory.RECORDINGS in categories) {
                store.recordingRoots.forEach { (name, root) ->
                    root.listFiles().orEmpty().filter(File::isFile).filter { !it.name.endsWith(".partial") }.forEach {
                        copyFile(it, File(staging, "recordings/$name/${it.name}"), checkCancelled)
                    }
                }
            }
            val all = BackupCategory.entries.toSet()
            val description = BackupManifest(
                createdAtMillis = System.currentTimeMillis(), appVersion = version, categories = all,
                counts = store.counts(snapshot, all), entries = entries(staging, all, checkCancelled)
            )
            val password = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.toCharArray()
            try {
                store.secretStorage.write("rollback", password.concatToString().toByteArray(Charsets.UTF_8))
                BackupArchive.pack(staging, description, File(store.journal, "rollback.l4sbackup"), password, checkCancelled)
                java.io.FileOutputStream(File(store.journal, "rollback.l4sbackup"), true).use { it.fd.sync() }
            } finally { password.fill('\u0000') }
            AtomicFile(File(store.journal, "ready")).run {
                val stream = startWrite()
                try { stream.write(categories.joinToString(",").toByteArray()); finishWrite(stream) }
                catch (error: Exception) { failWrite(stream); throw error }
            }
            return Math.addExact(description.entries.sumOf { it.bytes },
                File(store.journal, "rollback.l4sbackup").length())
        } catch (error: Exception) {
            discardJournal(store)
            throw error
        } finally { staging.deleteRecursively() }
    }

    private fun restoreFiles(
        manifest: BackupManifest, root: File, categories: Set<BackupCategory>, mode: RestoreMode
    ) {
        if (BackupCategory.RECORDINGS in categories) {
            if (mode == RestoreMode.REPLACE) store.recordingRoots.values.forEach { folder ->
                folder.listFiles().orEmpty().filter(File::isFile).forEach { check(it.delete()) }
            }
            for (entry in manifest.entries.filter { it.category == BackupCategory.RECORDINGS && it.path.endsWith(".wav") }) {
                val parts = entry.path.split('/')
                val directory = checkNotNull(store.recordingRoots[parts[1]])
                val duplicate = directory.listFiles().orEmpty().firstOrNull { it.isFile && it.extension == "wav" &&
                    it.length() == entry.bytes && BackupArchive.digest(it) == entry.sha256 }
                if (duplicate != null) {
                    val metadata = BackupArchive.safeFile(root, entry.path + ".json")
                    val target = File(duplicate.parentFile, duplicate.name + ".json")
                    if (metadata.isFile && !target.exists()) copyFile(metadata, target) {}
                    continue
                }
                val source = BackupArchive.safeFile(root, entry.path)
                var target = File(directory, parts[2])
                if (target.exists()) target = File(directory, "${target.nameWithoutExtension}-${entry.sha256.take(12)}.wav")
                copyFile(source, target) {}
                val metadata = File(source.parentFile, source.name + ".json")
                if (metadata.isFile) copyFile(metadata, File(target.parentFile, target.name + ".json")) {}
            }
        }
        if (BackupCategory.ASTRONOMY in categories && File(root, "astronomy/manifest.json").isFile) {
            val generation = "generation-${UUID.randomUUID()}"
            val target = File(store.astronomyRoot, generation)
            check(target.mkdirs())
            File(root, "astronomy").listFiles().orEmpty().forEach { copyFile(it, File(target, it.name)) {} }
            AstroDataPackage.verify(target) {}
            val runtime = OrekitRuntime(target, AstroDataPackage.metadata(target))
            runtime.validateCoverage {}
            check(context.getSharedPreferences("astronomy", Context.MODE_PRIVATE).edit().putString("activeDirectory", generation)
                .remove("verifiedGeneration").remove("verifiedFingerprint").commit())
        }
    }

    private fun validateFiles(manifest: BackupManifest, root: File, check: () -> Unit) {
        manifest.entries.forEach { entry ->
            require(entry.path == "snapshot.json" ||
                entry.category == BackupCategory.RECORDINGS && entry.path.matches(Regex("recordings/(Recordings|N76)/[^/]+\\.wav(\\.json)?")) ||
                entry.category == BackupCategory.ASTRONOMY && entry.path.matches(Regex("astronomy/[A-Za-z0-9_.-]+"))) {
                "Unexpected backup path"
            }
            val file = BackupArchive.safeFile(root, entry.path)
            require(file.length() == entry.bytes && BackupArchive.digest(file, check) == entry.sha256) { "Backup checksum mismatch" }
        }
        if (File(root, "astronomy/manifest.json").isFile) {
            AstroDataPackage.verify(File(root, "astronomy"), check)
            OrekitRuntime(File(root, "astronomy"), AstroDataPackage.metadata(File(root, "astronomy"))).validateCoverage(check)
        }
    }

    private fun entries(root: File, categories: Set<BackupCategory>, check: () -> Unit): List<BackupEntry> =
        root.walkTopDown().filter(File::isFile).map { file ->
            val name = file.relativeTo(root).invariantSeparatorsPath
            val category = when {
                name.startsWith("recordings/") -> BackupCategory.RECORDINGS
                name.startsWith("astronomy/") -> BackupCategory.ASTRONOMY
                else -> categories.first()
            }
            BackupEntry(name, file.length(), BackupArchive.digest(file, check), category)
        }.toList()

    private fun newWork(): File {
        check(root.mkdirs() || root.isDirectory)
        return File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
    }

    private fun deleteWork(file: File) {
        require(file.canonicalFile.parentFile == root.canonicalFile)
        file.deleteRecursively()
    }

    override suspend fun cancel() {
        if (mutable.value.phase == BackupPhase.RESTORING) return
        active?.cancelAndJoin()
        estimateJob?.cancelAndJoin()
        pending?.let(::deleteWork); pending = null; manifest = null
        mutable.update { it.copy(phase = BackupPhase.IDLE, preview = null, retryExportAvailable = false) }
    }

    override fun dismissResult() {
        if (!mutable.value.busy && !mutable.value.needsRestart) mutable.update { it.copy(phase = BackupPhase.IDLE, error = null) }
    }

    companion object {
        private fun recordKey(record: com.rtbishop.look4sat.core.domain.logbook.QsoRecord) =
            com.rtbishop.look4sat.core.data.repository.stableQsoKey(record)
        private fun freeSpace(directory: File) = (directory.usableSpace - 20L * 1024 * 1024).coerceAtLeast(0)

        internal fun copyFile(source: File, target: File, checkCancelled: () -> Unit) {
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            require(target.canonicalFile != source.canonicalFile)
            source.inputStream().buffered().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    val buffered = java.io.BufferedOutputStream(output)
                    BackupArchive.copy(input, buffered, freeSpace(target.parentFile!!), checkCancelled)
                    buffered.flush()
                    output.fd.sync()
                }
            }
        }

        internal fun recover(store: BackupSnapshotStore) {
            val journal = store.journal
            if (!journal.exists()) return
            if (!File(journal, "ready").exists() || File(journal, "committed").exists()) {
                discardJournal(store); return
            }
            val categories = File(journal, "ready").readText().split(',').map(BackupCategory::valueOf).toSet()
            val key = checkNotNull(store.secretStorage.read("rollback")) { "Restore recovery key is missing" }
            val password = try { key.toString(Charsets.UTF_8).toCharArray() } finally { key.fill(0) }
            val root = File(journal, "recovery")
            root.deleteRecursively()
            try {
                BackupArchive.unpack(File(journal, "rollback.l4sbackup"), root, password, freeSpace(journal)) {}
                val snapshot = store.readSnapshot(File(root, "snapshot.json"))
                store.apply(snapshot, BackupCategory.entries.toSet(), RestoreMode.REPLACE, rollback = true)
                if (BackupCategory.RECORDINGS in categories) store.recordingRoots.forEach { (name, directory) ->
                    directory.listFiles().orEmpty().filter(File::isFile).forEach { check(it.delete()) }
                    File(root, "recordings/$name").listFiles().orEmpty().forEach { copyFile(it, File(directory, it.name)) {} }
                }
                discardJournal(store)
            } finally { password.fill('\u0000'); root.deleteRecursively() }
        }

        private fun discardJournal(store: BackupSnapshotStore) {
            val directory = store.journal.canonicalFile
            require(directory.name == "backup-restore-journal" && directory.parentFile == store.contextForRecovery().noBackupFilesDir.canonicalFile)
            check(directory.deleteRecursively() || !directory.exists()) { "Could not clean up restore recovery data" }
            store.secretStorage.delete("rollback")
        }
    }
}
