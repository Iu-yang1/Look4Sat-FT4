/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.ft4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Ft4AutomationControllerTest {
    @Test
    fun standardReportRogerAndSignoffFlow() {
        val controller = Ft4AutomationController()
        controller.arm(4, "USB", "2m", "N0CALL", "K1ABC", "FN42", 10)
        assertEquals("K1ABC N0CALL FN42", controller.claimTransmit(4, 10)?.message)

        controller.onDecode(4, result(10, "K1ABC", "N0CALL", "FN31", 1, snr = -17), 10, receivedAt(10))
        assertEquals(Ft4AutomationPhase.REPORT, controller.snapshot.phase)
        assertEquals("K1ABC N0CALL -17", controller.claimTransmit(4, 11)?.message)
        assertEquals("-17", controller.snapshot.sentReport)

        controller.onDecode(4, result(11, "K1ABC", "N0CALL", "-08", 2, snr = -18), 11, receivedAt(11))
        assertEquals(Ft4AutomationPhase.ROGER, controller.snapshot.phase)
        assertEquals("K1ABC N0CALL R-18", controller.claimTransmit(4, 12)?.message)
        assertEquals("-18", controller.snapshot.sentReport)
        assertEquals("-08", controller.snapshot.receivedReport)

        controller.onDecode(4, result(12, "K1ABC", "N0CALL", "R-10", 3), 12, receivedAt(12))
        assertEquals("K1ABC N0CALL RR73", controller.claimTransmit(4, 13)?.message)
        assertEquals("-18", controller.snapshot.sentReport)

        controller.onDecode(4, result(13, "K1ABC", "N0CALL", "RR73", 4), 13, receivedAt(13))
        val signoff = controller.claimTransmit(4, 14)
        assertEquals("K1ABC N0CALL 73", signoff?.message)
        controller.transmissionFinished(4, requireNotNull(signoff), true)
        assertEquals(Ft4AutomationPhase.COMPLETE, controller.snapshot.phase)
    }

    @Test
    fun eachSlotCanOnlyBeClaimedOnceAndOldGenerationIsIgnored() {
        val controller = Ft4AutomationController()
        controller.arm(8, "USB", "70cm", "N0CALL", "", "FN42", 21)

        assertNotNull(controller.claimTransmit(8, 21))
        assertNull(controller.claimTransmit(8, 21))
        assertNull(controller.claimTransmit(7, 23))
        controller.onDecode(7, result(21, "K1ABC", "CQ", "FN31", 1), 21, receivedAt(21))
        assertEquals("", controller.snapshot.targetCall)
    }

    @Test
    fun cqUsesFiniteBurstAndBackoff() {
        val controller = Ft4AutomationController(maximumConsecutiveCq = 3, cqBackoffSlots = 2)
        controller.arm(2, "USB", "2m", "N0CALL", "", "FN42", 0)

        assertNotNull(controller.claimTransmit(2, 0))
        assertNotNull(controller.claimTransmit(2, 2))
        assertNotNull(controller.claimTransmit(2, 4))
        assertNull(controller.claimTransmit(2, 6))
        assertNotNull(controller.claimTransmit(2, 8))
    }

    @Test
    fun staleDecodeEchoAndContextChangeCannotTransmit() {
        val controller = Ft4AutomationController()
        controller.arm(9, "USB", "2m", "N0CALL", "K1ABC", "FN42", 4)
        controller.onDecode(9, result(1, "K1ABC", "N0CALL", "-10", 1), 4, receivedAt(4))
        assertEquals(Ft4AutomationPhase.ARMED, controller.snapshot.phase)
        controller.onDecode(9, result(4, "N0CALL", "K1ABC", "-10", 2), 4, receivedAt(4))
        assertEquals(Ft4AutomationPhase.ARMED, controller.snapshot.phase)

        controller.contextChanged(9, "FM", "2m", "K1ABC")
        assertEquals(Ft4AutomationPhase.ABORTED, controller.snapshot.phase)
        assertNull(controller.claimTransmit(9, 6))
    }

    @Test
    fun cqReplySelectsOppositeSlotAndDuplicateIsIgnored() {
        val controller = Ft4AutomationController()
        controller.arm(3, "USB", "2m", "N0CALL", "", "FN42", 6)
        val cq = result(7, "K1ABC", "CQ", "FN31", 15)
        controller.onDecode(3, cq, 7, receivedAt(7))

        assertEquals("K1ABC", controller.snapshot.targetCall)
        assertEquals(0, controller.snapshot.txSlotParity)
        assertEquals("K1ABC N0CALL FN42", controller.claimTransmit(3, 8)?.message)
        controller.onDecode(3, cq, 7, receivedAt(7))
        assertEquals(Ft4AutomationPhase.REPLYING, controller.snapshot.phase)
    }

    @Test
    fun targetMessagesAddressedToThirdPartiesNeverAdvanceTheQso() {
        val controller = Ft4AutomationController()
        controller.arm(5, "USB", "2m", "BG5JSU", "BA4SSP", "OM92", 20)
        val before = controller.snapshot

        controller.onDecode(
            5,
            result(20, "BA4SSP", "BD7PZF", "-05", 41, snr = -18),
            20,
            receivedAt(20)
        )
        controller.onDecode(
            5,
            result(20, "BA4SSP", "BD7PZF", "73", 42),
            20,
            receivedAt(20)
        )

        assertEquals(before, controller.snapshot)
    }

    @Test
    fun resultAfterCutoffSkipsImmediateTxSlotAndInvalidatesOldIntent() {
        val controller = Ft4AutomationController()
        controller.arm(6, "USB", "2m", "N0CALL", "K1ABC", "FN42", 30)
        val oldIntent = requireNotNull(controller.claimTransmit(6, 30))
        val lateResultTime = (31L * 7_500L) - 500L

        controller.onDecode(
            6,
            result(30, "K1ABC", "N0CALL", "-04", 50, snr = -16),
            30,
            lateResultTime
        )

        assertEquals(false, controller.isIntentCurrent(oldIntent))
        assertNull(controller.claimTransmit(6, 31))
        assertEquals("K1ABC N0CALL R-16", controller.claimTransmit(6, 33)?.message)
    }

    private fun result(
        slot: Long,
        source: String,
        target: String,
        detail: String,
        hash: Long,
        snr: Int = -10
    ) =
        Ft4DecodeResult(
            slotUtcMillis = slot * 7_500L,
            snr = snr,
            dtSeconds = 0.1f,
            frequencyHz = 1_500f,
            text = if (target == "CQ") "CQ $source $detail" else "$target $source $detail",
            sourceCall = source,
            targetCall = target,
            gridOrReport = detail,
            messageHash = hash
        )

    private fun receivedAt(slot: Long): Long = slot * 7_500L + 6_000L
}
