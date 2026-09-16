/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.util.Log
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Icom IC-705/IC-9700/IC-910 CI-V controller over Bluetooth, USB serial, or TCP.
 *
 * The IC-705 emits broadcast frames continuously (band scope, UTC, signal
 * level, …).  A reply to any command we send may therefore be buried in
 * that noise. All response reads drain up to the model-specific timeout and
 * scan the entire accumulated buffer for the frame we expect rather than assuming
 * the very next byte is the response.
 */
class Ic705Controller(
    bluetoothManager: BluetoothManager?,
    private val deviceAddress: String,
    private val civAddress: Byte = IcomCivProtocol.ADDR_IC705,
    private val transport: RadioTransport = BluetoothSppRadioTransport(
        requireNotNull(bluetoothManager),
        deviceAddress
    ),
    private val variant: IcomCivVariant = IcomCivVariant.IC705,
    dedicatedSatelliteMode: Boolean = variant != IcomCivVariant.IC705
) : IRadioController {

    private val tag = when (variant) {
        IcomCivVariant.IC705 -> "IC705"
        IcomCivVariant.IC9700 -> "IC9700"
        IcomCivVariant.IC910 -> "IC910"
    }
    private val usesDedicatedSatelliteMode = dedicatedSatelliteMode &&
        (variant == IcomCivVariant.IC9700 || variant == IcomCivVariant.IC910)
    private val usesManualVfoSelection = usesDedicatedSatelliteMode || variant == IcomCivVariant.IC910
    private val ioMutex = Mutex()
    private var duplexModeEnabled = false
    private var ctcssModeEnabled = false

    /** Time budget (ms) to wait for a response amid broadcast noise. */
    private val responseTimeoutMs = if (variant == IcomCivVariant.IC910) 1_000L else 500L
    /** Polling interval while draining the input buffer. */
    private val POLL_INTERVAL_MS = 20L
    /** Small pause after writing a command before reading the response. */
    private val WRITE_SETTLE_MS = 50L

    override var isConnected: Boolean = false
        private set

    // ── Connection ──────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        if (deviceAddress.isBlank()) return@withContext false
        try {
            isConnected = transport.connect()
            if (!isConnected) return@withContext false
            // Enter VFO mode — frequency/mode commands return FA if the radio
            // is in memory-channel mode. Safe to send regardless of current state.
            Log.i(tag, "Connected to $deviceAddress — entering VFO mode")
            val vfoCmd = IcomCivProtocol.buildEnterVfoModeCommand()
            Log.d(tag, "CMD enterVfoMode → ${commandHex(vfoCmd)}")
            val ready = ioMutex.withLock { sendAndWaitAck(vfoCmd) } && readFrequencyAndMode() != null
            if (!ready) {
                Log.e(tag, "Connected transport did not return a valid CI-V acknowledgement")
                transport.disconnect()
                isConnected = false
            }
            ready
        } catch (cancelled: CancellationException) {
            runCatching { transport.disconnect() }
            isConnected = false
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Connect error: ${e.message}")
            runCatching { transport.disconnect() }
            isConnected = false
            false
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO + NonCancellable) {
        try {
            if (isConnected) withTimeoutOrNull(PTT_OFF_TIMEOUT_MS) { pttOff() }
            if (isConnected && duplexModeEnabled) {
                withTimeoutOrNull(DUPLEX_OFF_TIMEOUT_MS) { setSplitMode(enabled = false) }
            }
            transport.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Disconnect error: ${e.message}")
            runCatching { transport.disconnect() }
        } finally {
            duplexModeEnabled = false
            ctcssModeEnabled = false
            isConnected = false
            Log.i(tag, "Disconnected from $deviceAddress")
        }
    }

    // ── IRadioController – standard operations ──────────────────────────────

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setFrequency: ${frequencyHz}Hz")
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildSetFreqCommand(frequencyHz)
            Log.d(tag, "CMD setFreq → ${commandHex(cmd)}")
            sendAndWaitAck(cmd) && reenableIc705ToneLocked()
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!isModeSupported(mode)) {
            Log.w(tag, "setMode: mode '$mode' is not supported by $variant")
            return@withContext false
        }
        val cmd = IcomCivProtocol.buildSetModeCommand(mode) ?: run {
            Log.w(tag, "setMode: unknown mode '$mode'")
            return@withContext false
        }
        Log.d(tag, "setMode: $mode")
        Log.d(tag, "CMD setMode → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setCtcssMode: $enabled")
        val cmd = IcomCivProtocol.buildCtcssModeCommand(enabled)
        Log.d(tag, "CMD ctcssMode → ${commandHex(cmd)}")
        ioMutex.withLock {
            val acknowledged = sendAndWaitAck(cmd)
            if (acknowledged) ctcssModeEnabled = enabled
            acknowledged
        }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setCtcssTone: ${toneHz}Hz")
        val cmd = IcomCivProtocol.buildSetCtcssToneCommand(toneHz)
        Log.d(tag, "CMD ctcssTone → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val frequencyCommand = IcomCivProtocol.buildReadFreqCommand()
            Log.d(tag, "CMD readFreq → ${commandHex(frequencyCommand)}")
            val frequencyPayload = sendAndReadResponse(
                frequencyCommand,
                IcomCivProtocol.CMD_READ_FREQ
            ) ?: return@withLock null
            val frequency = IcomCivProtocol.parseFrequencyPayload(frequencyPayload)
                ?: return@withLock null

            val modeCommand = IcomCivProtocol.buildReadModeCommand()
            Log.d(tag, "CMD readMode → ${commandHex(modeCommand)}")
            val modePayload = sendAndReadResponse(
                modeCommand,
                IcomCivProtocol.CMD_READ_MODE
            ) ?: return@withLock null
            val mode = IcomCivProtocol.parseModePayload(modePayload) ?: return@withLock null
            Log.d(tag, "readFreqMode: ${frequency}Hz, $mode")
            frequency to mode
        }
    }

    override suspend fun pttOn(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = true) }
    }

    override suspend fun pttOff(): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock { setPttAndConfirm(enabled = false) }
    }

    // ── IRadioController – Icom duplex operations ──────────────────────────

    /**
     * CI-V has no standalone operating-band selector. CMD 0x05 changes the
     * selected VFO's band together with its frequency, so preparation is a no-op.
     */
    override suspend fun setBand(frequencyHz: Long): Boolean = frequencyHz > 0L

    /** Select VFO-A (main/RX) or VFO-B (sub/TX). */
    override suspend fun setVfo(vfoA: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = selectVfoCommand(vfoA)
        Log.d(tag, "CMD selectVFO${if (vfoA) "A" else "B"} → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Enable or disable SPLIT mode (TX on sub-VFO while listening on main VFO).
     */
    override suspend fun setSplitMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val configured = if (usesDedicatedSatelliteMode) {
                if (enabled && !setOrdinarySplitLocked(enabled = false)) {
                    Log.w(tag, "Ordinary split OFF was not acknowledged before satellite mode")
                }
                setDedicatedSatelliteModeLocked(enabled)
            } else {
                if (variant != IcomCivVariant.IC705 && !setDedicatedSatelliteModeLocked(enabled = false)) {
                    // Fallback must remain usable even when the optional SAT command is unsupported.
                    Log.w(tag, "Satellite mode OFF was not verified; continuing with ordinary split")
                }
                setOrdinarySplitLocked(enabled)
            }
            if (configured) duplexModeEnabled = enabled
            configured
        }
    }

    /** Configure VFO-A/RX and VFO-B/TX modes without changing the active RX VFO. */
    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean = withContext(Dispatchers.IO) {
        if (rxMode?.let(::isModeSupported) == false || txMode?.let(::isModeSupported) == false) {
            Log.w(tag, "setSplitModes: mode is not supported by $variant: rx=$rxMode tx=$txMode")
            return@withContext false
        }
        if (usesManualVfoSelection) {
            return@withContext setManualVfoModes(rxMode, txMode)
        }
        val rxCommand = rxMode?.let { IcomCivProtocol.buildSetVfoModeCommand(selected = true, it) }
        val txCommand = txMode?.let { IcomCivProtocol.buildSetVfoModeCommand(selected = false, it) }
        if (rxMode != null && rxCommand == null || txMode != null && txCommand == null) {
            Log.w(tag, "setSplitModes: unsupported rx=$rxMode tx=$txMode")
            return@withContext false
        }
        ioMutex.withLock {
            if (!sendAndWaitAck(IcomCivProtocol.buildSelectVfoACommand())) return@withLock false
            if (rxCommand != null && !sendAndWaitAck(rxCommand)) return@withLock false
            if (txCommand != null && !sendAndWaitAck(txCommand)) return@withLock false
            if (rxMode != null && readVfoModeLocked(selected = true) != normalizedMode(rxMode)) {
                return@withLock false
            }
            if (txMode != null && readVfoModeLocked(selected = false) != normalizedMode(txMode)) {
                return@withLock false
            }
            true
        }
    }

    /** Configure TX-side CTCSS as one CI-V transaction and always return to RX. */
    override suspend fun configureTxCtcss(toneHz: Double?): Boolean = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            var rxRestored = false
            val configured = try {
                if (!sendAndWaitAck(selectVfoCommand(vfoA = false))) {
                    false
                } else if (toneHz != null) {
                    val toneCommand = IcomCivProtocol.buildSetCtcssToneCommand(toneHz)
                    val modeCommand = IcomCivProtocol.buildCtcssModeCommand(enabled = true)
                    val acknowledged = sendAndWaitAck(toneCommand) && sendAndWaitAck(modeCommand)
                    if (acknowledged) ctcssModeEnabled = true
                    acknowledged
                } else {
                    val acknowledged = sendAndWaitAck(
                        IcomCivProtocol.buildCtcssModeCommand(enabled = false)
                    )
                    if (acknowledged) ctcssModeEnabled = false
                    acknowledged
                }
            } finally {
                rxRestored = restoreRxVfo()
            }
            configured && rxRestored
        }
    }

    /**
     * Set the selected RX VFO frequency (CMD 0x25 sub 0x00). PTT does not
     * change which VFO is selected, so this remains the RX target while keyed.
     */
    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                sendAndWaitAck(selectVfoCommand(vfoA = true)) &&
                    sendAndWaitAck(IcomCivProtocol.buildSetFreqCommand(frequencyHz))
            }
        }
        Log.d(tag, "setWorkingFrequency (0x25/00): ${frequencyHz}Hz")
        val cmd = IcomCivProtocol.buildSetWorkingFreqCommand(frequencyHz)
        Log.d(tag, "CMD setWorkingFreq → ${commandHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Set TX VFO frequency via CMD 0x25 sub 0x01 (unselected VFO).
     * Sent every tracking cycle in split mode alongside [setWorkingFrequency].
     */
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                var rxRestored = false
                val frequencySet = try {
                    sendAndWaitAck(selectVfoCommand(vfoA = false)) &&
                        sendAndWaitAck(IcomCivProtocol.buildSetFreqCommand(frequencyHz))
                } finally {
                    rxRestored = restoreRxVfo()
                }
                frequencySet && rxRestored
            }
        }
        Log.d(tag, "setTxVfoFrequency (0x25/01): ${frequencyHz}Hz")
        val cmd = IcomCivProtocol.buildSetUnselectedVfoFreqCommand(frequencyHz)
        Log.d(tag, "CMD setTxVfoFreq → ${commandHex(cmd)}")
        ioMutex.withLock {
            if (!sendAndWaitAck(cmd)) return@withLock false
            if (variant != IcomCivVariant.IC705 || !ctcssModeEnabled) return@withLock true

            // IC-705 firmware can clear TONE on the VFO whose frequency changes.
            // The 0x25/01 command targets VFO-B while VFO-A remains selected, so
            // briefly select B to restore its TX tone and always return to A/RX.
            var rxRestored = false
            val toneRestored = try {
                sendAndWaitAck(IcomCivProtocol.buildSelectVfoBCommand()) &&
                    reenableIc705ToneLocked()
            } finally {
                rxRestored = restoreRxVfo()
            }
            toneRestored && rxRestored
        }
    }

    /**
     * Read the frequency of the currently active VFO (CMD 0x25 sub 0x00).
     * Used for tuning detection in split mode.
     */
    override suspend fun readWorkingFrequency(): Long? = withContext(Dispatchers.IO) {
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                if (!sendAndWaitAck(selectVfoCommand(vfoA = true))) return@withLock null
                readFrequencyLocked()
            }
        }
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildReadWorkingFreqCommand()
            Log.d(tag, "CMD readWorkingFreq → ${commandHex(cmd)}")
            val payload = sendAndReadResponse(cmd, IcomCivProtocol.CMD_SELECTED_VFO_FREQ) {
                it.firstOrNull() == IcomCivProtocol.SUB_SELECTED_VFO
            } ?: return@withContext null
            // Response payload: [sub] [5 freq bytes] — CMD byte already stripped by parseResponse
            Log.d(tag, "readWorkingFreq: got ${payload.size} bytes: ${IcomCivProtocol.toHex(payload)}")
            val freq = IcomCivProtocol.parseVfoFrequencyPayload(payload, selected = true)
                ?: return@withContext null
            Log.d(tag, "readWorkingFreq: ${freq}Hz")
            freq
        }
    }

    /**
     * Read the frequency of the inactive/TX VFO (CMD 0x25 sub 0x01).
     * Used for tuning detection in split mode.
     */
    override suspend fun readTxVfoFrequency(): Long? = withContext(Dispatchers.IO) {
        if (usesManualVfoSelection) {
            return@withContext ioMutex.withLock {
                var rxRestored = false
                val frequency = try {
                    if (sendAndWaitAck(selectVfoCommand(vfoA = false))) {
                        readFrequencyLocked()
                    } else {
                        null
                    }
                } finally {
                    rxRestored = restoreRxVfo()
                }
                frequency?.takeIf { rxRestored }
            }
        }
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildReadTxVfoFreqCommand()
            Log.d(tag, "CMD readTxVfoFreq → ${commandHex(cmd)}")
            val payload = sendAndReadResponse(cmd, IcomCivProtocol.CMD_SELECTED_VFO_FREQ) {
                it.firstOrNull() == IcomCivProtocol.SUB_UNSELECTED_VFO
            } ?: return@withContext null
            // Response payload: [sub] [5 freq bytes] — CMD byte already stripped by parseResponse
            Log.d(tag, "readTxVfoFreq: got ${payload.size} bytes: ${IcomCivProtocol.toHex(payload)}")
            val freq = IcomCivProtocol.parseVfoFrequencyPayload(payload, selected = false)
                ?: return@withContext null
            Log.d(tag, "readTxVfoFreq: ${freq}Hz")
            freq
        }
    }

    // ── Internal I/O helpers ────────────────────────────────────────────────

    /**
     * Write [cmd] to the radio and drain the input stream for up to
     * [responseTimeoutMs], looking for an OK/NG acknowledgement frame.
     */
    private suspend fun sendAndWaitAck(cmd: ByteArray): Boolean {
        if (!write(cmd)) return false
        delay(WRITE_SETTLE_MS)
        val buf = drainWithTimeout(responseTimeoutMs) {
            IcomCivProtocol.ackStatus(it, civAddress) != null
        }
        val ok  = IcomCivProtocol.ackStatus(buf, civAddress) == true
        if (!ok) Log.w(tag, "ACK not found in ${buf.size} bytes: ${IcomCivProtocol.toHex(buf)}")
        return ok
    }

    /**
     * Write [cmd] to the radio and drain the input stream for up to
     * [responseTimeoutMs], scanning for a response frame carrying [expectCmd].
     * Returns the payload bytes of that frame, or null on timeout/error.
     */
    private suspend fun sendAndReadResponse(cmd: ByteArray, expectCmd: Byte): ByteArray? {
        return sendAndReadResponse(cmd, expectCmd) { true }
    }

    private suspend fun sendAndReadResponse(
        cmd: ByteArray,
        expectCmd: Byte,
        payloadMatches: (ByteArray) -> Boolean
    ): ByteArray? {
        if (!write(cmd)) return null
        delay(WRITE_SETTLE_MS)
        val buf      = drainWithTimeout(responseTimeoutMs) {
            IcomCivProtocol.parseResponse(it, expectCmd, civAddress, payloadMatches) != null
        }
        val response = IcomCivProtocol.parseResponse(buf, expectCmd, civAddress, payloadMatches)
        if (response == null) {
            Log.w(tag, "No response for cmd 0x${String.format("%02X", expectCmd.toInt() and 0xFF)} " +
                    "in ${buf.size} bytes: ${IcomCivProtocol.toHex(buf)}")
        }
        return response?.payload
    }

    private suspend fun setPttAndConfirm(enabled: Boolean): Boolean {
        val command = IcomCivProtocol.buildPttCommand(enabled)
        Log.d(tag, "CMD PTT ${if (enabled) "ON" else "OFF"} → ${commandHex(command)}")
        if (!sendAndWaitAck(command)) return false
        val response = sendAndReadResponse(
            IcomCivProtocol.buildReadPttCommand(),
            IcomCivProtocol.CMD_TRANSCEIVER_STATUS
        ) { it.firstOrNull() == IcomCivProtocol.SUB_PTT } ?: return false
        val confirmed = IcomCivProtocol.parsePttState(response)
        if (confirmed != enabled) {
            Log.e(tag, "PTT readback mismatch: requested=$enabled read=$confirmed")
            return false
        }
        return true
    }

    private suspend fun readVfoModeLocked(selected: Boolean): String? {
        val payload = sendAndReadResponse(
            IcomCivProtocol.buildReadVfoModeCommand(selected),
            IcomCivProtocol.CMD_SELECTED_VFO_MODE
        ) {
            it.firstOrNull() == if (selected) {
                IcomCivProtocol.SUB_SELECTED_VFO
            } else {
                IcomCivProtocol.SUB_UNSELECTED_VFO
            }
        } ?: return null
        return IcomCivProtocol.parseVfoModePayload(payload, selected)
    }

    private fun normalizedMode(mode: String): String =
        if (mode.equals("AFSK", ignoreCase = true)) "FM" else mode.uppercase(Locale.US)

    private fun isModeSupported(mode: String): Boolean {
        val normalized = normalizedMode(mode)
        return normalized in IcomCivProtocol.MODE_TO_BYTE &&
            (variant != IcomCivVariant.IC910 || normalized in IC910_MODES)
    }

    private fun selectVfoCommand(vfoA: Boolean): ByteArray = if (!usesDedicatedSatelliteMode) {
        if (vfoA) {
            IcomCivProtocol.buildSelectVfoACommand()
        } else {
            IcomCivProtocol.buildSelectVfoBCommand()
        }
    } else {
        if (vfoA) {
            IcomCivProtocol.buildSelectMainCommand()
        } else {
            IcomCivProtocol.buildSelectSubCommand()
        }
    }

    private suspend fun setManualVfoModes(rxMode: String?, txMode: String?): Boolean {
        val rxCommand = rxMode?.let(IcomCivProtocol::buildSetModeCommand)
        val txCommand = txMode?.let(IcomCivProtocol::buildSetModeCommand)
        if (rxMode != null && rxCommand == null || txMode != null && txCommand == null) return false
        return ioMutex.withLock {
            var rxRestored = false
            val successful = try {
                var successful = sendAndWaitAck(selectVfoCommand(vfoA = true))
                if (successful && rxCommand != null) successful = sendAndWaitAck(rxCommand)
                if (successful && rxMode != null) successful = readModeLocked() == normalizedMode(rxMode)
                if (successful) successful = sendAndWaitAck(selectVfoCommand(vfoA = false))
                if (successful && txCommand != null) successful = sendAndWaitAck(txCommand)
                if (successful && txMode != null) successful = readModeLocked() == normalizedMode(txMode)
                successful
            } finally {
                rxRestored = restoreRxVfo()
            }
            successful && rxRestored
        }
    }

    private suspend fun restoreRxVfo(): Boolean = withContext(NonCancellable) {
        sendAndWaitAck(selectVfoCommand(vfoA = true))
    }

    private suspend fun setOrdinarySplitLocked(enabled: Boolean): Boolean {
        val command = IcomCivProtocol.buildSplitModeCommand(enabled)
        Log.d(tag, "CMD split ${if (enabled) "ON" else "OFF"} → ${commandHex(command)}")
        return sendAndWaitAck(command)
    }

    private suspend fun setDedicatedSatelliteModeLocked(enabled: Boolean): Boolean {
        val responseCommand: Byte
        val responseSubcommand: Byte
        val writeCommand: ByteArray
        val readCommand: ByteArray
        if (variant == IcomCivVariant.IC9700) {
            responseCommand = IcomCivProtocol.CMD_MISC_SETTING
            responseSubcommand = IcomCivProtocol.SUB_SATELLITE_MODE
            writeCommand = IcomCivProtocol.buildSatelliteModeCommand(enabled)
            readCommand = IcomCivProtocol.buildReadSatelliteModeCommand()
        } else {
            responseCommand = IcomCivProtocol.CMD_MEMORY_CONTROL
            responseSubcommand = IcomCivProtocol.SUB_IC910_SATELLITE_MODE
            writeCommand = IcomCivProtocol.buildIc910SatelliteModeCommand(enabled)
            readCommand = IcomCivProtocol.buildReadIc910SatelliteModeCommand()
        }
        Log.d(tag, "CMD satellite ${if (enabled) "ON" else "OFF"} → ${commandHex(writeCommand)}")
        if (!sendAndWaitAck(writeCommand)) return false
        val payload = sendAndReadResponse(readCommand, responseCommand) {
            it.firstOrNull() == responseSubcommand
        } ?: return false
        return IcomCivProtocol.parseSatelliteModeState(payload, responseSubcommand) == enabled
    }

    private suspend fun readFrequencyLocked(): Long? {
        val payload = sendAndReadResponse(
            IcomCivProtocol.buildReadFreqCommand(),
            IcomCivProtocol.CMD_READ_FREQ
        ) ?: return null
        return IcomCivProtocol.parseFrequencyPayload(payload)
    }

    private suspend fun readModeLocked(): String? {
        val payload = sendAndReadResponse(
            IcomCivProtocol.buildReadModeCommand(),
            IcomCivProtocol.CMD_READ_MODE
        ) ?: return null
        return IcomCivProtocol.parseModePayload(payload)
    }

    /** Work around IC-705 firmware clearing TONE after a frequency change. */
    private suspend fun reenableIc705ToneLocked(): Boolean {
        if (variant != IcomCivVariant.IC705 || !ctcssModeEnabled) return true
        val command = IcomCivProtocol.buildCtcssModeCommand(enabled = true)
        Log.d(tag, "CMD restore CTCSS after frequency change → ${commandHex(command)}")
        return sendAndWaitAck(command)
    }

    /**
     * Drain whatever bytes the radio has buffered within a [timeoutMs] window.
     * Exits early as soon as a complete CI-V frame addressed to us is present
     * in the buffer (i.e., FE FE E0 A4 … FD), so we don't waste the remaining
     * timeout on responses that already arrived.
     */
    private suspend fun drainWithTimeout(
        timeoutMs: Long,
        responseComplete: (ByteArray) -> Boolean
    ): ByteArray {
        val result   = mutableListOf<Byte>()
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            try {
                val chunk = transport.readAvailable(MAX_READ_CHUNK)
                if (chunk.isNotEmpty()) {
                    result.addAll(chunk.toList())
                    if (responseComplete(result.toByteArray())) break
                } else {
                    delay(POLL_INTERVAL_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Drain error: ${e.message}")
                isConnected = false
                break
            }
        }
        return result.toByteArray()
    }

    private suspend fun write(bytes: ByteArray): Boolean {
        return try {
            transport.write(addressCommand(bytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Write error: ${e.message}")
            isConnected = false
            false
        }
    }

    private fun commandHex(bytes: ByteArray): String =
        IcomCivProtocol.toHex(addressCommand(bytes))

    private fun addressCommand(bytes: ByteArray): ByteArray = bytes.copyOf().also { command ->
        if (command.size >= 4 && command[0] == IcomCivProtocol.PREAMBLE &&
            command[1] == IcomCivProtocol.PREAMBLE
        ) {
            command[2] = civAddress
        }
    }

    private companion object {
        val IC910_MODES = setOf("LSB", "USB", "CW", "FM")
        const val PTT_OFF_TIMEOUT_MS = 1_500L
        const val DUPLEX_OFF_TIMEOUT_MS = 1_500L
        const val MAX_READ_CHUNK = 4_096
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
