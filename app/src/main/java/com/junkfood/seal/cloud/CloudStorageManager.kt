package com.junkfood.seal.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
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

// Preference keys for cloud storage
const val CLOUD_UPLOAD_ENABLED = "cloud_upload_enabled"
const val CLOUD_PROVIDER = "cloud_provider"
const val CLOUD_FOLDER_ID = "cloud_folder_id"
const val CLOUD_FOLDER_NAME = "cloud_folder_name"
const val CLOUD_AUTO_UPLOAD = "cloud_auto_upload"
const val GOOGLE_DRIVE_ENABLED = "google_drive_enabled"
const val ONEDRIVE_ENABLED = "onedrive_enabled"

/**
 * Manages cloud storage providers and handles file uploads
 */
class CloudStorageManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val googleDriveProvider = GoogleDriveProvider()
    private val oneDriveProvider = OneDriveProvider()

    private val _activeProvider = MutableStateFlow<CloudStorageProvider?>(null)
    val activeProvider: StateFlow<CloudStorageProvider?> = _activeProvider.asStateFlow()

    private val _uploadQueue = MutableStateFlow<List<UploadTask>>(emptyList())
    val uploadQueue: StateFlow<List<UploadTask>> = _uploadQueue.asStateFlow()

    data class UploadTask(
        val id: String,
        val file: File,
        val provider: CloudProviderType,
        val destinationFolderId: String?,
        val state: UploadTaskState = UploadTaskState.Pending
    )

    sealed class UploadTaskState {
        object Pending : UploadTaskState()
        data class Uploading(val progress: Float) : UploadTaskState()
        data class Completed(val cloudFileId: String) : UploadTaskState()
        data class Failed(val error: String) : UploadTaskState()
    }

    suspend fun initialize() {
        googleDriveProvider.initialize(context)
        oneDriveProvider.initialize(context)

        // Set active provider based on preferences
        val providerName = CLOUD_PROVIDER.getString()
        when (providerName) {
            CloudProviderType.GOOGLE_DRIVE.name -> {
                if (googleDriveProvider.isSignedIn()) {
                    _activeProvider.value = googleDriveProvider
                }
            }
            CloudProviderType.ONEDRIVE.name -> {
                if (oneDriveProvider.isSignedIn()) {
                    _activeProvider.value = oneDriveProvider
                }
            }
        }
    }

    fun getProvider(type: CloudProviderType): CloudStorageProvider {
        return when (type) {
            CloudProviderType.GOOGLE_DRIVE -> googleDriveProvider
            CloudProviderType.ONEDRIVE -> oneDriveProvider
        }
    }

    fun getGoogleDriveProvider() = googleDriveProvider
    fun getOneDriveProvider() = oneDriveProvider

    suspend fun signIn(activity: Activity, providerType: CloudProviderType): Result<CloudAccount> {
        val provider = getProvider(providerType)
        return provider.signIn(activity)
    }

    suspend fun signOut(providerType: CloudProviderType): Result<Unit> {
        val provider = getProvider(providerType)
        val result = provider.signOut()

        if (result.isSuccess && _activeProvider.value?.providerType == providerType) {
            _activeProvider.value = null
            CLOUD_PROVIDER.updateString("")
        }

        return result
    }

    fun setActiveProvider(providerType: CloudProviderType) {
        val provider = getProvider(providerType)
        if (provider.isSignedIn()) {
            _activeProvider.value = provider
            CLOUD_PROVIDER.updateString(providerType.name)
        }
    }

    fun isCloudUploadEnabled(): Boolean = CLOUD_UPLOAD_ENABLED.getBoolean()

    fun setCloudUploadEnabled(enabled: Boolean) {
        CLOUD_UPLOAD_ENABLED.updateBoolean(enabled)
    }

    fun isAutoUploadEnabled(): Boolean = CLOUD_AUTO_UPLOAD.getBoolean()

    fun setAutoUploadEnabled(enabled: Boolean) {
        CLOUD_AUTO_UPLOAD.updateBoolean(enabled)
    }

    fun getDestinationFolder(): Pair<String?, String?> {
        val folderId = CLOUD_FOLDER_ID.getString().ifEmpty { null }
        val folderName = CLOUD_FOLDER_NAME.getString().ifEmpty { null }
        return Pair(folderId, folderName)
    }

    fun setDestinationFolder(folderId: String?, folderName: String?) {
        CLOUD_FOLDER_ID.updateString(folderId ?: "")
        CLOUD_FOLDER_NAME.updateString(folderName ?: "")
    }

    /**
     * Upload a file to the active cloud provider
     */
    suspend fun uploadFile(
        file: File,
        fileName: String? = null
    ): Result<String> {
        val provider = _activeProvider.value
            ?: return Result.failure(Exception("No active cloud provider"))

        if (!provider.isSignedIn()) {
            return Result.failure(Exception("Not signed in to ${provider.providerType}"))
        }

        val (folderId, _) = getDestinationFolder()

        return provider.uploadFile(
            file = file,
            destinationFolderId = folderId,
            fileName = fileName
        )
    }

    /**
     * Queue a file for upload
     */
    fun queueUpload(file: File, destinationFolderId: String? = null) {
        val provider = _activeProvider.value ?: return
        val taskId = "${file.name}_${System.currentTimeMillis()}"

        val task = UploadTask(
            id = taskId,
            file = file,
            provider = provider.providerType,
            destinationFolderId = destinationFolderId ?: getDestinationFolder().first
        )

        _uploadQueue.value = _uploadQueue.value + task

        // Start upload
        scope.launch {
            processUploadTask(task)
        }
    }

    private suspend fun processUploadTask(task: UploadTask) {
        updateTaskState(task.id, UploadTaskState.Uploading(0f))

        val provider = getProvider(task.provider)
        val result = provider.uploadFile(
            file = task.file,
            destinationFolderId = task.destinationFolderId
        )

        result.fold(
            onSuccess = { fileId ->
                updateTaskState(task.id, UploadTaskState.Completed(fileId))
            },
            onFailure = { error ->
                updateTaskState(task.id, UploadTaskState.Failed(error.message ?: "Upload failed"))
            }
        )
    }

    private fun updateTaskState(taskId: String, state: UploadTaskState) {
        _uploadQueue.value = _uploadQueue.value.map { task ->
            if (task.id == taskId) task.copy(state = state) else task
        }
    }

    /**
     * Handle Google Sign-In result from activity
     */
    suspend fun handleGoogleSignInResult(data: Intent?): Result<CloudAccount> {
        val result = googleDriveProvider.handleSignInResult(data)
        if (result.isSuccess) {
            setActiveProvider(CloudProviderType.GOOGLE_DRIVE)
        }
        return result
    }

    /**
     * Get list of folders from the active provider
     */
    suspend fun listFolders(parentFolderId: String? = null): Result<List<CloudFolder>> {
        val provider = _activeProvider.value
            ?: return Result.failure(Exception("No active cloud provider"))

        return provider.listFolders(parentFolderId)
    }

    /**
     * Create a new folder in the active provider
     */
    suspend fun createFolder(
        folderName: String,
        parentFolderId: String? = null
    ): Result<CloudFolder> {
        val provider = _activeProvider.value
            ?: return Result.failure(Exception("No active cloud provider"))

        return provider.createFolder(folderName, parentFolderId)
    }

    /**
     * Check if any cloud provider is signed in
     */
    fun hasActiveProvider(): Boolean = _activeProvider.value?.isSignedIn() == true

    /**
     * Get authentication state for a specific provider
     */
    fun getAuthState(providerType: CloudProviderType): StateFlow<CloudAuthState> {
        return getProvider(providerType).authState
    }
}
