package com.teledrive.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.ErrorOperation
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.LocalFileStorage
import com.teledrive.preferences.AppTheme
import com.teledrive.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class SettingsUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val syncMetadata: Boolean = true,
    val isClearingCache: Boolean = false,
    val cacheCleared: Boolean = false,
    val cacheUsageBytes: Long = 0L,
    val errorMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
    private val downloadCoordinator: DownloadCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.theme, preferences.syncMetadataEnabled) { theme, syncMetadata -> theme to syncMetadata }
                .collect { (theme, syncMetadata) -> _state.update { it.copy(theme = theme, syncMetadata = syncMetadata) } }
        }
        viewModelScope.launch {
            repository.observeFiles().collect { files ->
                _state.update { it.copy(cacheUsageBytes = files.sumOf { file -> file.sizeBytes?.takeIf { file.localPath != null } ?: 0L }) }
            }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            try {
                preferences.setTheme(theme)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun setMetadataSync(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences.setSyncMetadataEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    fun clearLocalCache() {
        if (_state.value.isClearingCache) return
        viewModelScope.launch {
            _state.update { it.copy(isClearingCache = true, cacheCleared = false, errorMessage = null) }
            try {
                // Stop in-flight writers first so they cannot recreate a copy mid-clear.
                downloadCoordinator.cancelAllNow()
                val cleared = repository.clearAllLocalCopies()
                val bytesFreed = localStorage.clearManagedFiles()
                _state.update {
                    it.copy(
                        isClearingCache = false,
                        cacheCleared = true,
                        cacheUsageBytes = 0L,
                        errorMessage = if (cleared == 0 && bytesFreed == 0L) "There were no offline copies to clear" else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isClearingCache = false) }
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(isClearingCache = false, errorMessage = error.toAppError(ErrorOperation.STORAGE).userMessage) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(cacheCleared = false, errorMessage = null) }

    private fun showError(error: Throwable) {
        _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.SETTINGS).userMessage) }
    }
}
