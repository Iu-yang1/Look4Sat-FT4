package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.SerialUsbDriver
import com.rtbishop.look4sat.core.domain.control.SerialUsbFlow
import com.rtbishop.look4sat.core.domain.control.serialUsbSupportsFlow

// Register operations adapted from usb-serial-for-android (MIT, license in assets/licenses).
internal typealias UsbControlTransfer = (Int, Int, Int, Int, ByteArray?) -> Int

data class UsbModemStatus(
    val cts: Boolean? = null, val dsr: Boolean? = null,
    val carrier: Boolean? = null, val ring: Boolean? = null
)

internal fun configureUsbFlow(
    driver: SerialUsbDriver, port: Int, flow: SerialUsbFlow, rts: Boolean, dtr: Boolean,
    transfer: UsbControlTransfer
): Boolean {
    if (!serialUsbSupportsFlow(driver, flow)) return false
    return when (driver) {
        SerialUsbDriver.CDC_ACM, SerialUsbDriver.PROLIFIC -> true // PL2303 sets flow during variant initialization.
        SerialUsbDriver.FTDI -> {
            val flag = when (flow) { SerialUsbFlow.NONE -> 0; SerialUsbFlow.RTS_CTS -> 0x100; SerialUsbFlow.DTR_DSR -> 0x200 }
            transfer(0x40, 2, 0, (port + 1) or flag, null) == 0
        }
        SerialUsbDriver.CH34X -> transfer(0x40, 0x9a, 0x2727,
            if (flow == SerialUsbFlow.RTS_CTS) 0x0101 else 0, null) == 0
        SerialUsbDriver.CP210X -> {
            val bytes = ByteArray(16)
            bytes[0] = (if (flow == SerialUsbFlow.DTR_DSR) 0x12 else if (dtr) 1 else 0).toByte()
            if (flow == SerialUsbFlow.RTS_CTS) bytes[0] = (bytes[0].toInt() or 8).toByte()
            bytes[4] = (if (flow == SerialUsbFlow.RTS_CTS) 0x80 else if (rts) 0x40 else 0).toByte()
            transfer(0x41, 0x13, 0, port, bytes) == bytes.size &&
                // SET_MHS must not replace a hardware-managed line.
                transfer(0x41, 7,
                    (if (flow == SerialUsbFlow.DTR_DSR) 0 else if (dtr) 0x101 else 0x100) or
                        (if (flow == SerialUsbFlow.RTS_CTS) 0 else if (rts) 0x202 else 0x200),
                    port, null) == 0
        }
    }
}

internal fun purgeUsbBuffers(driver: SerialUsbDriver, port: Int, transfer: UsbControlTransfer): Boolean? =
    when (driver) {
        SerialUsbDriver.CP210X -> transfer(0x41, 0x12, 0x0f, port, null) == 0
        SerialUsbDriver.FTDI -> transfer(0x40, 0, 1, port + 1, null) == 0 &&
            transfer(0x40, 0, 2, port + 1, null) == 0
        else -> null // No standard purge request; do not send invented register writes.
    }

internal fun readUsbStatus(
    driver: SerialUsbDriver, port: Int, transfer: UsbControlTransfer
): UsbModemStatus? {
    val buffer = ByteArray(if (driver == SerialUsbDriver.CP210X) 1 else 2)
    val count = when (driver) {
        SerialUsbDriver.CP210X -> transfer(0xc1, 8, 0, port, buffer)
        SerialUsbDriver.FTDI -> transfer(0xc0, 5, 0, port + 1, buffer)
        SerialUsbDriver.CH34X -> transfer(0xc0, 0x95, 0x0706, 0, buffer)
        else -> return null
    }
    if (count != buffer.size) return null
    val status = buffer[0].toInt() and 255
    return when (driver) {
        SerialUsbDriver.CP210X -> UsbModemStatus(status and 0x10 != 0, status and 0x20 != 0,
            status and 0x80 != 0, status and 0x40 != 0)
        SerialUsbDriver.FTDI -> UsbModemStatus(status and 0x10 != 0, status and 0x20 != 0,
            status and 0x80 != 0, status and 0x40 != 0)
        SerialUsbDriver.CH34X -> UsbModemStatus(status and 1 == 0, status and 2 == 0,
            status and 8 == 0, status and 4 == 0)
        else -> null
    }
}

internal fun parseUsbStatusNotification(driver: SerialUsbDriver, bytes: ByteArray, count: Int): UsbModemStatus? {
    if (count < 10 || count > bytes.size) return null
    val value = bytes[8].toInt() and 255
    return when (driver) {
        SerialUsbDriver.CDC_ACM -> if (bytes[0] == 0xa1.toByte() && bytes[1] == 0x20.toByte() &&
            bytes[6] == 2.toByte() && bytes[7] == 0.toByte())
            UsbModemStatus(null, value and 2 != 0, value and 1 != 0, value and 8 != 0) else null
        SerialUsbDriver.PROLIFIC -> UsbModemStatus(value and 0x80 != 0, value and 2 != 0,
            value and 1 != 0, value and 8 != 0)
        else -> null
    }
}
