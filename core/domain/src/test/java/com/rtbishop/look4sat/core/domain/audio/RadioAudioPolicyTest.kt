package com.rtbishop.look4sat.core.domain.audio

import org.junit.Assert.*
import org.junit.Test

class RadioAudioPolicyTest {
    @Test fun catConnectionDoesNotMakeAnUnconfiguredAudioRouteUsable() {
        assertTrue(runCatching { RadioAudioPolicy.requireTransmit(RadioAudioSettings(),false) }.isFailure)
    }
    @Test fun wlanAutomaticNeedsRuntimeSelfCheckButNotAnExperimentalFlag() {
        val settings=RadioAudioSettings(RadioAudioBackend.ICOM_WLAN)
        RadioAudioPolicy.requireTransmit(settings,false)
        RadioAudioPolicy.requireTransmit(settings,true)
        assertTrue(runCatching { RadioAudioPolicy.requireAutomaticReady(RadioAudioState(transmittingReady=true)) }.isFailure)
        RadioAudioPolicy.requireAutomaticReady(RadioAudioState(transmittingReady=true,linkSelfCheckPassed=true))
        assertTrue(runCatching { RadioAudioPolicy.requireAutomaticReady(RadioAudioState(transmittingReady=true,linkSelfCheckPassed=true,error="lost")) }.isFailure)
    }
    @Test fun usbOutputMustBeExplicitWhileAutomaticOperationIsPreserved() {
        assertTrue(runCatching { RadioAudioPolicy.requireTransmit(RadioAudioSettings(RadioAudioBackend.ANDROID_DEVICE),false) }.isFailure)
        RadioAudioPolicy.requireTransmit(RadioAudioSettings(RadioAudioBackend.ANDROID_DEVICE,txDeviceKey="usb:tx"),true)
    }
    @Test fun preferredSelectionIsNotAnActualRoute() {
        assertTrue(runCatching { RadioAudioPolicy.requireRoute("usb:tx",null) }.isFailure)
        assertTrue(runCatching { RadioAudioPolicy.requireRoute("usb:tx","speaker") }.isFailure)
        RadioAudioPolicy.requireRoute("usb:tx","usb:tx")
    }
    @Test fun deviceRemovalAndFallbackFailClosed() {
        RadioAudioPolicy.requireRoute("usb:rx","usb:rx")
        assertTrue(runCatching { RadioAudioPolicy.requireRoute("usb:rx","phone-mic") }.isFailure)
        assertTrue(runCatching { RadioAudioPolicy.requireRoute("","") }.isFailure)
    }
}
