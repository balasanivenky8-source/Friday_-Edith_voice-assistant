package com.example.ai

import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val TAG = "DeepThinkEngine"
private const val THINKING_MODEL = "gemini-4-carbon"

class DeepThinkEngine {

    private val reasoningModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = THINKING_MODEL,
            generationConfig = generationConfig {
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            },
            systemInstruction = content {
                text(
                    "You are the deep reasoning core behind Friday, a sharp, witty AI assistant. " +
                        "Provide an accurate, concise, conversational synthesis (2-4 spoken sentences max) " +
                        "that Friday can relay effortlessly by voice without formatting symbols."
                )
            }
        )
    }

    suspend fun analyze(query: String, latestJpegFrame: ByteArray?): String {
        return try {
            withTimeout(60_000L) {
                val inputContent = content {
                    if (latestJpegFrame != null) {
                        val bitmap = BitmapFactory.decodeByteArray(
                            latestJpegFrame,
                            0,
                            latestJpegFrame.size
                        )
                        if (bitmap != null) {
                            image(bitmap)
                        }
                    }
                    text(query)
                }
                val response = reasoningModel.generateContent(inputContent)
                response.text?.takeIf { it.isNotBlank() }
                    ?: "Deep reasoning completed without a textual summary."
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Deep thinking timed out", e)
            "Deep analysis timed out after 60 seconds."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Deep thinking failed", e)
            "Deep analysis error: ${e.localizedMessage ?: "Unable to reach reasoning model."}"
        }
    }
}
