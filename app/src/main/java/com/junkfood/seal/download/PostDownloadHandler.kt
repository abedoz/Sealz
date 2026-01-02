package com.junkfood.seal.download

import android.content.Context
import com.junkfood.seal.ai.AIManager
import com.junkfood.seal.cloud.CloudStorageManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles post-download operations like cloud upload and AI transcription
 */
class PostDownloadHandler(
    private val context: Context,
    private val cloudStorageManager: CloudStorageManager,
    private val aiManager: AIManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Handle post-download operations for a completed download
     * @param filePath Path to the downloaded file
     */
    fun onDownloadComplete(filePath: String?) {
        if (filePath.isNullOrEmpty()) return

        val file = File(filePath)
        if (!file.exists()) return

        // Handle cloud upload
        if (cloudStorageManager.isCloudUploadEnabled() &&
            cloudStorageManager.isAutoUploadEnabled() &&
            cloudStorageManager.hasActiveProvider()) {
            scope.launch {
                uploadToCloud(file)
            }
        }

        // Handle AI transcription
        if (aiManager.isTranscriptionEnabled() &&
            aiManager.isAutoTranscribeEnabled() &&
            aiManager.transcriber.isConfigured() &&
            aiManager.isSupported(file)) {
            scope.launch {
                transcribeFile(file)
            }
        }
    }

    private suspend fun uploadToCloud(file: File) {
        try {
            cloudStorageManager.uploadFile(file)
                .onSuccess { fileId ->
                    android.util.Log.i(TAG, "Uploaded to cloud: ${file.name} -> $fileId")
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Cloud upload failed: ${error.message}")
                }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cloud upload error: ${e.message}")
        }
    }

    private suspend fun transcribeFile(file: File) {
        try {
            aiManager.transcribe(file)
                .onSuccess { result ->
                    android.util.Log.i(TAG, "Transcription complete: ${result.wordCount} words")
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Transcription failed: ${error.message}")
                }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Transcription error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PostDownloadHandler"
    }
}
