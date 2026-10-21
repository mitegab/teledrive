package com.teledrive.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.ErrorOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
            try {
                gateway.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error)
            } finally {
                _uiState.update { it.copy(isConnecting = false) }
            }
        }
    }

    fun submitPhoneNumber(value: String) = submit { gateway.submitPhoneNumber(value) }
    fun submitCode(value: String) = submit { gateway.submitCode(value) }
    fun submitPassword(value: String) = submit { gateway.submitPassword(value) }
    fun submitEmail(value: String) = submit { gateway.submitEmailAddress(value) }
    fun submitEmailCode(value: String) = submit { gateway.submitEmailCode(value) }

    fun cancel() {
        viewModelScope.launch {
            try {
                gateway.cancelAuthorization()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                gateway.logOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(actionError = null) }

    private fun submit(action: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionError = null) }
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                showError(error)
            }
        }
    }

    private fun showError(error: Throwable) {
        val appError = error.toAppError(ErrorOperation.AUTHENTICATION)
        _uiState.update { it.copy(isConnecting = false, actionError = appError.userMessage) }
    }
}
