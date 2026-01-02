package com.junkfood.seal.ai

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// Preference keys for AI transcription
const val GEMINI_API_KEY = "gemini_api_key"
const val TRANSCRIPTION_ENABLED = "transcription_enabled"
const val TRANSCRIPTION_LANGUAGE = "transcription_language"
const val AUTO_TRANSCRIBE = "auto_transcribe"
const val TRANSCRIPTION_OUTPUT_FORMAT = "transcription_output_format"

/**
 * Represents the state of a transcription task
 */
sealed class TranscriptionState {
    object Idle : TranscriptionState()
    object Processing : TranscriptionState()
    data class Success(val transcription: TranscriptionResult) : TranscriptionState()
    data class Error(val message: String) : TranscriptionState()
}

/**
 * Transcription result with text and metadata
 */
data class TranscriptionResult(
    val id: String,
    val text: String,
    val language: String,
    val sourceFileName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long? = null,
    val wordCount: Int = text.split("\\s+".toRegex()).size
)

/**
 * Output format for transcriptions
 */
enum class TranscriptionOutputFormat {
    PLAIN_TEXT,
    SRT,
    VTT
}

/**
 * Gemini-based transcription service
 */
class GeminiTranscriber(private val context: Context) {

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _transcriptionHistory = MutableStateFlow<List<TranscriptionResult>>(emptyList())
    val transcriptionHistory: StateFlow<List<TranscriptionResult>> = _transcriptionHistory.asStateFlow()

    private var generativeModel: GenerativeModel? = null

    companion object {
        private const val MODEL_NAME = "gemini-1.5-flash"
    }

    /**
     * Initialize the Gemini model with the API key
     */
    fun initialize(): Boolean {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            return false
        }

        generativeModel = GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = apiKey
        )
        return true
    }

    /**
     * Set the Gemini API key
     */
    fun setApiKey(apiKey: String) {
        GEMINI_API_KEY.updateString(apiKey)
        initialize()
    }

    /**
     * Get the stored API key
     */
    fun getApiKey(): String = GEMINI_API_KEY.getString()

    /**
     * Check if API key is configured
     */
    fun isConfigured(): Boolean = getApiKey().isNotEmpty()

    /**
     * Transcribe an audio file using Gemini
     * Note: Gemini currently works best with audio content via prompts
     * For full audio transcription, the audio needs to be processed
     */
    suspend fun transcribeAudio(
        audioFile: File,
        language: String = "en"
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        val model = generativeModel
            ?: return@withContext Result.failure(Exception("Gemini not initialized. Please set API key."))

        if (!audioFile.exists()) {
            return@withContext Result.failure(Exception("Audio file not found"))
        }

        _state.value = TranscriptionState.Processing

        try {
            // Read audio file bytes
            val audioBytes = audioFile.readBytes()
            val mimeType = getMimeType(audioFile)

            // Create content with audio
            val inputContent = content {
                blob(mimeType, audioBytes)
                text(buildTranscriptionPrompt(language))
            }

            // Generate transcription
            val response = model.generateContent(inputContent)
            val transcriptionText = response.text ?: ""

            if (transcriptionText.isEmpty()) {
                _state.value = TranscriptionState.Error("Empty transcription result")
                return@withContext Result.failure(Exception("Empty transcription result"))
            }

            val result = TranscriptionResult(
                id = "${audioFile.name}_${System.currentTimeMillis()}",
                text = transcriptionText,
                language = language,
                sourceFileName = audioFile.name
            )

            _transcriptionHistory.value = _transcriptionHistory.value + result
            _state.value = TranscriptionState.Success(result)

            Result.success(result)
        } catch (e: Exception) {
            _state.value = TranscriptionState.Error(e.message ?: "Transcription failed")
            Result.failure(e)
        }
    }

    /**
     * Transcribe audio from a video file
     */
    suspend fun transcribeVideo(
        videoFile: File,
        language: String = "en"
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        val model = generativeModel
            ?: return@withContext Result.failure(Exception("Gemini not initialized. Please set API key."))

        if (!videoFile.exists()) {
            return@withContext Result.failure(Exception("Video file not found"))
        }

        _state.value = TranscriptionState.Processing

        try {
            // Read video file bytes
            val videoBytes = videoFile.readBytes()
            val mimeType = getMimeType(videoFile)

            // Create content with video
            val inputContent = content {
                blob(mimeType, videoBytes)
                text(buildTranscriptionPrompt(language))
            }

            // Generate transcription
            val response = model.generateContent(inputContent)
            val transcriptionText = response.text ?: ""

            if (transcriptionText.isEmpty()) {
                _state.value = TranscriptionState.Error("Empty transcription result")
                return@withContext Result.failure(Exception("Empty transcription result"))
            }

            val result = TranscriptionResult(
                id = "${videoFile.name}_${System.currentTimeMillis()}",
                text = transcriptionText,
                language = language,
                sourceFileName = videoFile.name
            )

            _transcriptionHistory.value = _transcriptionHistory.value + result
            _state.value = TranscriptionState.Success(result)

            Result.success(result)
        } catch (e: Exception) {
            _state.value = TranscriptionState.Error(e.message ?: "Transcription failed")
            Result.failure(e)
        }
    }

    /**
     * Generate summary of transcription
     */
    suspend fun summarizeTranscription(
        transcription: TranscriptionResult
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = generativeModel
            ?: return@withContext Result.failure(Exception("Gemini not initialized"))

        try {
            val prompt = """
                Please provide a concise summary of the following transcription:

                ${transcription.text}

                Summary:
            """.trimIndent()

            val response = model.generateContent(prompt)
            Result.success(response.text ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert transcription to SRT format
     */
    fun convertToSrt(transcription: TranscriptionResult): String {
        // Simple conversion - split by sentences and create SRT entries
        val sentences = transcription.text.split(Regex("[.!?]+"))
            .filter { it.isNotBlank() }
            .map { it.trim() }

        val srtBuilder = StringBuilder()
        var index = 1
        var currentTimeMs = 0L
        val avgTimePerSentence = 5000L // 5 seconds per sentence estimate

        sentences.forEach { sentence ->
            val startTime = formatSrtTime(currentTimeMs)
            currentTimeMs += avgTimePerSentence
            val endTime = formatSrtTime(currentTimeMs)

            srtBuilder.appendLine(index)
            srtBuilder.appendLine("$startTime --> $endTime")
            srtBuilder.appendLine(sentence)
            srtBuilder.appendLine()

            index++
        }

        return srtBuilder.toString()
    }

    /**
     * Convert transcription to VTT format
     */
    fun convertToVtt(transcription: TranscriptionResult): String {
        val vttBuilder = StringBuilder()
        vttBuilder.appendLine("WEBVTT")
        vttBuilder.appendLine()

        val sentences = transcription.text.split(Regex("[.!?]+"))
            .filter { it.isNotBlank() }
            .map { it.trim() }

        var currentTimeMs = 0L
        val avgTimePerSentence = 5000L

        sentences.forEach { sentence ->
            val startTime = formatVttTime(currentTimeMs)
            currentTimeMs += avgTimePerSentence
            val endTime = formatVttTime(currentTimeMs)

            vttBuilder.appendLine("$startTime --> $endTime")
            vttBuilder.appendLine(sentence)
            vttBuilder.appendLine()
        }

        return vttBuilder.toString()
    }

    /**
     * Save transcription to file
     */
    suspend fun saveTranscription(
        transcription: TranscriptionResult,
        outputDir: File,
        format: TranscriptionOutputFormat = TranscriptionOutputFormat.PLAIN_TEXT
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val baseName = transcription.sourceFileName.substringBeforeLast(".")
            val (content, extension) = when (format) {
                TranscriptionOutputFormat.PLAIN_TEXT -> Pair(transcription.text, "txt")
                TranscriptionOutputFormat.SRT -> Pair(convertToSrt(transcription), "srt")
                TranscriptionOutputFormat.VTT -> Pair(convertToVtt(transcription), "vtt")
            }

            val outputFile = File(outputDir, "${baseName}_transcription.$extension")
            outputFile.writeText(content)

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clear transcription history
     */
    fun clearHistory() {
        _transcriptionHistory.value = emptyList()
    }

    /**
     * Reset state to idle
     */
    fun resetState() {
        _state.value = TranscriptionState.Idle
    }

    private fun buildTranscriptionPrompt(language: String): String {
        val languageName = getLanguageName(language)
        return """
            Please transcribe the audio/video content accurately.
            The content is in $languageName.
            Provide only the transcription text without any additional commentary or formatting.
            If there are multiple speakers, indicate speaker changes with [Speaker 1], [Speaker 2], etc.
            Transcription:
        """.trimIndent()
    }

    private fun getLanguageName(code: String): String {
        return when (code.lowercase()) {
            "en" -> "English"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            else -> "English"
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    private fun formatVttTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }
}
