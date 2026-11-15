package com.rtbishop.look4sat.feature.backup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
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
class BackupPanelTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = Repository()
    private val container = Proxy.newProxyInstance(IMainContainer::class.java.classLoader,
        arrayOf(IMainContainer::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "BackupTestContainer"
            "getBackupRepository" -> repository
            "getAppScope" -> scope
            else -> error(method.name)
        }
    } as IMainContainer
    @After fun close() { scope.cancel() }

    private fun show(dark: Boolean, scale: Float) {
        compose.setContent { MainTheme(isDarkTheme = dark) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                BackupPanel(container) {}
            }
        } }
    }

    @Test fun lightLargeFontCanSelectEncryptedBackupWithoutStartingAnOperation() {
        show(false, 1.5f)
        compose.onNodeWithText("Create backup").performScrollTo().performClick()
        compose.onNodeWithText("Certificates and sign-in details").performScrollTo().performClick()
        compose.onNodeWithText("Choose destination and back up").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Backup password (optional)").performScrollTo().performTextInput("portable")
        compose.onNodeWithText("Confirm password").performScrollTo().performTextInput("portable")
        compose.onNodeWithText("Choose destination and back up").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertTrue(BackupCategory.CREDENTIALS in repository.options.categories)
            assertEquals(0, repository.writes)
        }
    }

    @Test fun darkRestorePreviewCanDeselectAllWithoutRestoring() {
        show(true, 1f)
        compose.onNodeWithText("Restore from file").performClick()
        compose.runOnIdle {
            repository.state.value = BackupState(phase = BackupPhase.PREVIEW, preview = BackupContents(
                1000, "test", mapOf(BackupCategory.LOGBOOK to 2), 1000, false))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Logbook and grid data").performScrollTo().performClick()
        compose.onNodeWithText("Restore selected contents").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, repository.writes) }
    }

    private class Repository : IBackupRepository {
        override val state = MutableStateFlow(BackupState())
        var options = BackupOptions()
        var writes = 0
        override fun create(destinationUri: String, options: BackupOptions, password: CharArray) { writes++ }
        override fun estimate(options: BackupOptions) { this.options = options }
        override fun retryExport(destinationUri: String) { writes++ }
        override fun inspect(sourceUri: String, password: CharArray) = Unit
        override fun preview(categories: Set<BackupCategory>, mode: RestoreMode) = Unit
        override fun restore(categories: Set<BackupCategory>, mode: RestoreMode) { writes++ }
        override suspend fun cancel() = Unit
        override fun dismissResult() = Unit
    }
}
