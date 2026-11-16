package com.rtbishop.look4sat.core.data.maintenance

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BackupDiagnosticsTest {
    @Test fun preservesInnermostStageAndOriginalCauseWithoutExportingPrivateMessages() {
        val cause = NullPointerException("private-password-and-certificate-data")
        cause.stackTrace = arrayOf(StackTraceElement("BackupSource", "snapshot", "BackupSource.kt", 42))
        val error = runCatching {
            backupStep("archive") { backupStep("audio-credentials") { throw cause } }
        }.exceptionOrNull() as BackupStepException
        assertSame(cause, error.cause)
        assertEquals(cause.message, error.message)
        val diagnostics = ControlDiagnosticsBuffer()
        diagnostics.recordBackupFailure("preparing", error)
        assertTrue(diagnostics.events.value.all { it.stage == "backup/preparing/audio-credentials" })
        assertTrue(diagnostics.exportText().contains("BackupSource.snapshot (BackupSource.kt line 42)"))
        assertFalse(diagnostics.exportText().contains(cause.message!!))
    }

    @Test fun cancellationIsNotWrappedAsBackupFailure() {
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, runCatching { backupStep("snapshot") { throw cancelled } }.exceptionOrNull())
    }
}
