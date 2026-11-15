package com.rtbishop.look4sat.core.domain.maintenance

import kotlinx.coroutines.flow.StateFlow

enum class CheckStatus { NOT_CHECKED, RUNNING, PASSED, ATTENTION, FAILED, UNSUPPORTED }
enum class CheckItem { CAT, FREQUENCY, PTT, RECEIVE, OUTPUT, CLOCK, CALIBRATION }
enum class CheckAction { NONE, CONNECT, AUDIO, PERMISSION, CLOCK }

data class CheckResult(
    val item: CheckItem,
    val status: CheckStatus = CheckStatus.NOT_CHECKED,
    val detail: String = "",
    val action: CheckAction = CheckAction.NONE
)

data class RadioProbe(
    val connected: Boolean,
    val frequencyHz: Long? = null,
    val mode: String? = null,
    val pttSupported: Boolean = false,
    val transmitting: Boolean? = null,
    val error: String? = null,
    val txFrequencyHz: Long? = null,
    val txMode: String? = null
)

data class ReceiveMeasurement(
    val sampleRate: Int = 0,
    val samples: Long = 0,
    val rmsDbfs: Double = -120.0,
    val peakDbfs: Double = -120.0,
    val clippedSamples: Long = 0,
    val gaps: Long = 0,
    val source: String = ""
)

data class SelfCheckState(
    val running: Boolean = false,
    val transmitting: Boolean = false,
    val preparing: Boolean = false,
    val stopRequired: Boolean = false,
    val checkedAtMillis: Long = 0,
    val stale: Boolean = false,
    val results: List<CheckResult> = CheckItem.entries.map(::CheckResult),
    val receive: ReceiveMeasurement = ReceiveMeasurement(),
    val testResult: CheckStatus = CheckStatus.NOT_CHECKED,
    val testFrequencyHz: Long? = null,
    val testMode: String? = null,
    val error: String? = null
)

interface IRadioSelfCheckService {
    val state: StateFlow<SelfCheckState>
    fun start()
    suspend fun cancel()
    fun testTransmit(withAudio: Boolean)
    suspend fun refreshTransmitPreview()
    suspend fun stopTransmit()
}
