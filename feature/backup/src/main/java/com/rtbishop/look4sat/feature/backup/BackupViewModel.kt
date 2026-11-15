package com.rtbishop.look4sat.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rtbishop.look4sat.core.domain.maintenance.*
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class BackupUiState(
    val page: String = "home",
    val categories: Set<BackupCategory> = BackupOptions().categories,
    val restoreCategories: Set<BackupCategory> = emptySet(),
    val mode: RestoreMode = RestoreMode.MERGE,
    val password: String = "",
    val confirmation: String = ""
)

internal sealed interface BackupAction {
    data class Page(val value: String) : BackupAction
    data class Toggle(val category: BackupCategory) : BackupAction
    data class RestoreToggle(val category: BackupCategory) : BackupAction
    data class Password(val value: String) : BackupAction
    data class Confirmation(val value: String) : BackupAction
    data class Mode(val value: RestoreMode) : BackupAction
    data class Create(val uri: String) : BackupAction
    data class RetryExport(val uri: String) : BackupAction
    data class Inspect(val uri: String) : BackupAction
    data object Restore : BackupAction
    data object ClearPassword : BackupAction
}

internal class BackupViewModel(val container: IMainContainer) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val uiState = mutable.asStateFlow()
    val state = container.backupRepository.state

    fun onAction(action: BackupAction) {
        if (action == BackupAction.ClearPassword) {
            mutable.update { it.copy(password = "", confirmation = "") }
            return
        }
        if (state.value.busy || state.value.needsRestart) return
        when (action) {
            is BackupAction.Page -> {
                mutable.update { it.copy(page = action.value, password = "", confirmation = "") }
                container.backupRepository.dismissResult()
                if (action.value == "create") container.backupRepository.estimate(BackupOptions(mutable.value.categories))
            }
            is BackupAction.Toggle -> {
                mutable.update { it.copy(categories = it.categories.toggle(action.category)) }
                container.backupRepository.estimate(BackupOptions(mutable.value.categories))
            }
            is BackupAction.RestoreToggle -> {
                mutable.update { it.copy(restoreCategories = it.restoreCategories.toggle(action.category)) }
                if (mutable.value.restoreCategories.isNotEmpty()) container.backupRepository.preview(
                    mutable.value.restoreCategories, mutable.value.mode)
            }
            is BackupAction.Password -> mutable.update { it.copy(password = action.value) }
            is BackupAction.Confirmation -> mutable.update { it.copy(confirmation = action.value) }
            is BackupAction.Mode -> {
                mutable.update { it.copy(mode = action.value) }
                if (mutable.value.restoreCategories.isNotEmpty()) container.backupRepository.preview(
                    mutable.value.restoreCategories, action.value)
            }
            is BackupAction.Create -> {
                container.backupRepository.create(action.uri, BackupOptions(mutable.value.categories),
                    mutable.value.password.toCharArray())
                mutable.update { it.copy(password = "", confirmation = "") }
            }
            is BackupAction.RetryExport -> container.backupRepository.retryExport(action.uri)
            is BackupAction.Inspect -> {
                container.backupRepository.inspect(action.uri, mutable.value.password.toCharArray())
                mutable.update { it.copy(password = "", confirmation = "", restoreCategories = emptySet(), mode = RestoreMode.MERGE) }
            }
            BackupAction.Restore -> if (mutable.value.restoreCategories.isNotEmpty())
                container.backupRepository.restore(mutable.value.restoreCategories, mutable.value.mode)
            BackupAction.ClearPassword -> Unit
        }
    }

    fun initializePreview(categories: Set<BackupCategory>) {
        if (mutable.value.restoreCategories.isEmpty()) mutable.update { it.copy(restoreCategories = categories) }
    }

    companion object {
        fun factory(container: IMainContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(container) as T
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
