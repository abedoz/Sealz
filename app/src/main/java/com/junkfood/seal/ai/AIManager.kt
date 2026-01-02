package com.junkfood.seal.ai

import android.content.Context
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateString
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages AI features including transcription
 */
class AIManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val transcriber = GeminiTranscriber(context)

    private val _transcriptionQueue = MutableStateFlow<List<TranscriptionTask>>(emptyList())
    val transcriptionQueue: StateFlow<List<TranscriptionTask>> = _transcriptionQueue.asStateFlow()

    data class TranscriptionTask(
        val id: String,
        val file: File,
        val language: String,
        val state: TranscriptionTaskState = TranscriptionTaskState.Pending
    )

    sealed class TranscriptionTaskState {
        object Pending : TranscriptionTaskState()
        object Processing : TranscriptionTaskState()
        data class Completed(val result: TranscriptionResult) : TranscriptionTaskState()
        data class Failed(val error: String) : TranscriptionTaskState()
    }

    /**
     * Initialize the AI manager
     */
    fun initialize(): Boolean {
        return transcriber.initialize()
    }

    /**
     * Check if transcription is enabled
     */
    fun isTranscriptionEnabled(): Boolean = TRANSCRIPTION_ENABLED.getBoolean()

    /**
     * Enable or disable transcription
     */
    fun setTranscriptionEnabled(enabled: Boolean) {
        TRANSCRIPTION_ENABLED.updateBoolean(enabled)
    }

    /**
     * Check if auto-transcribe is enabled
     */
    fun isAutoTranscribeEnabled(): Boolean = AUTO_TRANSCRIBE.getBoolean()

    /**
     * Enable or disable auto-transcribe
     */
    fun setAutoTranscribeEnabled(enabled: Boolean) {
        AUTO_TRANSCRIBE.updateBoolean(enabled)
    }

    /**
     * Get the transcription language
     */
    fun getTranscriptionLanguage(): String {
        return TRANSCRIPTION_LANGUAGE.getString().ifEmpty { "en" }
    }

    /**
     * Set the transcription language
     */
    fun setTranscriptionLanguage(language: String) {
        TRANSCRIPTION_LANGUAGE.updateString(language)
    }

    /**
     * Get the output format
     */
    fun getOutputFormat(): TranscriptionOutputFormat {
        val formatName = TRANSCRIPTION_OUTPUT_FORMAT.getString()
        return try {
            TranscriptionOutputFormat.valueOf(formatName)
        } catch (e: Exception) {
            TranscriptionOutputFormat.PLAIN_TEXT
        }
    }

    /**
     * Set the output format
     */
    fun setOutputFormat(format: TranscriptionOutputFormat) {
        TRANSCRIPTION_OUTPUT_FORMAT.updateString(format.name)
    }

    /**
     * Queue a file for transcription
     */
    fun queueTranscription(file: File) {
        if (!transcriber.isConfigured()) {
            return
        }

        val taskId = "${file.name}_${System.currentTimeMillis()}"
        val language = getTranscriptionLanguage()

        val task = TranscriptionTask(
            id = taskId,
            file = file,
            language = language
        )

        _transcriptionQueue.value = _transcriptionQueue.value + task

        // Start transcription
        scope.launch {
            processTranscriptionTask(task)
        }
    }

    private suspend fun processTranscriptionTask(task: TranscriptionTask) {
        updateTaskState(task.id, TranscriptionTaskState.Processing)

        val result = if (isAudioFile(task.file)) {
            transcriber.transcribeAudio(task.file, task.language)
        } else {
            transcriber.transcribeVideo(task.file, task.language)
        }

        result.fold(
            onSuccess = { transcription ->
                updateTaskState(task.id, TranscriptionTaskState.Completed(transcription))

                // Save transcription if auto-save is enabled
                val outputDir = task.file.parentFile
                if (outputDir != null) {
                    transcriber.saveTranscription(
                        transcription = transcription,
                        outputDir = outputDir,
                        format = getOutputFormat()
                    )
                }
            },
            onFailure = { error ->
                updateTaskState(task.id, TranscriptionTaskState.Failed(error.message ?: "Transcription failed"))
            }
        )
    }

    private fun updateTaskState(taskId: String, state: TranscriptionTaskState) {
        _transcriptionQueue.value = _transcriptionQueue.value.map { task ->
            if (task.id == taskId) task.copy(state = state) else task
        }
    }

    /**
     * Transcribe a file immediately
     */
    suspend fun transcribe(
        file: File,
        language: String? = null
    ): Result<TranscriptionResult> {
        val lang = language ?: getTranscriptionLanguage()

        return if (isAudioFile(file)) {
            transcriber.transcribeAudio(file, lang)
        } else {
            transcriber.transcribeVideo(file, lang)
        }
    }

    /**
     * Check if file is supported for transcription
     */
    fun isSupported(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    private fun isAudioFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov")
        val SUPPORTED_EXTENSIONS = AUDIO_EXTENSIONS + VIDEO_EXTENSIONS

        val SUPPORTED_LANGUAGES = listOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "ar" to "Arabic",
            "hi" to "Hindi"
        )
    }
}
