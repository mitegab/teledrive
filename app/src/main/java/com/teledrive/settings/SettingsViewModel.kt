package com.teledrive.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.preferences.AppTheme
import com.teledrive.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class SettingsUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val syncMetadata: Boolean = true,
    val isClearingCache: Boolean = false,
    val cacheCleared: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val repository: FileIndexRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.theme, preferences.syncMetadataEnabled) { theme, syncMetadata -> theme to syncMetadata }
                .collect { (theme, syncMetadata) -> _state.update { it.copy(theme = theme, syncMetadata = syncMetadata) } }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { runCatching { preferences.setTheme(theme) }.onFailure { showError(it) } }
    }

    fun setMetadataSync(enabled: Boolean) {
        viewModelScope.launch { runCatching { preferences.setSyncMetadataEnabled(enabled) }.onFailure { showError(it) } }
    }

    fun clearLocalCache() {
        if (_state.value.isClearingCache) return
        viewModelScope.launch {
            _state.update { it.copy(isClearingCache = true, cacheCleared = false, errorMessage = null) }
            runCatching {
                repository.observeFiles().first().forEach { file ->
                    file.localPath?.let { path -> File(path).delete() }
                    repository.deleteLocalFile(file.stableId)
                }
            }.onSuccess {
                _state.update { it.copy(isClearingCache = false, cacheCleared = true) }
            }.onFailure { error ->
                _state.update { it.copy(isClearingCache = false, errorMessage = error.message ?: "Local cache could not be cleared") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(cacheCleared = false, errorMessage = null) }

    private fun showError(error: Throwable) {
        _state.update { it.copy(errorMessage = error.message ?: "The setting could not be saved") }
    }
}
