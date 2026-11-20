package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import com.example.ai.DeepThinkEngine
import com.example.vision.VisionFrameBus
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FridayToolExecutor"

interface ToolActionBridge {
    fun onToolTelemetry(label: String?)
    fun requestOpenWebsite(url: String): Boolean
    suspend fun requestCameraVision(enabled: Boolean, useFrontCamera: Boolean?): String
    suspend fun requestScreenVision(enabled: Boolean): String
}

class FridayToolExecutor(
    private val context: Context,
    private val deepThinkEngine: DeepThinkEngine,
    private val bridge: ToolActionBridge
) {

    fun buildTools(): List<Tool> {
        val openWebsiteDecl = FunctionDeclaration(
            name = "openWebsite",
            description = "Opens a website URL in the user's Android browser. Use when the user asks to open, visit, or launch a website or web page.",
            parameters = mapOf(
                "url" to Schema.string(
                    description = "Full website URL or domain name (e.g. https://github.com or youtube.com)"
                )
            )
        )

        val setCameraVisionDecl = FunctionDeclaration(
            name = "setCameraVision",
            description = "Turns Friday's background running camera vision eyes on or off, or switches between front and rear cameras.",
            parameters = mapOf(
                "enabled" to Schema.boolean(
                    description = "True to turn camera vision eyes on, false to turn them off"
                ),
                "useFrontCamera" to Schema.boolean(
                    description = "Optional: true for selfie/front camera, false for main rear camera",
                    nullable = true
                )
            ),
            optionalParameters = listOf("useFrontCamera")
        )

        val setScreenVisionDecl = FunctionDeclaration(
            name = "setScreenVision",
            description = "Turns Friday's live screen vision eyes on or off so Friday can see what is currently on the user's phone screen.",
            parameters = mapOf(
                "enabled" to Schema.boolean(
                    description = "True to start watching the screen, false to stop"
                )
            )
        )

        val deepThinkDecl = FunctionDeclaration(
            name = "deepThinkAnalysis",
            description = "Runs high-depth reasoning for complex logic, math, coding, strategy, or detailed analysis of what Friday's camera or screen eyes currently see.",
            parameters = mapOf(
                "query" to Schema.string(
                    description = "The complex question or analytical task to solve deeply"
                ),
                "includeVisualFrame" to Schema.boolean(
                    description = "True if the latest camera or screen vision frame should be analyzed deeply",
                    nullable = true
                )
            ),
            optionalParameters = listOf("includeVisualFrame")
        )

        val getDeviceStatusDecl = FunctionDeclaration(
            name = "getDeviceStatus",
            description = "Gets current local time, date, battery percentage, charging state, and active vision modes.",
            parameters = emptyMap()
        )

        return listOf(
            Tool.functionDeclarations(
                listOf(
                    openWebsiteDecl,
                    setCameraVisionDecl,
                    setScreenVisionDecl,
                    deepThinkDecl,
                    getDeviceStatusDecl
                )
            )
        )
    }

    suspend fun execute(call: FunctionCallPart): JsonObject {
        Log.d(TAG, "Executing tool: ${call.name} args=${call.args}")
        return when (call.name) {
            "openWebsite" -> handleOpenWebsite(call)
            "setCameraVision" -> handleCameraVision(call)
            "setScreenVision" -> handleScreenVision(call)
            "deepThinkAnalysis" -> handleDeepThink(call)
            "getDeviceStatus" -> handleDeviceStatus()
            else -> JsonObject(
                mapOf(
                    "success" to JsonPrimitive(false),
                    "error" to JsonPrimitive("Unknown tool: ${call.name}")
                )
            )
        }
    }

    private fun handleOpenWebsite(call: FunctionCallPart): JsonObject {
        val rawUrl = call.args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            return JsonObject(
                mapOf(
                    "success" to JsonPrimitive(false),
                    "message" to JsonPrimitive("No URL provided.")
                )
            )
        }
        val normalizedUrl = if (rawUrl.startsWith("http://", ignoreCase = true) ||
            rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        bridge.onToolTelemetry("OPENING ${Uri.parse(normalizedUrl).host?.uppercase() ?: "LINK"}")
        val launched = bridge.requestOpenWebsite(normalizedUrl)
        return if (launched) {
            JsonObject(
                mapOf(
                    "success" to JsonPrimitive(true),
                    "openedUrl" to JsonPrimitive(normalizedUrl),
                    "message" to JsonPrimitive("Opened $normalizedUrl in browser.")
                )
            )
        } else {
            JsonObject(
                mapOf(
                    "success" to JsonPrimitive(false),
                    "message" to JsonPrimitive("Could not launch browser for $normalizedUrl.")
                )
            )
        }
    }

    private suspend fun handleCameraVision(call: FunctionCallPart): JsonObject {
        val enabled = call.args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val useFront = call.args["useFrontCamera"]?.jsonPrimitive?.booleanOrNull
        bridge.onToolTelemetry(if (enabled) "ACTIVATING CAM EYES" else "CLOSING CAM EYES")
        val status = bridge.requestCameraVision(enabled, useFront)
        return JsonObject(
            mapOf(
                "success" to JsonPrimitive(true),
                "cameraActive" to JsonPrimitive(VisionFrameBus.isCameraActive.value),
                "frontCamera" to JsonPrimitive(VisionFrameBus.useFrontCamera.value),
                "status" to JsonPrimitive(status)
            )
        )
    }

    private suspend fun handleScreenVision(call: FunctionCallPart): JsonObject {
        val enabled = call.args["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        bridge.onToolTelemetry(if (enabled) "ACTIVATING SCREEN EYES" else "CLOSING SCREEN EYES")
        val status = bridge.requestScreenVision(enabled)
        return JsonObject(
            mapOf(
                "success" to JsonPrimitive(true),
                "screenActive" to JsonPrimitive(VisionFrameBus.isScreenActive.value),
                "status" to JsonPrimitive(status)
            )
        )
    }

    private suspend fun handleDeepThink(call: FunctionCallPart): JsonObject {
        val query = call.args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val includeVision = call.args["includeVisualFrame"]?.jsonPrimitive?.booleanOrNull ?: true
        if (query.isEmpty()) {
            return JsonObject(
                mapOf(
                    "success" to JsonPrimitive(false),
                    "message" to JsonPrimitive("No query provided for deep thinking.")
                )
            )
        }
        bridge.onToolTelemetry("DEEP THINKING...")
        val frame = if (includeVision) VisionFrameBus.getLatestFrameBytes() else null
        val analysis = deepThinkEngine.analyze(query, frame)
        return JsonObject(
            mapOf(
                "success" to JsonPrimitive(true),
                "analysis" to JsonPrimitive(analysis)
            )
        )
    }

    private fun handleDeviceStatus(): JsonObject {
        bridge.onToolTelemetry("READING TELEMETRY")
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = batteryManager?.isCharging ?: false
        val now = SimpleDateFormat("EEEE, MMM d, h:mm a", Locale.getDefault()).format(Date())
        return JsonObject(
            mapOf(
                "localTime" to JsonPrimitive(now),
                "batteryPercent" to JsonPrimitive(batteryPct),
                "isCharging" to JsonPrimitive(isCharging),
                "cameraEyesActive" to JsonPrimitive(VisionFrameBus.isCameraActive.value),
                "screenEyesActive" to JsonPrimitive(VisionFrameBus.isScreenActive.value)
            )
        )
    }

    companion object {
        fun launchBrowser(context: Context, url: String): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open URL: $url", e)
                false
            }
        }
    }
}
