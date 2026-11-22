package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

private val firebaseInitLock = Any()

fun ensureDefaultFirebaseApp(context: Context): Boolean {
    return ensureDefaultFirebaseApp(context) { appContext ->
        FirebaseOptions.fromResource(appContext)
    }
}

internal fun isValidFirebaseOptions(options: FirebaseOptions?): Boolean {
    return options != null &&
        options.applicationId.isNotBlank() &&
        options.apiKey.isNotBlank() &&
        !options.projectId.isNullOrBlank()
}

internal fun ensureDefaultFirebaseApp(
    context: Context,
    optionsProvider: (Context) -> FirebaseOptions?
): Boolean {
    val appContext = context.applicationContext ?: context
    synchronized(firebaseInitLock) {
        val hasDefault = FirebaseApp.getApps(appContext).any {
            it.name == FirebaseApp.DEFAULT_APP_NAME
        } || runCatching { FirebaseApp.getInstance() }.isSuccess
        if (hasDefault) {
            return true
        }

        val options = runCatching { optionsProvider(appContext) }
            .onFailure { err -> Log.e(TAG, "Failed to read FirebaseOptions from resources", err) }
            .getOrNull()

        if (!isValidFirebaseOptions(options)) {
            Log.e(
                TAG,
                "FirebaseOptions are null or incomplete. Ensure app/google-services.json is present when building the APK."
            )
            return false
        }

        val validOptions = requireNotNull(options)
        return runCatching {
            val initialized = FirebaseApp.initializeApp(appContext, validOptions, FirebaseApp.DEFAULT_APP_NAME)
                ?: FirebaseApp.initializeApp(appContext)
            initialized != null && runCatching { FirebaseApp.getInstance() }.isSuccess
        }.onFailure { err ->
            Log.e(TAG, "FirebaseApp.initializeApp failed", err)
        }.getOrDefault(false)
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    val appContext = context.applicationContext ?: context
    try {
        if (!ensureDefaultFirebaseApp(appContext)) {
            Log.e(TAG, "Skipping App Check setup because default FirebaseApp is not initialized.")
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = appContext.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // Synchronous commit ensures token is persisted before DebugAppCheckProviderFactory reads it.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = appContext.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                appContext.packageName
            )
            if (resId != 0) {
                val siteKey = appContext.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
