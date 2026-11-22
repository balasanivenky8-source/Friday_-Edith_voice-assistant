package com.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ai.configureAppCheck
import com.example.ai.ensureDefaultFirebaseApp
import com.example.ai.isValidFirebaseOptions
import com.example.ui.FridayScreen
import com.example.ui.theme.FridayTheme
import com.example.viewmodel.FridayUiEvent
import com.example.viewmodel.FridayUiState
import com.example.viewmodel.FridayViewModel
import com.example.viewmodel.VoiceOrbState
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        // Verify repeated calls remain idempotent and thread-safe
        assertTrue(ensureDefaultFirebaseApp(context))
        val app = FirebaseApp.getInstance(FirebaseApp.DEFAULT_APP_NAME)
        assertEquals(FirebaseApp.DEFAULT_APP_NAME, app.name)
        assertTrue(app.options.apiKey.isNotBlank())
        assertEquals("gen-lang-client-0700051686", app.options.projectId)
        assertEquals("1:357366392903:android:bdacdb95f6ebd0427ea962", app.options.applicationId)
        com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
    }

    @Test
    fun `Firebase initialization rejects null or incomplete options cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(isValidFirebaseOptions(null))

        val missingProjectOptions = FirebaseOptions.Builder()
            .setApplicationId("1:123:android:456")
            .setApiKey("test-key")
            .build()
        assertFalse(isValidFirebaseOptions(missingProjectOptions))

        FirebaseApp.getApps(context).forEach { it.delete() }
        try {
            assertFalse(ensureDefaultFirebaseApp(context) { null })
            assertFalse(ensureDefaultFirebaseApp(context) { missingProjectOptions })
            assertTrue(ensureDefaultFirebaseApp(context))
        } finally {
            assertTrue(ensureDefaultFirebaseApp(context))
            configureAppCheck(context, null)
        }
    }

    @Test
    fun `FridayViewModel starts idle without Firebase config errors`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = FridayViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceOrbState.IDLE, vm.uiState.value.orbState)
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isMuted)
    }

    @Test
    fun `FridayViewModel persists permission errors across state updates and resets on dismiss`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = FridayViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()

        // Without RECORD_AUDIO permission, power toggle emits RequestMicPermission
        val emittedEvents = mutableListOf<FridayUiEvent>()
        val collectJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            vm.events.collect { emittedEvents.add(it) }
        }
        try {
            vm.onPowerToggle()
            assertEquals(listOf(FridayUiEvent.RequestMicPermission), emittedEvents)
        } finally {
            collectJob.cancel()
        }

        // Denying mic permission enters ERROR and survives subsequent flow emissions
        vm.onMicPermissionResult(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceOrbState.ERROR, vm.uiState.value.orbState)
        assertEquals(app.getString(R.string.error_mic_permission), vm.uiState.value.errorMessage)

        // Mute toggle remains disabled while not in an active session
        vm.onMuteToggle()
        assertFalse(vm.uiState.value.isMuted)

        // Dismissing error clears message and restores IDLE
        vm.dismissError()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VoiceOrbState.IDLE, vm.uiState.value.orbState)
        assertNull(vm.uiState.value.errorMessage)

        // Camera and screen denials persist in UI error state until dismissed
        vm.onCameraPermissionResult(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(app.getString(R.string.error_camera_permission), vm.uiState.value.errorMessage)

        vm.dismissError()
        vm.onMediaProjectionResult(Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(app.getString(R.string.error_screen_denied), vm.uiState.value.errorMessage)
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
