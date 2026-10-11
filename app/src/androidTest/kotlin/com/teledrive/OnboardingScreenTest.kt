package com.teledrive

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectActionIsVisibleAndInvokesCallback() {
        var clicked = false
        composeRule.setContent {
            TeleDriveTheme {
                OnboardingScreen(errorMessage = null, isConnecting = false, onConnect = { clicked = true })
            }
        }

        composeRule.onNodeWithContentDescription("Connect TeleDrive to Telegram")
            .assertExists()
            .performClick()
        assertTrue(clicked)
    }
}
