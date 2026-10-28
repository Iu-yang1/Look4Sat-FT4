package com.rtbishop.look4sat.core.domain.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class RadioAudioBackend { UNCONFIGURED, ANDROID_DEVICE, ICOM_WLAN }

@Serializable
data class RadioAudioSettings(
    val backend: RadioAudioBackend = RadioAudioBackend.UNCONFIGURED,
    val rxDeviceKey: String = "",
    val txDeviceKey: String = "",
    val host: String = "",
    val controlPort: Int = 50001,
    val networkCat: Boolean = false,
    val txLatencyMillis: Long = 0
)

data class RadioAudioDevice(val key: String, val name: String, val input: Boolean)

object RadioAudioPolicy {
    fun requireTransmit(settings: RadioAudioSettings, automatic: Boolean) {
        check(settings.backend!=RadioAudioBackend.UNCONFIGURED) { "Select the radio audio connection in CAT settings" }
        require(settings.txLatencyMillis in -500..1000) { "Audio timing correction must be between -500 and 1000 ms" }
        if(settings.backend==RadioAudioBackend.ANDROID_DEVICE) check(settings.txDeviceKey.isNotBlank()) { "Select a radio audio output" }
    }
    fun requireRoute(expected: String, actual: String?) {
        check(expected.isNotBlank() && expected==actual) { "Radio audio route is unavailable or changed" }
    }
    fun requireAutomaticReady(state: RadioAudioState) {
        check(state.transmittingReady && state.linkSelfCheckPassed && state.error==null) { "Radio audio link self-check has not passed" }
    }
}

enum class RadioAudioConnectionPhase { DISCONNECTED, CONNECTING, AUTHENTICATING, NEGOTIATING, READY, RECONNECTING, FAILED }
data class RadioAudioState(
    val receivingReady: Boolean = false,
    val transmittingReady: Boolean = false,
    val route: String = "",
    val generation: Long = 0,
    val error: String? = null,
    val discontinuities: Long = 0,
    val latencyMillis: Long? = null,
    val jitterMillis: Long? = null,
    val linkSelfCheckPassed: Boolean = false,
    val endToEndTimingVerified: Boolean = false,
    val phase: RadioAudioConnectionPhase = RadioAudioConnectionPhase.DISCONNECTED
)

/** Audio connections are independent of the radio's CAT transport. */
interface IRadioAudioSession {
    val state: StateFlow<RadioAudioState>
    fun devices(): List<RadioAudioDevice>
    suspend fun prepare(receive: Boolean = true)
    fun receive(): Flow<TimestampedAudioChunk>
    suspend fun prepareTransmit(automatic: Boolean)
    suspend fun transmit(samples: FloatArray, sampleRate: Int)
    suspend fun stopTransmit()
    suspend fun close()
    fun credentialsConfigured(): Boolean
    fun saveCredentials(username: String, password: String, model: String? = null, config: RadioAudioSettings? = null)
    suspend fun discover(): List<String>
}
