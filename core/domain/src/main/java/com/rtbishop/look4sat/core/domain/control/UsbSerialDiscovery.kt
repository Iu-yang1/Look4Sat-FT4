package com.rtbishop.look4sat.core.domain.control

/** Pure USB topology shared by device selection and the Android transport. */
enum class SerialUsbDriver(val displayName: String) {
    CDC_ACM("CDC-ACM"), CP210X("CP210x"), FTDI("FTDI"), CH34X("CH34x"), PROLIFIC("PL2303")
}

enum class SerialUsbFlow { NONE, RTS_CTS, DTR_DSR }

data class SerialUsbInterface(
    val id: Int, val number: Int, val deviceClass: Int, val subclass: Int,
    val bulkPair: Boolean
)

data class SerialUsbPort(
    val driver: SerialUsbDriver, val controlId: Int, val dataId: Int, val portIndex: Int
)

fun serialUsbDriver(vendor: Int, product: Int): SerialUsbDriver? = when {
    vendor == 0x10c4 && product in setOf(0xea60, 0xea70, 0xea71) -> SerialUsbDriver.CP210X
    vendor == 0x0403 && product in setOf(0x6001, 0x6010, 0x6011, 0x6014, 0x6015) -> SerialUsbDriver.FTDI
    vendor == 0x1a86 && product in setOf(0x7523, 0x5523) ||
        vendor == 0x4348 && product == 0x5523 -> SerialUsbDriver.CH34X
    isProlificSerialAdapter(vendor, product) -> SerialUsbDriver.PROLIFIC
    else -> null
}

fun serialUsbAssociations(bytes: ByteArray): Map<Int, Set<Int>> {
    val result = mutableMapOf<Int, MutableSet<Int>>()
    val unions = mutableMapOf<Int, MutableSet<Int>>()
    var offset = 0
    while (offset + 1 < bytes.size) {
        fun u(index: Int) = bytes[offset + index].toInt() and 255
        val size = u(0)
        if (size < 2 || offset + size > bytes.size) break
        if (u(1) == 0x24 && size >= 5 && u(2) == 6) {
            for (i in 4 until size) unions.getOrPut(u(3)) { mutableSetOf() }.add(u(i))
        } else if (u(1) == 0x0b && size >= 8 && u(4) == 2 && u(5) == 2) {
            for (id in u(2) + 1 until u(2) + u(3))
                result.getOrPut(u(2)) { mutableSetOf() }.add(id)
        }
        offset += size
    }
    return result + unions
}

fun serialUsbChipName(vendor: Int, product: Int, version: Int): String = when {
    vendor == 0x10c4 && product == 0xea70 -> "CP2105"
    vendor == 0x10c4 && product == 0xea71 -> "CP2108"
    vendor == 0x10c4 && product == 0xea60 -> "CP210x"
    vendor == 0x0403 -> when (product) {
        0x6010 -> if (version >= 0x0700) "FT2232H" else "FT2232"
        0x6011 -> "FT4232H"
        0x6014 -> "FT232H"
        0x6015 -> "FT-X"
        else -> "FT232"
    }
    vendor in setOf(0x1a86, 0x4348) && product == 0x5523 -> "CH341"
    vendor in setOf(0x1a86, 0x4348) && product == 0x7523 -> "CH340"
    isProlificSerialAdapter(vendor, product) -> if (product == 0x2303) "PL2303" else "PL2303-HXN"
    else -> "%04X:%04X".format(vendor, product)
}

fun discoverSerialUsbPorts(
    vendor: Int, product: Int, interfaces: List<SerialUsbInterface>,
    descriptors: ByteArray = byteArrayOf(), override: SerialUsbDriver? = null
): List<SerialUsbPort> {
    val result = mutableListOf<SerialUsbPort>()
    val associations = serialUsbAssociations(descriptors)
    if (override == null || override == SerialUsbDriver.CDC_ACM) {
        val controls = interfaces.filter { it.deviceClass == 2 && it.subclass == 2 }
        val data = interfaces.filter { it.deviceClass == 10 && it.bulkPair }
        val used = mutableSetOf<Int>()
        for (control in controls) {
            val explicit = associations[control.id]
            val candidates = if (explicit != null) data.filter { it.id in explicit } else
                data.filter { it.id == control.id + 1 }.ifEmpty {
                    if (controls.size == 1 && data.size == 1) data else emptyList()
                }
            for (intf in candidates) if (used.add(intf.id)) {
                result += SerialUsbPort(SerialUsbDriver.CDC_ACM, control.id, intf.id, result.size)
            }
        }
        // Manually selected CDC firmware can expose a single vendor-class bulk interface.
        if (override == SerialUsbDriver.CDC_ACM && result.isEmpty()) {
            interfaces.filter { it.bulkPair && it.deviceClass != 1 }.forEach {
                result += SerialUsbPort(override, it.id, it.id, result.size)
            }
        }
    }
    val driver = override ?: serialUsbDriver(vendor, product)
    if (driver != null && driver != SerialUsbDriver.CDC_ACM) {
        val occupied = result.flatMap { listOf(it.controlId, it.dataId) }.toSet()
        val bulk = interfaces.filter {
            it.bulkPair && it.deviceClass != 1 && it.id !in occupied &&
                (override != null || it.deviceClass == 255)
        }
        // The CH340/341 register protocol is a single-UART protocol, not CH343/CH344.
        val ports = if (driver == SerialUsbDriver.CH34X) bulk.takeLast(1) else bulk
        ports.forEach {
            result += SerialUsbPort(driver, it.id, it.id,
                if (driver == SerialUsbDriver.CH34X) 0 else it.number)
        }
    }
    return result.distinct()
}

fun serialUsbSupportsFlow(driver: SerialUsbDriver, flow: SerialUsbFlow): Boolean =
    flow == SerialUsbFlow.NONE || when (driver) {
        SerialUsbDriver.CP210X, SerialUsbDriver.FTDI -> true
        SerialUsbDriver.CH34X, SerialUsbDriver.PROLIFIC -> flow == SerialUsbFlow.RTS_CTS
        SerialUsbDriver.CDC_ACM -> false // CDC ACM has no standard hardware-flow-control request.
    }
