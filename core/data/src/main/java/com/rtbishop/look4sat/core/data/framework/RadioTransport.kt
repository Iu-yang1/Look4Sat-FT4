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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RadioTransport {
    val isConnected: Boolean
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun write(bytes: ByteArray): Boolean
    suspend fun readAvailable(maxBytes: Int): ByteArray
}

class BluetoothSppRadioTransport(
    private val manager: BluetoothManager,
    private val address: String
) : RadioTransport {
    private var socket: android.bluetooth.BluetoothSocket? = null
    override val isConnected: Boolean get() = socket?.isConnected == true

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val device = manager.adapter.getRemoteDevice(address)
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        }.isSuccess
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    override suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching { socket?.outputStream?.apply { write(bytes); flush() } ?: error("SPP disconnected") }.isSuccess
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = socket?.inputStream ?: return@withContext ByteArray(0)
        val count = minOf(input.available(), maxBytes)
        if (count <= 0) ByteArray(0) else ByteArray(count).also { input.read(it) }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
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
        runCatching { socket?.getOutputStream()?.apply { write(bytes); flush() } ?: error("TCP disconnected") }.isSuccess
    }

    override suspend fun readAvailable(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val input = socket?.getInputStream() ?: return@withContext ByteArray(0)
        val count = minOf(input.available(), maxBytes)
        if (count <= 0) ByteArray(0) else ByteArray(count).also { input.read(it) }
    }
}

/** 无额外依赖的 CDC-ACM/bulk USB CAT 传输；USB 音频设备由音频系统独立选择。 */
class UsbSerialRadioTransport(
    context: Context,
    private val deviceId: Int,
    private val baudRate: Int
) : RadioTransport {
    private val manager = context.applicationContext.getSystemService(UsbManager::class.java)
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    override val isConnected: Boolean get() = connection != null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return@withContext false
        if (!manager.hasPermission(device)) return@withContext false
        val candidate = (0 until device.interfaceCount).asSequence().map(device::getInterface).firstOrNull { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).count { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
        } ?: return@withContext false
        val opened = manager.openDevice(device) ?: return@withContext false
        if (!opened.claimInterface(candidate, true)) {
            opened.close()
            return@withContext false
        }
        usbInterface = candidate
        connection = opened
        input = (0 until candidate.endpointCount).map(candidate::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        output = (0 until candidate.endpointCount).map(candidate::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        if (input == null || output == null) {
            disconnect()
            return@withContext false
        }
        if (candidate.interfaceClass == UsbConstants.USB_CLASS_COMM) configureCdc(opened, candidate)
        true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        usbInterface?.let { intf -> runCatching { connection?.releaseInterface(intf) } }
        connection?.close()
        connection = null
        usbInterface = null
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

    private fun configureCdc(connection: UsbDeviceConnection, intf: UsbInterface) {
        val lineCoding = byteArrayOf(
            baudRate.toByte(), (baudRate shr 8).toByte(), (baudRate shr 16).toByte(), (baudRate shr 24).toByte(),
            0, 0, 8
        )
        connection.controlTransfer(0x21, 0x20, 0, intf.id, lineCoding, lineCoding.size, IO_TIMEOUT_MILLIS)
        connection.controlTransfer(0x21, 0x22, 3, intf.id, null, 0, IO_TIMEOUT_MILLIS)
    }

    private companion object {
        const val IO_TIMEOUT_MILLIS = 1_000
        const val READ_POLL_MILLIS = 20
    }
}

class AndroidRadioTransportFactory(
    private val context: Context,
    private val bluetoothManager: BluetoothManager
) {
    fun create(type: String, address: String, baudRate: Int): RadioTransport = when (type) {
        "USB" -> UsbSerialRadioTransport(context, address.toIntOrNull() ?: -1, baudRate)
        "TCP" -> {
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull() ?: 0
            TcpRadioTransport(host, port)
        }
        else -> BluetoothSppRadioTransport(bluetoothManager, address)
    }
}
