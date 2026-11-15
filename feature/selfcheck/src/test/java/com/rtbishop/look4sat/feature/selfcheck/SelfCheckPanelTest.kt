package com.rtbishop.look4sat.feature.selfcheck

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.repository.*
import com.rtbishop.look4sat.core.presentation.MainTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h740dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelfCheckPanelTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val service = Service()
    private inline fun <reified T> stub(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> T::class.java.simpleName
                else -> call(method.name)
            }
        } as T
    private val config = RadioControlSettings(true, RadioControlSettings.MODEL_ICOM_IC705, "", "", "", "", 19200)
    private val configState = MutableStateFlow(config)
    private val radioState = MutableStateFlow(RadioTrackingState())
    private val settings = stub<ISettingsRepo> { if (it == "getRadioControlSettings") configState else error(it) }
    private val radio = stub<IRadioTrackingService> { if (it == "getState") radioState else error(it) }
    private val container = stub<IMainContainer> {
        when (it) {
            "getRadioSelfCheck" -> service
            "getSettingsRepo" -> settings
            "getRadioTrackingService" -> radio
            "getAppScope" -> scope
            else -> error(it)
        }
    }
    @After fun close() { scope.cancel() }
    private fun show(dark: Boolean, scale: Float) {
        compose.setContent { MainTheme(isDarkTheme = dark) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                SelfCheckPanel(container) {}
            }
        } }
    }

    @Test fun lightLargeFontOpeningManualTestDoesNotTransmit() {
        show(false, 1.5f)
        compose.onNodeWithText("Manual transmit test").performScrollTo().performClick()
        compose.onNodeWithText("Include a 1000 Hz audio tone").performScrollTo().assertExists()
        compose.onNodeWithText("Start transmit test").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, service.transmits) }
    }

    @Test fun darkFailedStopKeepsStopAccessibleAndDisablesNewTransmission() {
        show(true, 1f)
        compose.onNodeWithText("Manual transmit test").performScrollTo().performClick()
        compose.runOnIdle { service.state.value = SelfCheckState(stopRequired = true, testResult = CheckStatus.FAILED) }
        compose.onNodeWithText("Start transmit test").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Stop transmission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, service.stops); assertEquals(0, service.transmits) }
    }

    private class Service : IRadioSelfCheckService {
        override val state = MutableStateFlow(SelfCheckState())
        var transmits = 0
        var stops = 0
        override fun start() = Unit
        override suspend fun cancel() = Unit
        override fun testTransmit(withAudio: Boolean) { transmits++ }
        override suspend fun stopTransmit() { stops++ }
        override suspend fun refreshTransmitPreview() = Unit
    }
}
