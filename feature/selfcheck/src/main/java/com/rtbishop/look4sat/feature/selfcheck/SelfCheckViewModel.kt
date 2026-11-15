package com.rtbishop.look4sat.feature.selfcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rtbishop.look4sat.core.domain.maintenance.CheckItem
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SelfCheckUiState(
    val page: String = "summary",
    val detail: CheckItem? = null,
    val withAudio: Boolean = false,
    val error: String? = null
)

internal sealed interface SelfCheckAction {
    data object Start : SelfCheckAction
    data object Cancel : SelfCheckAction
    data class Page(val page: String) : SelfCheckAction
    data class Detail(val item: CheckItem) : SelfCheckAction
    data class Audio(val enabled: Boolean) : SelfCheckAction
    data object Transmit : SelfCheckAction
    data object Stop : SelfCheckAction
    data object Connect : SelfCheckAction
}

internal class SelfCheckViewModel(val container: IMainContainer) : ViewModel() {
    private val mutable = MutableStateFlow(SelfCheckUiState())
    val uiState = mutable.asStateFlow()
    val state = container.radioSelfCheck.state
    fun onAction(action: SelfCheckAction) {
        when (action) {
            SelfCheckAction.Start -> container.radioSelfCheck.start()
            SelfCheckAction.Cancel -> viewModelScope.launch { container.radioSelfCheck.cancel() }
            is SelfCheckAction.Page -> {
                mutable.update { it.copy(page = action.page, detail = null, error = null) }
                if (action.page == "test") viewModelScope.launch { container.radioSelfCheck.refreshTransmitPreview() }
            }
            is SelfCheckAction.Detail -> mutable.update { it.copy(page = "detail", detail = action.item) }
            is SelfCheckAction.Audio -> mutable.update { it.copy(withAudio = action.enabled) }
            SelfCheckAction.Transmit -> container.radioSelfCheck.testTransmit(mutable.value.withAudio)
            SelfCheckAction.Stop -> viewModelScope.launch { container.radioSelfCheck.stopTransmit() }
            SelfCheckAction.Connect -> viewModelScope.launch {
                try { container.radioTrackingService.connectRadios() }
                catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            }
        }
    }

    companion object {
        fun factory(container: IMainContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SelfCheckViewModel(container) as T
        }
    }
}
