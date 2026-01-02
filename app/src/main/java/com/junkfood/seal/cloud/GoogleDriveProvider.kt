package com.junkfood.seal.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class GoogleDriveProvider : CloudStorageProvider {

    override val providerType = CloudProviderType.GOOGLE_DRIVE

    private val _authState = MutableStateFlow<CloudAuthState>(CloudAuthState.NotAuthenticated)
    override val authState: StateFlow<CloudAuthState> = _authState.asStateFlow()

    private val _uploadState = MutableStateFlow<CloudUploadState>(CloudUploadState.Idle)
    override val uploadState: StateFlow<CloudUploadState> = _uploadState.asStateFlow()

    private var googleSignInClient: GoogleSignInClient? = null
    private var driveService: Drive? = null
    private var currentAccount: CloudAccount? = null
    private lateinit var appContext: Context

    companion object {
        const val REQUEST_CODE_SIGN_IN = 1001
        private const val APP_NAME = "Seal"

        private val SCOPES = listOf(DriveScopes.DRIVE_FILE)
    }

    override suspend fun initialize(context: Context) {
        appContext = context.applicationContext

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)

        // Check if already signed in
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
            setupDriveService(account)
            currentAccount = CloudAccount(
                providerType = CloudProviderType.GOOGLE_DRIVE,
                accountName = account.displayName ?: "",
                accountEmail = account.email ?: ""
            )
            _authState.value = CloudAuthState.Authenticated(
                accountName = account.displayName ?: "",
                accountEmail = account.email ?: ""
            )
        }
    }

    private fun setupDriveService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(appContext, SCOPES)
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()
    }

    override suspend fun signIn(activity: Activity): Result<CloudAccount> {
        _authState.value = CloudAuthState.Authenticating

        return try {
            val signInIntent = googleSignInClient?.signInIntent
                ?: return Result.failure(Exception("Google Sign-In not initialized"))

            // This will be handled via Activity result
            activity.startActivityForResult(signInIntent, REQUEST_CODE_SIGN_IN)

            // Return a placeholder - actual result will be processed via handleSignInResult
            Result.success(
                CloudAccount(
                    providerType = CloudProviderType.GOOGLE_DRIVE,
                    accountName = "",
                    accountEmail = ""
                )
            )
        } catch (e: Exception) {
            _authState.value = CloudAuthState.Error(e.message ?: "Sign-in failed")
            Result.failure(e)
        }
    }

    /**
     * Handle the sign-in result from the activity
     */
    suspend fun handleSignInResult(data: Intent?): Result<CloudAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.result

            if (account != null) {
                setupDriveService(account)
                currentAccount = CloudAccount(
                    providerType = CloudProviderType.GOOGLE_DRIVE,
                    accountName = account.displayName ?: "",
                    accountEmail = account.email ?: ""
                )
                _authState.value = CloudAuthState.Authenticated(
                    accountName = account.displayName ?: "",
                    accountEmail = account.email ?: ""
                )
                Result.success(currentAccount!!)
            } else {
                _authState.value = CloudAuthState.Error("Sign-in failed: No account")
                Result.failure(Exception("No account returned"))
            }
        } catch (e: Exception) {
            _authState.value = CloudAuthState.Error(e.message ?: "Sign-in failed")
            Result.failure(e)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            suspendCoroutine { continuation ->
                googleSignInClient?.signOut()?.addOnCompleteListener {
                    currentAccount = null
                    driveService = null
                    _authState.value = CloudAuthState.NotAuthenticated
                    continuation.resume(Result.success(Unit))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isSignedIn(): Boolean {
        return currentAccount != null && driveService != null
    }

    override fun getCurrentAccount(): CloudAccount? = currentAccount

    override suspend fun uploadFile(
        file: File,
        destinationFolderId: String?,
        fileName: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val service = driveService
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val uploadFileName = fileName ?: file.name
            _uploadState.value = CloudUploadState.Uploading(0f, uploadFileName)

            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = uploadFileName
                if (destinationFolderId != null) {
                    parents = listOf(destinationFolderId)
                }
            }

            val mimeType = getMimeType(file)
            val mediaContent = FileContent(mimeType, file)

            val uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()

            _uploadState.value = CloudUploadState.Success(uploadedFile.id, uploadedFile.name)
            Result.success(uploadedFile.id)
        } catch (e: Exception) {
            _uploadState.value = CloudUploadState.Error(e.message ?: "Upload failed")
            Result.failure(e)
        }
    }

    override suspend fun listFolders(parentFolderId: String?): Result<List<CloudFolder>> =
        withContext(Dispatchers.IO) {
            try {
                val service = driveService
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                val query = StringBuilder("mimeType='application/vnd.google-apps.folder' and trashed=false")
                if (parentFolderId != null) {
                    query.append(" and '$parentFolderId' in parents")
                } else {
                    query.append(" and 'root' in parents")
                }

                val result = service.files().list()
                    .setQ(query.toString())
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()

                val folders = result.files?.map { file ->
                    CloudFolder(
                        id = file.id,
                        name = file.name,
                        path = file.name
                    )
                } ?: emptyList()

                Result.success(folders)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun createFolder(
        folderName: String,
        parentFolderId: String?
    ): Result<CloudFolder> = withContext(Dispatchers.IO) {
        try {
            val service = driveService
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                if (parentFolderId != null) {
                    parents = listOf(parentFolderId)
                }
            }

            val folder = service.files().create(fileMetadata)
                .setFields("id, name")
                .execute()

            Result.success(
                CloudFolder(
                    id = folder.id,
                    name = folder.name,
                    path = folder.name
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getUploadProgress(): Flow<Float> {
        return uploadState.map { state ->
            when (state) {
                is CloudUploadState.Uploading -> state.progress
                is CloudUploadState.Success -> 1f
                else -> 0f
            }
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "txt" -> "text/plain"
            "srt" -> "text/plain"
            "vtt" -> "text/vtt"
            else -> "application/octet-stream"
        }
    }
}
