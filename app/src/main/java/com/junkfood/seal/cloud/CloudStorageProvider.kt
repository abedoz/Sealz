package com.junkfood.seal.cloud

import android.app.Activity
import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a cloud storage provider (Google Drive, OneDrive, etc.)
 */
enum class CloudProviderType {
    GOOGLE_DRIVE,
    ONEDRIVE
}

/**
 * Represents the authentication state of a cloud provider
 */
sealed class CloudAuthState {
    object NotAuthenticated : CloudAuthState()
    object Authenticating : CloudAuthState()
    data class Authenticated(val accountName: String, val accountEmail: String) : CloudAuthState()
    data class Error(val message: String) : CloudAuthState()
}

/**
 * Represents the upload state
 */
sealed class CloudUploadState {
    object Idle : CloudUploadState()
    data class Uploading(val progress: Float, val fileName: String) : CloudUploadState()
    data class Success(val fileId: String, val fileName: String) : CloudUploadState()
    data class Error(val message: String) : CloudUploadState()
}

/**
 * Cloud storage account information
 */
data class CloudAccount(
    val providerType: CloudProviderType,
    val accountName: String,
    val accountEmail: String,
    val isActive: Boolean = true,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenExpiry: Long? = null
)

/**
 * Cloud folder information
 */
data class CloudFolder(
    val id: String,
    val name: String,
    val path: String
)

/**
 * Interface for cloud storage providers
 */
interface CloudStorageProvider {
    val providerType: CloudProviderType
    val authState: StateFlow<CloudAuthState>
    val uploadState: StateFlow<CloudUploadState>

    /**
     * Initialize the provider with the application context
     */
    suspend fun initialize(context: Context)

    /**
     * Start the authentication flow
     * @param activity The activity to use for the auth flow
     */
    suspend fun signIn(activity: Activity): Result<CloudAccount>

    /**
     * Sign out from the cloud provider
     */
    suspend fun signOut(): Result<Unit>

    /**
     * Check if the user is currently signed in
     */
    fun isSignedIn(): Boolean

    /**
     * Get the current account information
     */
    fun getCurrentAccount(): CloudAccount?

    /**
     * Upload a file to the cloud storage
     * @param file The local file to upload
     * @param destinationFolderId The folder ID in cloud storage (null for root)
     * @param fileName Optional custom file name
     * @return Result containing the cloud file ID
     */
    suspend fun uploadFile(
        file: File,
        destinationFolderId: String? = null,
        fileName: String? = null
    ): Result<String>

    /**
     * List folders in a given parent folder
     * @param parentFolderId The parent folder ID (null for root)
     */
    suspend fun listFolders(parentFolderId: String? = null): Result<List<CloudFolder>>

    /**
     * Create a new folder
     * @param folderName Name of the folder to create
     * @param parentFolderId Parent folder ID (null for root)
     */
    suspend fun createFolder(
        folderName: String,
        parentFolderId: String? = null
    ): Result<CloudFolder>

    /**
     * Get the upload progress as a flow
     */
    fun getUploadProgress(): Flow<Float>
}
