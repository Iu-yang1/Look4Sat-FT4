package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*

/** One source and one subscription shared by every decoder; never opens a second microphone. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class RoutedAudioHub(
    private val local: IAudioHub,
    private val session: IRadioAudioSession,
    private val settings: ISettingsRepo,
    scope: CoroutineScope
) : IAudioHub {
    // An exhausted receive attempt must not permanently kill the sharing coroutine.
    // Error state remains visible; stop/start creates a new attempt with a fresh generation.
    private val network=session.receive().catch { error ->
        if(error !is Exception) throw error
        awaitCancellation()
    }.shareIn(scope,SharingStarted.WhileSubscribed(0),0)
    override val state: StateFlow<AudioHubState> = combine(local.state,session.state,settings.radioControlSettings) { mic,radio,config ->
        if(config.radioAudio.backend!=RadioAudioBackend.ICOM_WLAN) mic
        else if(radio.error!=null) AudioHubState.Failed(checkNotNull(radio.error))
        else if(radio.receivingReady) AudioHubState.Capturing(48000,AudioSampleFormat.PCM_16,emptySet(),deviceName=radio.route)
        else AudioHubState.Idle
    }.stateIn(scope,SharingStarted.Eagerly,AudioHubState.Idle)
    override val inputDevices=local.inputDevices
    override fun audioFlow(consumer: AudioConsumer,delivery: AudioDelivery): Flow<TimestampedAudioChunk> =
        settings.radioControlSettings.map { it.radioAudio }.distinctUntilChanged().flatMapLatest { config ->
            if(config.backend==RadioAudioBackend.ICOM_WLAN) network
            else flow {
                if(config.backend==RadioAudioBackend.ANDROID_DEVICE) {
                    check(config.rxDeviceKey.isNotBlank()) { "Select the radio audio input" }
                    session.prepare(true)
                }
                emitAll(local.audioFlow(consumer,delivery))
            }
        }
    override suspend fun selectInputDevice(deviceKey: String?)=local.selectInputDevice(deviceKey)
    override suspend fun stopAll() { local.stopAll();session.close() }
}
