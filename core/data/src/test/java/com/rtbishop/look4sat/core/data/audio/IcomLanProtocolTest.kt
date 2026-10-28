package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.data.framework.IcomCivProtocol
import org.junit.Assert.*
import org.junit.Test

class IcomLanProtocolTest {
    @Test fun credentialsMatchPinnedWfviewVectors() {
        assertEquals("5c22555c"+"00".repeat(12),IcomLanProtocol.credential("user").hex())
        assertEquals("282b5c44"+"00".repeat(12),IcomLanProtocol.credential("pass").hex())
    }
    @Test fun invalidCredentialsAreNotTruncatedOrTransmitted() {
        for(value in listOf("","12345678901234567","用户名","a\nb")) {
            assertTrue(runCatching { IcomLanProtocol.credential(value) }.isFailure)
        }
    }
    @Test fun audioHeaderMatchesMixedEndianWireLayout() {
        val b=IcomLanProtocol.audio(0x01020304,0x11223344,0x1234,0x5678,byteArrayOf(0,0,0,-128))
        assertEquals("1c000000000034120403020144332211800056780000000400000080",b.hex())
        assertArrayEquals(floatArrayOf(0f,-1f),IcomLanProtocol.pcm(b),0f)
    }
    @Test fun malformedAudioCannotEnterPcmStream() {
        val b=IcomLanProtocol.audio(1,2,1,1,byteArrayOf(0,0))
        b[23]=50
        assertTrue(runCatching { IcomLanProtocol.pcm(b) }.isFailure)
        assertFalse(IcomLanProtocol.valid(byteArrayOf()))
    }
    @Test fun outOfOrderDuplicateAndWrappedPacketsHaveOneTimeline() {
        val queue=IcomAudioReorder()
        assertEquals(1f,queue.offer(65534,floatArrayOf(1f)).single().first.single())
        assertTrue(queue.offer(0,floatArrayOf(3f)).isEmpty())
        assertEquals(listOf(2f,3f),queue.offer(65535,floatArrayOf(2f)).map { it.first.single() })
        assertTrue(queue.offer(65535,floatArrayOf(2f)).isEmpty())
        assertEquals(false,queue.offer(1,floatArrayOf(4f)).single().second)
    }
    @Test fun missingPacketsInvalidateTheNextAudioBlock() {
        val queue=IcomAudioReorder(3)
        queue.offer(1,floatArrayOf(1f))
        queue.offer(3,floatArrayOf(3f));queue.offer(4,floatArrayOf(4f))
        val output=queue.offer(5,floatArrayOf(5f))
        assertEquals(listOf(3f,4f,5f),output.map { it.first.single() })
        assertEquals(listOf(true,false,false),output.map { it.second })
    }
    @Test fun modulationInputUsesTheIc705DocumentedSetting() {
        assertEquals("fefea4e01a050119fd",IcomCivProtocol.buildDataModInput().hex())
        assertEquals("fefea4e01a05011903fd",IcomCivProtocol.buildDataModInput(3).hex())
        assertTrue(runCatching { IcomCivProtocol.buildDataModInput(4) }.isFailure)
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it.toInt() and 255) }
}
