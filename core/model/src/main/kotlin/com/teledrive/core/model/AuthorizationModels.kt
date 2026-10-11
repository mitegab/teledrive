package com.teledrive.core.model

sealed interface AuthorizationState {
    data object Uninitialized : AuthorizationState
    data object WaitingForTdlibParameters : AuthorizationState
    data object WaitingForPhoneNumber : AuthorizationState
    data class WaitingForCode(val hint: String? = null) : AuthorizationState
    data class WaitingForPassword(val hint: String? = null) : AuthorizationState
    data class WaitingForEmailAddress(val allowAppleId: Boolean = false, val allowGoogleId: Boolean = false) : AuthorizationState
    data class WaitingForEmailCode(val hint: String? = null) : AuthorizationState
    data class WaitingForOtherDeviceConfirmation(val link: String) : AuthorizationState
    data class WaitingForRegistration(val termsOfService: String? = null) : AuthorizationState
    data object WaitingForPremiumPurchase : AuthorizationState
    data object Ready : AuthorizationState
    data object LoggingOut : AuthorizationState
    data object Closing : AuthorizationState
    data object Closed : AuthorizationState
    data class Error(val code: Int? = null, val message: String) : AuthorizationState
}

data class TelegramUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val username: String? = null,
) {
    val displayName: String get() = listOf(firstName, lastName).filter(String::isNotBlank).joinToString(" ").ifBlank { "Telegram user" }
}

data class SavedMessagesChat(
    val chatId: Long,
    val title: String,
)
