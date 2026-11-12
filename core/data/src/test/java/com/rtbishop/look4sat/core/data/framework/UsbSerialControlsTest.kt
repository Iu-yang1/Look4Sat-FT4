package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.SerialUsbDriver
import com.rtbishop.look4sat.core.domain.control.SerialUsbFlow
import org.junit.Assert.*
import org.junit.Test

class UsbSerialControlsTest {
    private data class Request(val type: Int, val request: Int, val value: Int, val index: Int, val data: ByteArray?)

    @Test fun cp210FlowUsesHardwareHandshakeWithoutManuallyOverridingManagedLine() {
        for (flow in SerialUsbFlow.entries) {
            val sent = mutableListOf<Request>()
            assertTrue(configureUsbFlow(SerialUsbDriver.CP210X, 1, flow, true, true) { t, r, v, i, b ->
                sent += Request(t, r, v, i, b?.copyOf()); b?.size ?: 0
            })
            assertEquals(1, sent[0].index)
            assertEquals(16, sent[0].data!!.size)
            assertEquals(when (flow) { SerialUsbFlow.NONE -> 0x303
                SerialUsbFlow.RTS_CTS -> 0x101; SerialUsbFlow.DTR_DSR -> 0x202 }, sent[1].value)
        }
    }

    @Test fun shortFlowWriteIsRejectedBeforeModemWrite() {
        var count = 0
        assertFalse(configureUsbFlow(SerialUsbDriver.CP210X, 0, SerialUsbFlow.RTS_CTS, false, false) {
                _, _, _, _, _ -> count++; 15
        })
        assertEquals(1, count)
    }

    @Test fun ftdiFlowPreservesSelectedPortAndCh340UsesItsOwnRegisters() {
        assertTrue(configureUsbFlow(SerialUsbDriver.FTDI, 3, SerialUsbFlow.RTS_CTS, false, false) {
                type, request, value, index, _ ->
            assertEquals(listOf(0x40, 2, 0, 0x104), listOf(type, request, value, index)); 0
        })
        assertTrue(configureUsbFlow(SerialUsbDriver.CH34X, 0, SerialUsbFlow.RTS_CTS, false, false) {
                type, request, value, index, _ ->
            assertEquals(listOf(0x40, 0x9a, 0x2727, 0x0101), listOf(type, request, value, index)); 0
        })
    }

    @Test fun unsupportedFlowDoesNotIssueAnyRequests() {
        assertFalse(configureUsbFlow(SerialUsbDriver.CDC_ACM, 0, SerialUsbFlow.RTS_CTS, false, false) {
                _, _, _, _, _ -> fail("CDC flow has no standard command"); -1
        })
    }

    @Test fun modemReadRequiresCompleteStatusAndDecodesActiveLowCh340() {
        assertNull(readUsbStatus(SerialUsbDriver.FTDI, 0) { _, _, _, _, _ -> 1 })
        assertEquals(UsbModemStatus(true, true, true, true),
            readUsbStatus(SerialUsbDriver.CH34X, 0) { _, _, _, _, b -> b!!.fill(0); b.size })
        assertEquals(UsbModemStatus(true, true, true, true),
            readUsbStatus(SerialUsbDriver.CP210X, 1) { type, request, _, index, b ->
                assertEquals(0xc1, type); assertEquals(8, request); assertEquals(1, index)
                b!![0] = 0xf0.toByte(); 1
            })
    }

    @Test fun cdcNotificationsNeverInventCtsAndRejectTruncation() {
        val notification = byteArrayOf(0xa1.toByte(), 0x20, 0, 0, 0, 0, 2, 0, 3, 0)
        assertEquals(UsbModemStatus(null, true, true, false),
            parseUsbStatusNotification(SerialUsbDriver.CDC_ACM, notification, 10))
        assertNull(parseUsbStatusNotification(SerialUsbDriver.CDC_ACM, notification, 9))
        notification[1] = 0
        assertNull(parseUsbStatusNotification(SerialUsbDriver.CDC_ACM, notification, 10))
    }

    @Test fun purgeTargetsOnlyTheSelectedUart() {
        val requests = mutableListOf<Pair<Int, Int>>()
        assertEquals(true, purgeUsbBuffers(SerialUsbDriver.FTDI, 2) { _, _, value, index, _ ->
            requests += value to index; 0
        })
        assertEquals(listOf(1 to 3, 2 to 3), requests)
        assertNull(purgeUsbBuffers(SerialUsbDriver.CDC_ACM, 0) {
                _, _, _, _, _ -> fail("No CDC purge command"); -1
        })
    }

    @Test fun manualDriverSelectorRemainsCompatibleWithLegacySelectors() {
        assertEquals(SerialUsbDriver.FTDI, parseUsbSerialSelector("1:0:0:123:456:FTDI")!!.driver)
        assertNull(parseUsbSerialSelector("1:0:0:123:456:UNKNOWN"))
        assertNull(parseUsbSerialSelector("1:0:0:123:456")!!.driver)
    }
}
