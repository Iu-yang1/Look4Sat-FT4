package com.rtbishop.look4sat.core.domain.maintenance

import kotlinx.coroutines.flow.StateFlow

enum class BackupCategory { SETTINGS, SATELLITES, LOGBOOK, RECORDINGS, ASTRONOMY, CREDENTIALS }
enum class RestoreMode { MERGE, REPLACE }
enum class BackupPhase { IDLE, PREPARING, EXPORTING, READING, PREVIEW, RESTORING, COMPLETE, FAILED }

data class BackupOptions(
    val categories: Set<BackupCategory> = setOf(
        BackupCategory.SETTINGS, BackupCategory.SATELLITES, BackupCategory.LOGBOOK
    )
)

data class BackupContents(
    val createdAtMillis: Long,
    val appVersion: String,
    val counts: Map<BackupCategory, Long>,
    val bytes: Long,
    val encrypted: Boolean,
    val added: Map<BackupCategory, Long> = emptyMap(),
    val changed: Map<BackupCategory, Long> = emptyMap(),
    val removed: Map<BackupCategory, Long> = emptyMap()
)

data class BackupState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val preview: BackupContents? = null,
    val lastBackupMillis: Long = 0,
    val lastBackupName: String = "",
    val error: String? = null,
    val needsRebinding: Boolean = false,
    val needsRestart: Boolean = false,
    val retryExportAvailable: Boolean = false,
    val estimatedBytes: Long? = null,
    val availableCounts: Map<BackupCategory, Long> = emptyMap()
) {
    val busy get() = phase in setOf(
        BackupPhase.PREPARING, BackupPhase.EXPORTING, BackupPhase.READING, BackupPhase.RESTORING
    )
}

interface IBackupRepository {
    val state: StateFlow<BackupState>
    /** Password ownership transfers to the repository; every operation clears it. */
    fun create(destinationUri: String, options: BackupOptions, password: CharArray)
    fun estimate(options: BackupOptions)
    fun retryExport(destinationUri: String)
    fun inspect(sourceUri: String, password: CharArray)
    fun preview(categories: Set<BackupCategory>, mode: RestoreMode)
    fun restore(categories: Set<BackupCategory>, mode: RestoreMode)
    suspend fun cancel()
    fun dismissResult()
}
