package com.tareimbursement.app.service.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import com.tareimbursement.app.core.ai.AiModel
import java.io.File

/** A loaded on-device language model. */
interface LlmEngine : AutoCloseable {
    val model: AiModel
    /** Runs one prompt (optionally with the bill picture for vision models) and returns the text answer. */
    fun generate(prompt: String, image: Bitmap? = null): String
}

/**
 * MediaPipe LLM Inference (LiteRT). Prompt templates are blanked so the exact chat-formatted
 * prompt the app was tested with reaches the model. Greedy decoding (top-k 1, temperature 0)
 * makes answers repeatable. Tries the GPU first and falls back to the CPU. Runs fully offline.
 */
class MediaPipeEngine private constructor(override val model: AiModel, private val llm: LlmInference, val backend: String) : LlmEngine {

    override fun generate(prompt: String, image: Bitmap?): String {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(1).setTemperature(0f).setRandomSeed(7)
            .setPromptTemplates(PromptTemplates.builder().setUserPrefix("").setUserSuffix("").setModelPrefix("").setModelSuffix("").setSystemPrefix("").setSystemSuffix("").build())
        // Text only: MediaPipe's image input needs its tasks-core library, which brings Google usage
        // telemetry; it is left out so nothing about the bills leaves the phone.
        LlmInferenceSession.createFromOptions(llm, opts.build()).use { s ->
            s.addQueryChunk(prompt.replace(IMAGE_SLOT, ""))
            return s.generateResponse()
        }
    }

    override fun close() { try { llm.close() } catch (_: Throwable) { } }

    companion object {
        const val IMAGE_SLOT = "<<IMAGE>>"

        fun load(context: Context, model: AiModel, file: File): MediaPipeEngine {
            var last: Throwable? = null
            for (backend in listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)) {
                try {
                    val b = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(file.absolutePath).setMaxTokens(1280).setMaxTopK(1).setPreferredBackend(backend)
                    return MediaPipeEngine(model, LlmInference.createFromOptions(context, b.build()), backend.name)
                } catch (e: UnsatisfiedLinkError) {
                    throw IllegalStateException("This phone's processor is not supported by the on-device AI (64-bit ARM needed).", e)
                } catch (e: Throwable) { last = e }
            }
            throw IllegalStateException("The AI model could not be loaded: ${last?.message ?: "unknown error"}", last)
        }
    }
}
