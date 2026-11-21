package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ai.configureAppCheck
import com.example.ai.ensureDefaultFirebaseApp
import com.example.ui.FridayScreen
import com.example.ui.theme.FridayTheme
import com.example.viewmodel.FridayUiState
import com.example.viewmodel.VoiceOrbState
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name matches Friday`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Friday", appName)
    }

    @Test
    fun `default FirebaseApp initializes from google-services resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialized = ensureDefaultFirebaseApp(context)
        assertTrue("Default FirebaseApp should initialize from google-services resources", initialized)
        configureAppCheck(context, null)
        val app = FirebaseApp.getInstance(FirebaseApp.DEFAULT_APP_NAME)
        assertEquals(FirebaseApp.DEFAULT_APP_NAME, app.name)
        assertTrue(app.options.apiKey.isNotBlank())
        assertEquals("gen-lang-client-0700051686", app.options.projectId)
        assertEquals("1:357366392903:android:bdacdb95f6ebd0427ea962", app.options.applicationId)
        com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
    }

    @Test
    fun `voice controls render and respond to taps`() {
        var powerClicked = false
        var cameraClicked = false
        var screenClicked = false

        composeRule.setContent {
            FridayTheme {
                FridayScreen(
                    uiState = FridayUiState(orbState = VoiceOrbState.IDLE),
                    onPowerToggle = { powerClicked = true },
                    onMuteToggle = {},
                    onCameraToggle = { cameraClicked = true },
                    onFlipCamera = {},
                    onScreenToggle = { screenClicked = true },
                    onDismissError = {}
                )
            }
        }

        composeRule.onNodeWithTag("core_power_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("camera_vision_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen_vision_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("mute_button").assertIsDisplayed()

        assertTrue(powerClicked)
        assertTrue(cameraClicked)
        assertTrue(screenClicked)
    }
}
