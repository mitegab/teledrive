package com.teledrive.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.AuthorizationState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class SessionUiState(
    val authorizationState: AuthorizationState = AuthorizationState.Uninitialized,
    val isConnecting: Boolean = false,
    val actionError: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val gateway: TelegramStorageGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            gateway.authorizationState.collect { authorizationState ->
                _uiState.update {
                    it.copy(
                        authorizationState = authorizationState,
                        isConnecting = false,
                        actionError = if (authorizationState is AuthorizationState.Error) authorizationState.message else it.actionError,
                    )
                }
            }
        }
    }

    fun connect() {
        if (_uiState.value.isConnecting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, actionError = null) }
            runCatching { gateway.start() }
                .onFailure { error -> showError(error) }
        }
    }

    fun submitPhoneNumber(value: String) = submit { gateway.submitPhoneNumber(value) }
    fun submitCode(value: String) = submit { gateway.submitCode(value) }
    fun submitPassword(value: String) = submit { gateway.submitPassword(value) }
    fun submitEmail(value: String) = submit { gateway.submitEmailAddress(value) }
    fun submitEmailCode(value: String) = submit { gateway.submitEmailCode(value) }

    fun cancel() {
        viewModelScope.launch { runCatching { gateway.cancelAuthorization() }.onFailure(::showError) }
    }

    fun logout() {
        viewModelScope.launch { runCatching { gateway.logOut() }.onFailure(::showError) }
    }

    fun clearError() = _uiState.update { it.copy(actionError = null) }

    private fun submit(action: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionError = null) }
            runCatching { action() }.onFailure(::showError)
        }
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is StorageGatewayException -> error.message
            else -> error.message ?: "Something went wrong"
        }
        _uiState.update { it.copy(isConnecting = false, actionError = message) }
    }
}
