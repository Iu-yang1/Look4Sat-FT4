package com.rtbishop.look4sat.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.audio.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.MainTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="en-w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioAudioDialogTest {
    @get:Rule val compose=createComposeRule()
    private val session=FakeAudio()
    private var saved:RadioAudioSettings?=null
    private fun show() {
        compose.setContent { MainTheme { RadioAudioDialog(RadioAudioSettings(),RadioControlSettings.MODEL_ICOM_IC705,session,{}, { saved=it }) } }
    }
    @Test fun externalAudioSavesIndependentInputAndOutput() {
        show()
        compose.onNodeWithText("External audio (USB / wired / compatible Bluetooth)").performClick()
        compose.onNodeWithText("USB RX").performScrollTo().performClick()
        compose.onNodeWithText("USB TX").performScrollTo().performClick()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle {
            assertEquals(RadioAudioBackend.ANDROID_DEVICE,saved?.backend)
            assertEquals("usb:rx",saved?.rxDeviceKey)
            assertEquals("usb:tx",saved?.txDeviceKey)
        }
    }
    @Test fun wlanCredentialsAreSeparatedFromSavedAudioConfiguration() {
        show()
        compose.onNodeWithText("IC-705 WLAN audio").performClick()
        compose.onNodeWithText("Radio LAN address").performScrollTo().performTextInput("192.168.59.1")
        compose.onNodeWithText("Network username (blank to keep)").performScrollTo().performTextInput("user")
        compose.onNodeWithText("Network password (blank to keep)").performScrollTo().performTextInput("secret")
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle {
            assertEquals(RadioAudioBackend.ICOM_WLAN,saved?.backend)
            assertEquals("192.168.59.1",saved?.host)
            assertEquals(false,saved?.networkCat)
            assertEquals("user" to "secret",session.credentials)
            assertFalse(saved.toString().contains("secret"))
        }
    }
    private class FakeAudio:IRadioAudioSession {
        override val state=MutableStateFlow(RadioAudioState())
        var credentials:Pair<String,String>?=null
        override fun devices()=listOf(RadioAudioDevice("usb:rx","USB RX",true),RadioAudioDevice("usb:tx","USB TX",false))
        override suspend fun prepare(receive:Boolean)=Unit
        override fun receive()=emptyFlow<TimestampedAudioChunk>()
        override suspend fun prepareTransmit(automatic:Boolean)=Unit
        override suspend fun transmit(samples:FloatArray,sampleRate:Int)=Unit
        override suspend fun stopTransmit()=Unit
        override suspend fun close()=Unit
        override fun credentialsConfigured()=credentials!=null
        override fun saveCredentials(username:String,password:String,model:String?,config:RadioAudioSettings?) { credentials=username to password }
        override suspend fun discover()=emptyList<String>()
    }
}
