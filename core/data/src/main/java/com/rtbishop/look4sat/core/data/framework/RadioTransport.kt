/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface RadioTransport {
    val isConnected: Boolean
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun write(bytes: ByteArray): Boolean
    suspend fun readAvailable(maxBytes: Int): ByteArray
}

class BluetoothSppRadioTransport(
    private val manager: BluetoothManager?,
    private val address: String
) : RadioTransport {
    private var socket: android.bluetooth.BluetoothSocket? = null
    override val isConnected: Boolean get() = socket?.isConnected == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val adapter = manager?.adapter ?: return@withContext false
        val candidate = adapter.getRemoteDevice(address)
            .createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        socket = candidate
        try {
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                runInterruptible { candidate.connect() }
                true
            } == true
            if (!connected) {
                runCatching { candidate.close() }
                socket = null
            }
            connected
        } catch (cancelled: CancellationException) {
            runCatching { candidate.close() }
            socket = null
            throw cancelled
        } catch (_: Exception) {
            runCatching { candidate.close() }
            socket = null
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext false
        try {
            val written = withTimeoutOrNull(IO_TIMEOUT_MILLIS) {
                runInterruptible { current.outputStream.apply { write(bytes); flush() } }
                true
            } == true
            if (!written) {
                runCatching { current.close() }
                socket = null
            }
            written
        } catch (cancelled: CancellationException) {
            runCatching { current.close() }
            socket = null
            throw cancelled
        } catch (_: Exception) {
            runCatching { current.close() }
            socket = null
            false
        }
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext ByteArray(0)
        val input = current.inputStream
        val count = minOf(input.available(), maxBytes)
        if (count <= 0) return@withContext ByteArray(0)
        try {
            val result = withTimeoutOrNull(IO_TIMEOUT_MILLIS) {
                runInterruptible {
                    val buffer = ByteArray(count)
                    val read = input.read(buffer)
                    if (read > 0) buffer.copyOf(read) else ByteArray(0)
                }
            }
            if (result == null) {
                runCatching { current.close() }
                socket = null
            }
            result ?: ByteArray(0)
        } catch (cancelled: CancellationException) {
            runCatching { current.close() }
            socket = null
            throw cancelled
        }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        const val CONNECT_TIMEOUT_MILLIS = 6_000L
        const val IO_TIMEOUT_MILLIS = 1_000L
    }
}

class TcpRadioTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int = 1_500
) : RadioTransport {
    private var socket: Socket? = null
    override val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            socket = Socket().also { it.connect(InetSocketAddress(host, port), timeoutMillis) }
        }.isSuccess
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext false
        try {
            val written = withTimeoutOrNull(IO_TIMEOUT_MILLIS) {
                runInterruptible { current.getOutputStream().apply { write(bytes); flush() } }
                true
            } == true
            if (!written) {
                runCatching { current.close() }
                socket = null
            }
            written
        } catch (cancelled: CancellationException) {
            runCatching { current.close() }
            socket = null
            throw cancelled
        } catch (_: Exception) {
            runCatching { current.close() }
            socket = null
            false
        }
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext ByteArray(0)
        val input = current.getInputStream()
        val count = minOf(input.available(), maxBytes)
        if (count <= 0) return@withContext ByteArray(0)
        try {
            val result = withTimeoutOrNull(IO_TIMEOUT_MILLIS) {
                runInterruptible {
                    val buffer = ByteArray(count)
                    val read = input.read(buffer)
                    if (read > 0) buffer.copyOf(read) else ByteArray(0)
                }
            }
            if (result == null) {
                runCatching { current.close() }
                socket = null
            }
            result ?: ByteArray(0)
        } catch (cancelled: CancellationException) {
            runCatching { current.close() }
            socket = null
            throw cancelled
        }
    }

    private companion object {
        const val IO_TIMEOUT_MILLIS = 1_000L
    }
}

/** 无额外依赖的标准 CDC-ACM USB CAT 传输；vendor-specific 串口不会被误判为已支持。 */
class UsbSerialRadioTransport(
    context: Context,
    deviceSelector: String,
    private val baudRate: Int
) : RadioTransport {
    private val manager = context.applicationContext.getSystemService(UsbManager::class.java)
    private val deviceId = deviceSelector.substringBefore(':').toIntOrNull() ?: -1
    private val preferredControlId = deviceSelector.split(':').getOrNull(1)?.toIntOrNull()
    private val preferredDataId = deviceSelector.split(':').getOrNull(2)?.toIntOrNull()
    private var connection: UsbDeviceConnection? = null
    private var controlInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    override val isConnected: Boolean
        get() = connection != null && controlInterface != null && dataInterface != null && input != null && output != null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return@withContext false
        if (!manager.hasPermission(device) || baudRate <= 0) return@withContext false
        val opened = manager.openDevice(device) ?: return@withContext false
        val pair = findCdcAcmPair(
            interfaces = (0 until device.interfaceCount).map(device::getInterface),
            associations = parseCdcInterfaceAssociations(opened.rawDescriptors),
            preferredControlId = preferredControlId,
            preferredDataId = preferredDataId
        )
        if (pair == null || !opened.claimInterface(pair.control, true)) {
            opened.close()
            return@withContext false
        }
        if (!opened.claimInterface(pair.data, true)) {
            opened.releaseInterface(pair.control)
            opened.close()
            return@withContext false
        }
        val selectedInput = (0 until pair.data.endpointCount).map(pair.data::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        val selectedOutput = (0 until pair.data.endpointCount).map(pair.data::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        if (selectedInput == null || selectedOutput == null || !configureCdc(opened, pair.control)) {
            opened.releaseInterface(pair.data)
            opened.releaseInterface(pair.control)
            opened.close()
            return@withContext false
        }
        controlInterface = pair.control
        dataInterface = pair.data
        input = selectedInput
        output = selectedOutput
        connection = opened
        true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        dataInterface?.let { intf -> runCatching { connection?.releaseInterface(intf) } }
        controlInterface?.let { intf -> runCatching { connection?.releaseInterface(intf) } }
        connection?.close()
        connection = null
        controlInterface = null
        dataInterface = null
        input = null
        output = null
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = connection ?: return@withContext false
        val endpoint = output ?: return@withContext false
        current.bulkTransfer(endpoint, bytes, bytes.size, IO_TIMEOUT_MILLIS) == bytes.size
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val current = connection ?: return@withContext ByteArray(0)
        val endpoint = input ?: return@withContext ByteArray(0)
        val buffer = ByteArray(maxBytes)
        val count = current.bulkTransfer(endpoint, buffer, buffer.size, READ_POLL_MILLIS)
        if (count > 0) buffer.copyOf(count) else ByteArray(0)
    }

    private fun configureCdc(connection: UsbDeviceConnection, intf: UsbInterface): Boolean {
        val lineCoding = byteArrayOf(
            baudRate.toByte(), (baudRate shr 8).toByte(), (baudRate shr 16).toByte(), (baudRate shr 24).toByte(),
            0, 0, 8
        )
        val lineCodingBytes = connection.controlTransfer(
            CDC_REQUEST_TYPE,
            CDC_SET_LINE_CODING,
            0,
            intf.id,
            lineCoding,
            lineCoding.size,
            IO_TIMEOUT_MILLIS
        )
        if (lineCodingBytes != lineCoding.size) return false
        return connection.controlTransfer(
            CDC_REQUEST_TYPE,
            CDC_SET_CONTROL_LINE_STATE,
            CDC_DTR or CDC_RTS,
            intf.id,
            null,
            0,
            IO_TIMEOUT_MILLIS
        ) == 0
    }

    private companion object {
        const val IO_TIMEOUT_MILLIS = 1_000
        const val READ_POLL_MILLIS = 20
        const val CDC_REQUEST_TYPE = 0x21
        const val CDC_SET_LINE_CODING = 0x20
        const val CDC_SET_CONTROL_LINE_STATE = 0x22
        const val CDC_DTR = 0x01
        const val CDC_RTS = 0x02
    }
}

private data class CdcAcmPair(val control: UsbInterface, val data: UsbInterface)

private fun findCdcAcmPair(
    interfaces: List<UsbInterface>,
    associations: Map<Int, Set<Int>>,
    preferredControlId: Int?,
    preferredDataId: Int?
): CdcAcmPair? {
    val controls = interfaces.filter {
        it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == CDC_ACM_SUBCLASS
    }
    val dataInterfaces = interfaces.filter { intf ->
        intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA &&
            (0 until intf.endpointCount).map(intf::getEndpoint).any {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
            } &&
            (0 until intf.endpointCount).map(intf::getEndpoint).any {
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
            }
    }
    val explicit = controls.flatMap { control ->
        associations[control.id].orEmpty().mapNotNull { dataId ->
            dataInterfaces.firstOrNull { it.id == dataId }?.let { CdcAcmPair(control, it) }
        }
    }
    val pairs = explicit.ifEmpty {
        if (controls.size == 1 && dataInterfaces.size == 1) {
            listOf(CdcAcmPair(controls.single(), dataInterfaces.single()))
        } else {
            emptyList()
        }
    }
    return pairs.firstOrNull { pair ->
        (preferredControlId == null || pair.control.id == preferredControlId) &&
            (preferredDataId == null || pair.data.id == preferredDataId)
    }
}

internal fun parseCdcInterfaceAssociations(descriptors: ByteArray): Map<Int, Set<Int>> {
    val associations = mutableMapOf<Int, MutableSet<Int>>()
    var offset = 0
    while (offset + 1 < descriptors.size) {
        val length = descriptors[offset].toInt() and 0xff
        val type = descriptors[offset + 1].toInt() and 0xff
        if (length < 2 || offset + length > descriptors.size) break
        when {
            type == USB_DESCRIPTOR_TYPE_CS_INTERFACE && length >= 5 &&
                (descriptors[offset + 2].toInt() and 0xff) == CDC_UNION_SUBTYPE -> {
                val master = descriptors[offset + 3].toInt() and 0xff
                val slaves = associations.getOrPut(master) { mutableSetOf() }
                for (index in offset + 4 until offset + length) slaves += descriptors[index].toInt() and 0xff
            }
            type == USB_DESCRIPTOR_TYPE_IAD && length >= 8 &&
                (descriptors[offset + 4].toInt() and 0xff) == UsbConstants.USB_CLASS_COMM &&
                (descriptors[offset + 5].toInt() and 0xff) == CDC_ACM_SUBCLASS -> {
                val first = descriptors[offset + 2].toInt() and 0xff
                val count = descriptors[offset + 3].toInt() and 0xff
                val members = associations.getOrPut(first) { mutableSetOf() }
                for (id in first + 1 until first + count) members += id
            }
        }
        offset += length
    }
    return associations.mapValues { it.value.toSet() }
}

private const val CDC_ACM_SUBCLASS = 0x02
private const val USB_DESCRIPTOR_TYPE_IAD = 0x0b
private const val USB_DESCRIPTOR_TYPE_CS_INTERFACE = 0x24
private const val CDC_UNION_SUBTYPE = 0x06

class AndroidRadioTransportFactory(
    private val context: Context,
    private val bluetoothManager: BluetoothManager?
) {
    fun create(type: String, address: String, baudRate: Int): RadioTransport = when (type) {
        "USB" -> UsbSerialRadioTransport(context, address, baudRate)
        "TCP" -> {
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull() ?: 0
            TcpRadioTransport(host, port)
        }
        else -> BluetoothSppRadioTransport(bluetoothManager, address)
    }
}
