package com.rtbishop.look4sat.core.data.maintenance

import android.util.Log
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import kotlinx.coroutines.CancellationException

internal class BackupStepException(val step: String, cause: Exception) : Exception(cause.message, cause)

internal inline fun <T> backupStep(step: String, block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: BackupStepException) {
    throw error
} catch (error: Exception) {
    throw BackupStepException(step, error)
}

internal fun IControlDiagnostics.recordBackupFailure(operation: String, error: Exception) {
    val step = (error as? BackupStepException)?.step ?: "operation"
    val failure = if (error is BackupStepException) error.cause ?: error else error
    val stage = "backup/$operation/$step"
    // Exception messages can contain provider URIs or private values. Export only types and frames.
    Log.e("Look4SatBackup", "$stage ${failure.javaClass.name}")
    record(ControlDiagnosticSource.RADIO, stage, failure.javaClass.name, ControlDiagnosticSeverity.ERROR)
    generateSequence(failure) { it.cause }.take(3).forEach { cause ->
        cause.stackTrace.take(8).forEach { frame ->
            Log.e("Look4SatBackup", "$stage ${cause.javaClass.name} at $frame")
            record(ControlDiagnosticSource.RADIO, stage,
                "${cause.javaClass.name} at ${frame.className}.${frame.methodName} " +
                    "(${frame.fileName} line ${frame.lineNumber})", ControlDiagnosticSeverity.ERROR)
        }
    }
}
