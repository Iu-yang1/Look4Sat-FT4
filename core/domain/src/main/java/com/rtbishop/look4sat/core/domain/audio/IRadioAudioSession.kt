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
    val networkCat: Boolean = false
)

data class RadioAudioDevice(val key: String, val name: String, val input: Boolean)

object RadioAudioPolicy {
    fun requireTransmit(settings: RadioAudioSettings, automatic: Boolean) {
        check(settings.backend!=RadioAudioBackend.UNCONFIGURED) { "Select the radio audio connection in CAT settings" }
        check(!automatic || settings.backend!=RadioAudioBackend.ICOM_WLAN) {
            "Automatic WLAN transmission awaits hardware timing validation"
        }
        if(settings.backend==RadioAudioBackend.ANDROID_DEVICE) check(settings.txDeviceKey.isNotBlank()) { "Select a radio audio output" }
    }
    fun requireRoute(expected: String, actual: String?) {
        check(expected.isNotBlank() && expected==actual) { "Radio audio route is unavailable or changed" }
    }
}
data class RadioAudioState(
    val receivingReady: Boolean = false,
    val transmittingReady: Boolean = false,
    val route: String = "",
    val generation: Long = 0,
    val error: String? = null,
    val discontinuities: Long = 0,
    val latencyMillis: Long? = null
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
