package com.rtbishop.look4sat.core.domain.rotator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatorCodecTest {

    @Test
    fun encodesTextPointCommandsByteForByte() {
        val position = RotatorPosition(12.6, 45.4)

        assertAscii("W013 045\r", RotatorCodec.point(RotatorProtocol.GS232, position))
        assertAscii("AZ13 EL45\r", RotatorCodec.point(RotatorProtocol.EASYCOMM_I, position))
        assertAscii("AZ12.6 EL45.4\r", RotatorCodec.point(RotatorProtocol.EASYCOMM_II, position))
        assertAscii("AZ12.6 EL45.4\r", RotatorCodec.point(RotatorProtocol.EASYCOMM_III, position))
        assertAscii("AZ013EL045\n", RotatorCodec.point(RotatorProtocol.SAEBRTRACK, position))
        assertAscii("P 12.6 45.4\n", RotatorCodec.point(RotatorProtocol.ROTCTLD, position))
        assertAscii(
            "<PST><AZIMUTH>12.6</AZIMUTH><ELEVATION>45.4</ELEVATION></PST>",
            RotatorCodec.point(RotatorProtocol.PST_ROTATOR, position)
        )
        assertAscii("{\"GOTO\":[12.6,45.4]}", RotatorCodec.point(RotatorProtocol.OZ9AAR_URC, position))
    }

    @Test
    fun protocolSpecificPointClampsAreApplied() {
        assertAscii(
            "W350 180\r",
            RotatorCodec.point(RotatorProtocol.GS232, RotatorPosition(-10.0, 999.0))
        )
        assertAscii(
            "AZ350.0 EL0.0\r",
            RotatorCodec.point(RotatorProtocol.EASYCOMM_II, RotatorPosition(-10.0, -5.0))
        )
        assertAscii(
            "P -10.0 0.0\n",
            RotatorCodec.point(RotatorProtocol.ROTCTLD, RotatorPosition(-10.0, -5.0))
        )
    }

    @Test
    fun encodesSpidSetQueryAndStopFrames() {
        assertArrayEquals(
            byteArrayOf(0x57, 0, 3, 7, 3, 1, 0, 4, 0, 5, 1, 0x2F, 0x20),
            RotatorCodec.point(RotatorProtocol.SPID_ROT2PROG, RotatorPosition(13.0, 45.0))
        )
        assertArrayEquals(
            byteArrayOf(0x57, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0x1F, 0x20),
            RotatorCodec.positionQuery(RotatorProtocol.SPID_ROT2PROG)
        )
        assertArrayEquals(
            byteArrayOf(0x57, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0x0F, 0x20),
            RotatorCodec.stop(RotatorProtocol.SPID_ROT2PROG)
        )
    }

    @Test
    fun encodesStopAndQueryCommandsOnlyWhenSupported() {
        assertAscii("S\r", RotatorCodec.stop(RotatorProtocol.GS232))
        assertAscii("SA SE\r", RotatorCodec.stop(RotatorProtocol.EASYCOMM_III))
        assertAscii("S\n", RotatorCodec.stop(RotatorProtocol.ROTCTLD))
        assertAscii("<PST><STOP>1</STOP></PST>", RotatorCodec.stop(RotatorProtocol.PST_ROTATOR))
        assertNull(RotatorCodec.stop(RotatorProtocol.SAEBRTRACK))
        assertNull(RotatorCodec.stop(RotatorProtocol.OZ9AAR_URC))

        assertAscii("C2\r", RotatorCodec.positionQuery(RotatorProtocol.GS232))
        assertAscii("AZ EL\r", RotatorCodec.positionQuery(RotatorProtocol.EASYCOMM_I))
        assertAscii("p\n", RotatorCodec.positionQuery(RotatorProtocol.ROTCTLD))
        assertAscii("{\"POLL\"}", RotatorCodec.positionQuery(RotatorProtocol.OZ9AAR_URC))
        assertNull(RotatorCodec.positionQuery(RotatorProtocol.PST_ROTATOR))
    }

    @Test
    fun parsesGs232EasyCommSaebrtrackAndRotctldReplies() {
        assertPosition(123.0, 45.0, RotatorProtocol.GS232, "noise AZ=123EL=045\r")
        assertPosition(123.0, 45.0, RotatorProtocol.GS232, "+0123+0045\r")
        assertPosition(123.4, 45.6, RotatorProtocol.EASYCOMM_II, "AZ123.4 EL45.6\r")
        assertPosition(123.0, 45.0, RotatorProtocol.SAEBRTRACK, "AZ123EL045\n")
        assertPosition(123.4, 45.6, RotatorProtocol.ROTCTLD, "123.4\n45.6\n")
        assertNull(RotatorCodec.parsePosition(RotatorProtocol.ROTCTLD, "RPRT -1\n".encodeToByteArray()))
    }

    @Test
    fun parsesPstUrcAndSpidReplies() {
        assertPosition(123.4, 45.6, RotatorProtocol.PST_ROTATOR, "status AZ:123.4 EL:45.6 ok")
        assertPosition(123.4, 45.6, RotatorProtocol.OZ9AAR_URC, "{\"AZ\":123.4,\"EL\":45.6}")
        assertPosition(123.4, 45.6, RotatorProtocol.OZ9AAR_URC, "{\"el\":45.6,\"az\":123.4}")

        val spid = byteArrayOf(0x00, 0x57, 0, 4, 8, 3, 1, 0, 4, 0, 6, 1, 0x20)
        val parsed = RotatorCodec.parsePosition(RotatorProtocol.SPID_ROT2PROG, spid)
        assertEquals(123.0, parsed?.azimuthDegrees ?: Double.NaN, 0.0)
        assertEquals(46.0, parsed?.elevationDegrees ?: Double.NaN, 0.0)

        val echoedCommand = byteArrayOf(0x57, 0, 4, 8, 3, 1, 0, 4, 0, 6, 1, 0x2F, 0x20)
        val parsedEcho = RotatorCodec.parsePosition(RotatorProtocol.SPID_ROT2PROG, echoedCommand)
        assertEquals(123.0, parsedEcho?.azimuthDegrees ?: Double.NaN, 0.0)
        assertEquals(46.0, parsedEcho?.elevationDegrees ?: Double.NaN, 0.0)
    }

    @Test
    fun rejectsMalformedReplies() {
        assertNull(RotatorCodec.parsePosition(RotatorProtocol.GS232, "AZ=bad EL=10".encodeToByteArray()))
        assertNull(RotatorCodec.parsePosition(RotatorProtocol.EASYCOMM_I, "AZ123".encodeToByteArray()))
        assertNull(RotatorCodec.parsePosition(RotatorProtocol.SPID_ROT2PROG, byteArrayOf(0x57, 0x00)))
        assertNull(RotatorCodec.parsePosition(RotatorProtocol.CUSTOM_TEMPLATE, "AZ=1 EL=2".encodeToByteArray()))
    }

    @Test
    fun customTemplatesSubstituteValuesAndControlCharacters() {
        assertAscii(
            "G 123.25 45\r\n",
            RotatorCodec.point(
                RotatorProtocol.CUSTOM_TEMPLATE,
                RotatorPosition(123.25, 45.0),
                "G \$AZ \$EL\\r\\n"
            )
        )
        assertAscii("HALT\r", RotatorCodec.stop(RotatorProtocol.CUSTOM_TEMPLATE, "HALT\\r"))
        assertAscii("?\n", RotatorCodec.positionQuery(RotatorProtocol.CUSTOM_TEMPLATE, "?\\n"))
        assertNull(RotatorCodec.stop(RotatorProtocol.CUSTOM_TEMPLATE))
    }

    @Test
    fun rejectsNonFinitePointTargets() {
        val failure = runCatching {
            RotatorCodec.point(RotatorProtocol.GS232, RotatorPosition(Double.NaN, 10.0))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun assertPosition(
        expectedAzimuth: Double,
        expectedElevation: Double,
        protocol: RotatorProtocol,
        reply: String
    ) {
        val position = RotatorCodec.parsePosition(protocol, reply.encodeToByteArray())
        assertEquals(expectedAzimuth, position?.azimuthDegrees ?: Double.NaN, 0.0)
        assertEquals(expectedElevation, position?.elevationDegrees ?: Double.NaN, 0.0)
    }

    private fun assertAscii(expected: String, actual: ByteArray?) {
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), actual)
    }
}
