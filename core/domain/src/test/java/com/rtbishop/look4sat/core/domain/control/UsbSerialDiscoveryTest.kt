package com.rtbishop.look4sat.core.domain.control

import org.junit.Assert.*
import org.junit.Test

class UsbSerialDiscoveryTest {
    private fun intf(id: Int, number: Int = id, cls: Int = 255, sub: Int = 0, bulk: Boolean = true) =
        SerialUsbInterface(id, number, cls, sub, bulk)

    @Test fun unionPairsNonAdjacentPortsAndKeepsOtherCdcFunctions() {
        val ports = discoverSerialUsbPorts(1, 1,
            listOf(intf(0, cls = 2, sub = 2, bulk = false), intf(1, cls = 10),
                intf(2, cls = 2, sub = 2, bulk = false), intf(5, cls = 10)),
            byteArrayOf(5, 0x24, 6, 2, 5))
        assertEquals(listOf(0 to 1, 2 to 5), ports.map { it.controlId to it.dataId })
    }

    @Test fun explicitUnionTakesPrecedenceOverAdjacentData() {
        val ports = discoverSerialUsbPorts(1, 1,
            listOf(intf(0, cls = 2, sub = 2), intf(1, cls = 10), intf(4, cls = 10)),
            byteArrayOf(5, 0x24, 6, 0, 4))
        assertEquals(4, ports.single().dataId)
    }

    @Test fun mixedCdcAndVendorUartsAreBothEnumeratedWithoutAudio() {
        val ports = discoverSerialUsbPorts(0x0403, 0x6011,
            listOf(intf(0, cls = 2, sub = 2), intf(1, cls = 10), intf(2),
                intf(3, cls = 1)))
        assertEquals(listOf(SerialUsbDriver.CDC_ACM, SerialUsbDriver.FTDI), ports.map { it.driver })
        assertEquals(2, ports.last().portIndex)
    }

    @Test fun cp2108AndFt4232ExposeFourIndependentPorts() {
        for ((vendor, product) in listOf(0x10c4 to 0xea71, 0x0403 to 0x6011)) {
            val ports = discoverSerialUsbPorts(vendor, product, (0..3).map { intf(it) })
            assertEquals(listOf(0, 1, 2, 3), ports.map { it.portIndex })
        }
    }

    @Test fun unknownWchChipIsNotInitializedAsCh340() {
        assertNull(serialUsbDriver(0x1a86, 0x55d4))
        assertTrue(discoverSerialUsbPorts(0x1a86, 0x55d4, listOf(intf(0))).isEmpty())
        // New CDC-compatible chips use the standard descriptor path, independent of VID/PID.
        assertEquals(SerialUsbDriver.CDC_ACM, discoverSerialUsbPorts(0x1a86, 0x55d4,
            listOf(intf(0, cls = 2, sub = 2), intf(1, cls = 10))).single().driver)
    }

    @Test fun manualMappingAllowsOemIdsWithoutChangingOtherDevices() {
        assertTrue(discoverSerialUsbPorts(0x1234, 0x5678, listOf(intf(0))).isEmpty())
        assertEquals(SerialUsbDriver.FTDI, discoverSerialUsbPorts(0x1234, 0x5678,
            listOf(intf(0), intf(1, cls = 1)), override = SerialUsbDriver.FTDI).single().driver)
        assertTrue(discoverSerialUsbPorts(0x1234, 0x5678, listOf(intf(0))).isEmpty())
    }

    @Test fun malformedDescriptorsAndAmbiguousCdcAreNotGuessed() {
        assertTrue(serialUsbAssociations(byteArrayOf(9, 0x24, 6, 0, 1)).isEmpty())
        assertTrue(discoverSerialUsbPorts(1, 1,
            listOf(intf(0, cls = 2, sub = 2), intf(3, cls = 2, sub = 2),
                intf(7, cls = 10), intf(9, cls = 10))).isEmpty())
    }

    @Test fun flowCapabilitiesDoNotInventAStandardCdcRequest() {
        assertFalse(serialUsbSupportsFlow(SerialUsbDriver.CDC_ACM, SerialUsbFlow.RTS_CTS))
        assertFalse(serialUsbSupportsFlow(SerialUsbDriver.PROLIFIC, SerialUsbFlow.DTR_DSR))
        assertTrue(serialUsbSupportsFlow(SerialUsbDriver.CP210X, SerialUsbFlow.DTR_DSR))
        assertTrue(serialUsbSupportsFlow(SerialUsbDriver.CH34X, SerialUsbFlow.RTS_CTS))
    }
}
