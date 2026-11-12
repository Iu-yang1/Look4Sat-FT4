package com.rtbishop.look4sat.core.data.framework

import android.content.Context
import android.hardware.usb.*
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.control.SerialUsbFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ScriptedUsbConnection::class])
class UsbTransportIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(UsbManager::class.java)
    private lateinit var device: UsbDevice

    @Before fun setup() {
        ScriptedUsbConnection.reset()
        fun endpoint(address: Int) = Shadow.newInstanceOf(UsbEndpoint::class.java).apply {
            ReflectionHelpers.setField(this, "mAddress", address)
            ReflectionHelpers.setField(this, "mAttributes", 2)
            ReflectionHelpers.setField(this, "mMaxPacketSize", 64)
        }
        val serial = Shadow.newInstanceOf(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this, "mId", 0)
            ReflectionHelpers.setField(this, "mClass", 255)
            ReflectionHelpers.setField(this, "mEndpoints", arrayOf(endpoint(0x81), endpoint(2)))
        }
        val audio = Shadow.newInstanceOf(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this, "mId", 1)
            ReflectionHelpers.setField(this, "mClass", 1)
            ReflectionHelpers.setField(this, "mEndpoints", emptyArray<UsbEndpoint>())
        }
        device = Shadow.newInstanceOf(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/002")
            ReflectionHelpers.setField(this, "mVendorId", 0x10c4)
            ReflectionHelpers.setField(this, "mProductId", 0xea60)
            ReflectionHelpers.setField(this, "mInterfaces", arrayOf(serial, audio))
        }
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
    }

    private fun transport() = UsbSerialRadioTransport(context, "${device.deviceId}:0:0:4292:60000", 9600)

    @Test fun firstPermissionGrantConnectsAndAudioInterfaceIsNeverClaimed() = runBlocking {
        shadowOf(manager).addOrUpdateUsbDevice(device, false)
        val radio = transport()
        assertFalse(radio.connect())
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        assertTrue(radio.connect())
        assertTrue(radio.isConnected)
        assertEquals(listOf(0), ScriptedUsbConnection.claims)
        radio.disconnect()
        assertFalse(radio.isConnected)
    }

    @Test fun shortBaudConfigurationClosesWithoutPublishingAConnection() = runBlocking {
        ScriptedUsbConnection.shortBaud = true
        val radio = transport()
        assertFalse(radio.connect())
        assertFalse(radio.isConnected)
        assertTrue(ScriptedUsbConnection.closes > 0)
    }

    @Test fun unplugMakesConnectionFalseAndDropsOldBufferedBytes() = runBlocking {
        val radio = transport()
        assertTrue(radio.connect())
        ScriptedUsbConnection.reads.add(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1), radio.readAvailable(1))
        shadowOf(manager).removeUsbDevice(device)
        assertFalse(radio.isConnected)
        assertTrue(radio.readAvailable(8).isEmpty())
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        assertTrue(radio.connect())
        assertTrue(radio.readAvailable(8).isEmpty())
        radio.disconnect()
    }

    @Test fun failedBulkWriteClosesAndDoesNotReplayPartialCommand() = runBlocking {
        val radio = transport()
        assertTrue(radio.connect())
        ScriptedUsbConnection.failWrites = true
        assertFalse(radio.write(byteArrayOf(0xfe.toByte(), 0xfd.toByte())))
        assertFalse(radio.isConnected)
        assertEquals(1, ScriptedUsbConnection.writeCalls)
    }

    @Test fun hardwareFlowPersistsUntilCloseAndStatusCanBeRead() = runBlocking {
        val radio = transport()
        radio.setUsbFlowControl(SerialUsbFlow.RTS_CTS)
        assertTrue(radio.connect())
        assertEquals(UsbModemStatus(true, true, true, true), radio.readUsbModemStatus())
        radio.disconnect()
        assertTrue(ScriptedUsbConnection.releases.contains(0))
    }

    @Test fun l4sCdcKeepsDtrAssertedAndReleasesItOnlyWhenClosing() = runBlocking {
        val data = device.getInterface(0)
        ReflectionHelpers.setField(data, "mId", 1)
        ReflectionHelpers.setField(data, "mClass", 10)
        val control = Shadow.newInstanceOf(UsbInterface::class.java).apply {
            ReflectionHelpers.setField(this, "mId", 0)
            ReflectionHelpers.setField(this, "mClass", 2)
            ReflectionHelpers.setField(this, "mSubclass", 2)
            ReflectionHelpers.setField(this, "mEndpoints", emptyArray<UsbEndpoint>())
        }
        ReflectionHelpers.setField(device, "mInterfaces", arrayOf(control, data))
        val radio = UsbSerialRadioTransport(context, "${device.deviceId}:0:1:4292:60000", 38400)
        radio.setCdcDtrOnConnect(true)
        assertTrue(radio.connect())
        assertEquals(listOf(1), ScriptedUsbConnection.cdcLines)
        assertTrue(radio.write(byteArrayOf(1)))
        assertEquals(listOf(1), ScriptedUsbConnection.cdcLines)
        radio.disconnect()
        assertEquals(listOf(1, 0), ScriptedUsbConnection.cdcLines)
        assertEquals(listOf(0, 1), ScriptedUsbConnection.claims)
    }

    @Test fun oemManualDriverUsesSelectedProtocolAndDoesNotClaimAudio() = runBlocking {
        ReflectionHelpers.setField(device, "mVendorId", 0x1234)
        ReflectionHelpers.setField(device, "mProductId", 0x5678)
        val radio = UsbSerialRadioTransport(context, "${device.deviceId}:0:0:4660:22136:CP210X", 9600)
        assertTrue(radio.connect())
        assertEquals(listOf(0), ScriptedUsbConnection.claims)
        radio.disconnect()
    }

    @Test fun ch340RejectsUnexpectedControlWriteLengths() = runBlocking {
        ReflectionHelpers.setField(device, "mVendorId", 0x1a86)
        ReflectionHelpers.setField(device, "mProductId", 0x7523)
        ScriptedUsbConnection.unexpectedControlWrite = true
        val radio = UsbSerialRadioTransport(context, "${device.deviceId}:0:0:6790:29987", 9600)
        assertFalse(radio.connect())
        assertFalse(radio.isConnected)
        assertEquals(listOf(0), ScriptedUsbConnection.releases)
        assertEquals(1, ScriptedUsbConnection.closes)
    }

    @Test fun vendorFamiliesConfigureTransferAndReleaseTheSelectedPort() = runBlocking {
        for ((vendor, product) in listOf(0x0403 to 0x6015, 0x1a86 to 0x7523, 0x067b to 0x23a3)) {
            ScriptedUsbConnection.reset()
            ReflectionHelpers.setField(device, "mVendorId", vendor)
            ReflectionHelpers.setField(device, "mProductId", product)
            val radio = UsbSerialRadioTransport(context, "${device.deviceId}:0:0:$vendor:$product", 9600)
            radio.setUsbFlowControl(SerialUsbFlow.RTS_CTS)
            assertTrue("$vendor:$product must configure", radio.connect())
            val payload = byteArrayOf(1, 2, 3)
            ScriptedUsbConnection.reads.add(if (vendor == 0x0403)
                byteArrayOf(0x30, 0) + payload else payload)
            assertArrayEquals(payload, radio.readAvailable(16))
            assertTrue(radio.write(byteArrayOf(4, 5)))
            radio.disconnect()
            assertFalse(radio.isConnected)
            assertEquals(listOf(0), ScriptedUsbConnection.releases)
            assertEquals(1, ScriptedUsbConnection.closes)
        }
    }
}

@Implements(UsbDeviceConnection::class)
class ScriptedUsbConnection {
    companion object {
        var shortBaud = false
        var failWrites = false
        var unexpectedControlWrite = false
        var closes = 0
        var writeCalls = 0
        val reads = ArrayDeque<ByteArray>()
        val claims = mutableListOf<Int>()
        val releases = mutableListOf<Int>()
        val cdcLines = mutableListOf<Int>()
        fun reset() {
            shortBaud = false; failWrites = false; unexpectedControlWrite = false; closes = 0; writeCalls = 0
            reads.clear(); claims.clear(); releases.clear(); cdcLines.clear()
        }
    }
    @Implementation fun getRawDescriptors() = ByteArray(18).also { it[0] = 18; it[1] = 1; it[7] = 64 }
    @Implementation fun claimInterface(intf: UsbInterface, force: Boolean): Boolean { claims += intf.id; return true }
    @Implementation fun releaseInterface(intf: UsbInterface): Boolean { releases += intf.id; return true }
    @Implementation fun close() { closes++ }
    @Implementation fun controlTransfer(type: Int, request: Int, value: Int, index: Int,
        bytes: ByteArray?, length: Int, timeout: Int): Int {
        if (type == 0x21 && request == 0x22) cdcLines += value
        if (request == 0x1e && shortBaud) return 3
        if (request == 0xa1 && unexpectedControlWrite) return 1
        if (type and 0x80 != 0 && bytes != null) bytes.fill(0xf0.toByte())
        return length
    }
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int =
        bulkTransfer(endpoint, buffer, 0, length, timeout)
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        if (endpoint.direction == 0) { writeCalls++; return if (failWrites) -1 else length }
        val bytes = reads.poll() ?: return -1
        val count = minOf(length, bytes.size)
        bytes.copyInto(buffer, offset, 0, count)
        return count
    }
}
