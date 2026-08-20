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

import java.util.Locale

enum class Ft4AutomationPhase {
    IDLE,
    ARMED,
    CALLING,
    REPLYING,
    REPORT,
    ROGER,
    SIGNOFF,
    COMPLETE,
    ABORTED
}

data class Ft4AutomationSnapshot(
    val phase: Ft4AutomationPhase = Ft4AutomationPhase.IDLE,
    val generation: Long = 0L,
    val mode: String = "",
    val band: String = "",
    val myCall: String = "",
    val targetCall: String = "",
    val currentMessage: String = "",
    val nextMessage: String = "",
    val rxSlotParity: Int? = null,
    val txSlotParity: Int? = null,
    val consecutiveCqCount: Int = 0,
    val abortReason: String = ""
)

data class Ft4AutomaticTxIntent(
    val generation: Long,
    val slotIndex: Long,
    val message: String
)

/** 小型标准 FT4 QSO 状态机；调度、时钟和 PTT 由上层服务负责。 */
class Ft4AutomationController(
    private val maximumConsecutiveCq: Int = 3,
    private val cqBackoffSlots: Int = 2,
    private val maximumDecodeAgeSlots: Long = 1L
) {
    private var mutableSnapshot = Ft4AutomationSnapshot()
    private val seenMessages = LinkedHashSet<String>()
    private val claimedSlots = LinkedHashSet<Long>()
    private var cqBackoffUntilSlot = Long.MIN_VALUE

    val snapshot: Ft4AutomationSnapshot
        get() = mutableSnapshot

    fun arm(
        generation: Long,
        mode: String,
        band: String,
        myCall: String,
        targetCall: String,
        grid: String,
        nextSlotIndex: Long
    ): Ft4AutomationSnapshot {
        val normalizedMyCall = normalizeCall(myCall)
        val normalizedTarget = normalizeCall(targetCall)
        val normalizedGrid = normalizeGrid(grid)
        require(normalizedMyCall.isNotBlank()) { "Operator callsign is required" }
        seenMessages.clear()
        claimedSlots.clear()
        cqBackoffUntilSlot = Long.MIN_VALUE
        mutableSnapshot = Ft4AutomationSnapshot(
            phase = Ft4AutomationPhase.ARMED,
            generation = generation,
            mode = mode,
            band = band,
            myCall = normalizedMyCall,
            targetCall = normalizedTarget,
            nextMessage = if (normalizedTarget.isBlank()) {
                "CQ $normalizedMyCall $normalizedGrid"
            } else {
                "$normalizedTarget $normalizedMyCall $normalizedGrid"
            },
            txSlotParity = Math.floorMod(nextSlotIndex, 2L).toInt()
        )
        return mutableSnapshot
    }

    fun onDecode(
        generation: Long,
        result: Ft4DecodeResult,
        currentSlotIndex: Long
    ): Ft4AutomationSnapshot {
        if (!acceptGeneration(generation) || !isActive()) return mutableSnapshot
        val decodedSlot = Math.floorDiv(result.slotUtcMillis, SLOT_MILLIS)
        if (decodedSlot > currentSlotIndex || currentSlotIndex - decodedSlot > maximumDecodeAgeSlots) {
            return mutableSnapshot
        }
        val messageKey = "$decodedSlot:${result.messageHash}"
        if (!seenMessages.add(messageKey)) return mutableSnapshot

        val source = normalizeCall(result.sourceCall)
        val target = normalizeCall(result.targetCall)
        if (source.isBlank() || source == mutableSnapshot.myCall) return mutableSnapshot
        val activeTarget = mutableSnapshot.targetCall
        val isCq = result.text.trim().uppercase(Locale.US).startsWith("CQ ")
        val addressedToMe = target == mutableSnapshot.myCall
        val fromCurrentTarget = activeTarget.isNotBlank() && source == activeTarget
        if (!isCq && !addressedToMe && !fromCurrentTarget) return mutableSnapshot
        if (activeTarget.isNotBlank() && source != activeTarget) return mutableSnapshot

        val detail = result.gridOrReport.trim().uppercase(Locale.US)
        val next = when {
            isCq && activeTarget.isBlank() -> "$source ${mutableSnapshot.myCall} ${defaultGrid()}"
            detail == "73" -> ""
            detail == "RR73" || detail == "RRR" -> "$source ${mutableSnapshot.myCall} 73"
            REPORT_PATTERN.matches(detail.removePrefix("R")) && detail.startsWith("R") ->
                "$source ${mutableSnapshot.myCall} RR73"
            REPORT_PATTERN.matches(detail) -> "$source ${mutableSnapshot.myCall} R$detail"
            GRID_PATTERN.matches(detail) -> "$source ${mutableSnapshot.myCall} $DEFAULT_REPORT"
            else -> return mutableSnapshot
        }
        val phase = when {
            isCq -> Ft4AutomationPhase.REPLYING
            detail == "73" -> Ft4AutomationPhase.COMPLETE
            detail == "RR73" || detail == "RRR" || detail.startsWith("R+") || detail.startsWith("R-") ->
                Ft4AutomationPhase.SIGNOFF
            REPORT_PATTERN.matches(detail) -> Ft4AutomationPhase.ROGER
            GRID_PATTERN.matches(detail) -> Ft4AutomationPhase.REPORT
            else -> mutableSnapshot.phase
        }
        mutableSnapshot = mutableSnapshot.copy(
            phase = phase,
            targetCall = source,
            nextMessage = next,
            rxSlotParity = Math.floorMod(decodedSlot, 2L).toInt(),
            txSlotParity = 1 - Math.floorMod(decodedSlot, 2L).toInt(),
            consecutiveCqCount = 0
        )
        return mutableSnapshot
    }

    fun claimTransmit(generation: Long, slotIndex: Long): Ft4AutomaticTxIntent? {
        if (!acceptGeneration(generation) || !isActive()) return null
        if (mutableSnapshot.nextMessage.isBlank()) return null
        if (mutableSnapshot.txSlotParity != Math.floorMod(slotIndex, 2L).toInt()) return null
        if (slotIndex < cqBackoffUntilSlot || !claimedSlots.add(slotIndex)) return null

        val message = mutableSnapshot.nextMessage
        val isCq = message.startsWith("CQ ")
        val nextCqCount = if (isCq) mutableSnapshot.consecutiveCqCount + 1 else 0
        if (isCq && nextCqCount >= maximumConsecutiveCq) {
            cqBackoffUntilSlot = slotIndex + cqBackoffSlots * 2L
        }
        mutableSnapshot = mutableSnapshot.copy(
            phase = when {
                isCq -> Ft4AutomationPhase.CALLING
                mutableSnapshot.phase == Ft4AutomationPhase.ARMED -> Ft4AutomationPhase.CALLING
                else -> mutableSnapshot.phase
            },
            currentMessage = message,
            consecutiveCqCount = nextCqCount
        )
        trimHistory(claimedSlots)
        return Ft4AutomaticTxIntent(generation, slotIndex, message)
    }

    fun transmissionFinished(generation: Long, intent: Ft4AutomaticTxIntent, succeeded: Boolean) {
        if (!acceptGeneration(generation) || intent.generation != generation) return
        if (!succeeded) {
            abort(generation, "FT4 transmit failed")
            return
        }
        if (intent.message.endsWith(" 73")) {
            mutableSnapshot = mutableSnapshot.copy(phase = Ft4AutomationPhase.COMPLETE, nextMessage = "")
        }
    }

    fun contextChanged(generation: Long, mode: String, band: String, targetCall: String) {
        if (!acceptGeneration(generation) || !isActive()) return
        if (mode != mutableSnapshot.mode || band != mutableSnapshot.band ||
            normalizeCall(targetCall) != mutableSnapshot.targetCall
        ) {
            abort(generation, "FT4 operating context changed")
        }
    }

    fun stop(generation: Long): Ft4AutomationSnapshot {
        if (acceptGeneration(generation)) {
            mutableSnapshot = mutableSnapshot.copy(
                phase = Ft4AutomationPhase.IDLE,
                currentMessage = "",
                nextMessage = "",
                abortReason = ""
            )
        }
        return mutableSnapshot
    }

    fun abort(generation: Long, reason: String): Ft4AutomationSnapshot {
        if (acceptGeneration(generation)) {
            mutableSnapshot = mutableSnapshot.copy(
                phase = Ft4AutomationPhase.ABORTED,
                nextMessage = "",
                abortReason = reason
            )
        }
        return mutableSnapshot
    }

    private fun acceptGeneration(generation: Long): Boolean = generation == mutableSnapshot.generation

    private fun isActive(): Boolean = mutableSnapshot.phase !in setOf(
        Ft4AutomationPhase.IDLE,
        Ft4AutomationPhase.COMPLETE,
        Ft4AutomationPhase.ABORTED
    )

    private fun defaultGrid(): String = GRID_PATTERN.find(mutableSnapshot.nextMessage)?.value ?: "AA00"

    private fun <T> trimHistory(values: LinkedHashSet<T>) {
        while (values.size > MAX_HISTORY) values.remove(values.first())
    }

    private companion object {
        const val SLOT_MILLIS = 7_500L
        const val DEFAULT_REPORT = "-10"
        const val MAX_HISTORY = 64
        val REPORT_PATTERN = Regex("[+-]\\d{2}")
        val GRID_PATTERN = Regex("[A-R]{2}\\d{2}")

        fun normalizeCall(value: String): String = value.trim().uppercase(Locale.US)
        fun normalizeGrid(value: String): String = value.trim().uppercase(Locale.US).take(4).ifBlank { "AA00" }
    }
}
