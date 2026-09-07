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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Icom IC-705 CI-V controller over Bluetooth SPP.
 *
 * The IC-705 emits broadcast frames continuously (band scope, UTC, signal
 * level, …).  A reply to any command we send may therefore be buried in
 * that noise.  All response reads drain up to [ACK_TIMEOUT_MS] and scan the
 * entire accumulated buffer for the frame we expect rather than assuming
 * the very next byte is the response.
 */
class Ic705Controller(
    bluetoothManager: BluetoothManager?,
    private val deviceAddress: String,
    private val civAddress: Byte = IcomCivProtocol.ADDR_IC705,
    private val transport: RadioTransport = BluetoothSppRadioTransport(
        requireNotNull(bluetoothManager),
        deviceAddress
    )
) : IRadioController {

    private val tag = "IC705"
    private val ioMutex = Mutex()

    /** Time budget (ms) to wait for a response amid broadcast noise. */
    private val ACK_TIMEOUT_MS = 500L
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
            Log.d(tag, "CMD enterVfoMode → ${IcomCivProtocol.toHex(vfoCmd)}")
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

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                if (isConnected) withTimeoutOrNull(PTT_OFF_TIMEOUT_MS) { pttOff() }
                transport.disconnect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(tag, "Disconnect error: ${e.message}")
            } finally {
                isConnected  = false
                Log.i(tag, "Disconnected from $deviceAddress")
            }
        }
    }

    // ── IRadioController – standard operations ──────────────────────────────

    override suspend fun setFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setFrequency: ${frequencyHz}Hz")
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildSetFreqCommand(frequencyHz)
            Log.d(tag, "CMD setFreq → ${IcomCivProtocol.toHex(cmd)}")
            sendAndWaitAck(cmd)
        }
    }

    override suspend fun setMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = IcomCivProtocol.buildSetModeCommand(mode) ?: run {
            Log.w(tag, "setMode: unknown mode '$mode'")
            return@withContext false
        }
        Log.d(tag, "setMode: $mode")
        Log.d(tag, "CMD setMode → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setCtcssMode: $enabled")
        val cmd = IcomCivProtocol.buildCtcssModeCommand(enabled)
        Log.d(tag, "CMD ctcssMode → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setCtcssTone: ${toneHz}Hz")
        val cmd = IcomCivProtocol.buildSetCtcssToneCommand(toneHz)
        Log.d(tag, "CMD ctcssTone → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    override suspend fun readFrequencyAndMode(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val frequencyCommand = IcomCivProtocol.buildReadFreqCommand()
            Log.d(tag, "CMD readFreq → ${IcomCivProtocol.toHex(frequencyCommand)}")
            val frequencyPayload = sendAndReadResponse(
                frequencyCommand,
                IcomCivProtocol.CMD_READ_FREQ
            ) ?: return@withLock null
            val frequency = IcomCivProtocol.parseFrequencyPayload(frequencyPayload)
                ?: return@withLock null

            val modeCommand = IcomCivProtocol.buildReadModeCommand()
            Log.d(tag, "CMD readMode → ${IcomCivProtocol.toHex(modeCommand)}")
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

    // ── IRadioController – IC-705 extended operations ───────────────────────

    /** Select the band for [frequencyHz] via CMD 0x1A sub 0x00 (band stacking register). */
    override suspend fun setBand(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        val cmd = IcomCivProtocol.buildBandSelectCommand(frequencyHz) ?: run {
            Log.w(tag, "setBand: no band code for ${frequencyHz}Hz — skipping")
            return@withContext false
        }
        Log.d(tag, "CMD setBand (${frequencyHz}Hz) → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /** Select VFO-A (main/RX) or VFO-B (sub/TX). */
    override suspend fun setVfo(vfoA: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = if (vfoA) IcomCivProtocol.buildSelectVfoACommand()
                  else      IcomCivProtocol.buildSelectVfoBCommand()
        Log.d(tag, "CMD selectVFO${if (vfoA) "A" else "B"} → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Enable or disable SPLIT mode (TX on sub-VFO while listening on main VFO).
     */
    override suspend fun setSplitMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = IcomCivProtocol.buildSplitModeCommand(enabled)
        Log.d(tag, "CMD split ${if (enabled) "ON" else "OFF"} → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /** Configure VFO-A/RX and VFO-B/TX modes without changing the active RX VFO. */
    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean = withContext(Dispatchers.IO) {
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

    /**
     * Set the frequency of the **currently active** VFO (CMD 0x25 sub 0x00).
     * In split mode the radio automatically switches active VFO on PTT, so
     * always writing to the active VFO is the correct strategy.
     */
    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setWorkingFrequency (0x25/00): ${frequencyHz}Hz")
        val cmd = IcomCivProtocol.buildSetWorkingFreqCommand(frequencyHz)
        Log.d(tag, "CMD setWorkingFreq → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Set TX VFO frequency via CMD 0x25 sub 0x01 (unselected VFO).
     * Sent every tracking cycle in split mode alongside [setWorkingFrequency].
     */
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean = withContext(Dispatchers.IO) {
        Log.d(tag, "setTxVfoFrequency (0x25/01): ${frequencyHz}Hz")
        val cmd = IcomCivProtocol.buildSetUnselectedVfoFreqCommand(frequencyHz)
        Log.d(tag, "CMD setTxVfoFreq → ${IcomCivProtocol.toHex(cmd)}")
        ioMutex.withLock { sendAndWaitAck(cmd) }
    }

    /**
     * Read the frequency of the currently active VFO (CMD 0x25 sub 0x00).
     * Used for tuning detection in split mode.
     */
    override suspend fun readWorkingFrequency(): Long? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildReadWorkingFreqCommand()
            Log.d(tag, "CMD readWorkingFreq → ${IcomCivProtocol.toHex(cmd)}")
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
        ioMutex.withLock {
            val cmd = IcomCivProtocol.buildReadTxVfoFreqCommand()
            Log.d(tag, "CMD readTxVfoFreq → ${IcomCivProtocol.toHex(cmd)}")
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
     * [ACK_TIMEOUT_MS], looking for an OK/NG acknowledgement frame.
     */
    private suspend fun sendAndWaitAck(cmd: ByteArray): Boolean {
        if (!write(cmd)) return false
        delay(WRITE_SETTLE_MS)
        val buf = drainWithTimeout(ACK_TIMEOUT_MS) {
            IcomCivProtocol.ackStatus(it, civAddress) != null
        }
        val ok  = IcomCivProtocol.ackStatus(buf, civAddress) == true
        if (!ok) Log.w(tag, "ACK not found in ${buf.size} bytes: ${IcomCivProtocol.toHex(buf)}")
        return ok
    }

    /**
     * Write [cmd] to the radio and drain the input stream for up to
     * [ACK_TIMEOUT_MS], scanning for a response frame carrying [expectCmd].
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
        val buf      = drainWithTimeout(ACK_TIMEOUT_MS) {
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
        Log.d(tag, "CMD PTT ${if (enabled) "ON" else "OFF"} → ${IcomCivProtocol.toHex(command)}")
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
            val addressed = bytes.copyOf().also { command ->
                if (command.size >= 4 && command[0] == IcomCivProtocol.PREAMBLE &&
                    command[1] == IcomCivProtocol.PREAMBLE
                ) {
                    command[2] = civAddress
                }
            }
            transport.write(addressed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(tag, "Write error: ${e.message}")
            isConnected = false
            false
        }
    }

    private companion object {
        const val PTT_OFF_TIMEOUT_MS = 1_500L
        const val MAX_READ_CHUNK = 4_096
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
