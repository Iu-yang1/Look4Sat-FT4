package com.rtbishop.look4sat.core.presentation

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rtbishop.look4sat.core.domain.control.SerialUsbDriver
import com.rtbishop.look4sat.core.domain.control.SerialUsbInterface
import com.rtbishop.look4sat.core.domain.control.SerialUsbPort
import com.rtbishop.look4sat.core.domain.control.discoverSerialUsbPorts

/** Read descriptors without claiming a live radio's interfaces. */
fun discoverAndroidSerialPorts(
    device: UsbDevice, manager: UsbManager? = null, driver: SerialUsbDriver? = null
): List<SerialUsbPort> {
    val descriptors = if (manager?.hasPermission(device) == true) {
        runCatching { manager.openDevice(device)?.let { connection ->
            try { connection.rawDescriptors } finally { connection.close() }
        } }.getOrNull() ?: byteArrayOf()
    } else byteArrayOf()
    val interfaces = (0 until device.interfaceCount).map { index ->
        val intf = device.getInterface(index)
        val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
        SerialUsbInterface(intf.id, index, intf.interfaceClass, intf.interfaceSubclass,
            endpoints.any { it.type == 2 && it.direction == 0x80 } &&
                endpoints.any { it.type == 2 && it.direction == 0 })
    }
    return discoverSerialUsbPorts(device.vendorId, device.productId, interfaces, descriptors, driver)
}
