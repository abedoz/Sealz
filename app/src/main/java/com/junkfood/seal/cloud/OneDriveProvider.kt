package com.junkfood.seal.cloud

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OneDriveProvider : CloudStorageProvider {

    override val providerType = CloudProviderType.ONEDRIVE

    private val _authState = MutableStateFlow<CloudAuthState>(CloudAuthState.NotAuthenticated)
    override val authState: StateFlow<CloudAuthState> = _authState.asStateFlow()

    private val _uploadState = MutableStateFlow<CloudUploadState>(CloudUploadState.Idle)
    override val uploadState: StateFlow<CloudUploadState> = _uploadState.asStateFlow()

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private var currentAccount: CloudAccount? = null
    private var currentAccessToken: String? = null
    private lateinit var appContext: Context
    private val httpClient = OkHttpClient()

    companion object {
        private val SCOPES = arrayOf("Files.ReadWrite", "User.Read")
        private const val GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0"
    }

    override suspend fun initialize(context: Context) {
        appContext = context.applicationContext

        // Check if MSAL config exists
        val configResId = getMsalConfigResourceId()
        if (configResId == 0) {
            // No MSAL config file - OneDrive won't be available until user configures it
            _authState.value = CloudAuthState.NotAuthenticated
            return
        }

        try {
            // Create MSAL configuration programmatically
            msalApp = suspendCoroutine { continuation ->
                PublicClientApplication.createSingleAccountPublicClientApplication(
                    context,
                    configResId,
                    object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                        override fun onCreated(application: ISingleAccountPublicClientApplication) {
                            continuation.resume(application)
                        }

                        override fun onError(exception: MsalException) {
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }

            // Check for existing account
            val account = msalApp?.currentAccount?.currentAccount
            if (account != null) {
                currentAccount = CloudAccount(
                    providerType = CloudProviderType.ONEDRIVE,
                    accountName = account.username ?: "",
                    accountEmail = account.username ?: ""
                )
                _authState.value = CloudAuthState.Authenticated(
                    accountName = account.username ?: "",
                    accountEmail = account.username ?: ""
                )
            }
        } catch (e: Exception) {
            _authState.value = CloudAuthState.Error("Initialization failed: ${e.message}")
        }
    }

    private fun getMsalConfigResourceId(): Int {
        // This returns the resource ID for the MSAL config
        // Users need to create this file in res/raw/auth_config_onedrive.json
        return appContext.resources.getIdentifier("auth_config_onedrive", "raw", appContext.packageName)
    }

    override suspend fun signIn(activity: Activity): Result<CloudAccount> {
        _authState.value = CloudAuthState.Authenticating

        return try {
            val app = msalApp ?: return Result.failure(Exception("OneDrive not configured. Please add MSAL config file."))

            val result = suspendCoroutine<IAuthenticationResult> { continuation ->
                val signInParams = SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(SCOPES.toList())
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            continuation.resume(authenticationResult)
                        }

                        override fun onError(exception: MsalException) {
                            continuation.resumeWithException(exception)
                        }

                        override fun onCancel() {
                            continuation.resumeWithException(Exception("Sign-in cancelled"))
                        }
                    })
                    .build()

                app.signIn(signInParams)
            }

            currentAccessToken = result.accessToken

            val account = CloudAccount(
                providerType = CloudProviderType.ONEDRIVE,
                accountName = result.account.username ?: "",
                accountEmail = result.account.username ?: "",
                accessToken = result.accessToken
            )
            currentAccount = account

            _authState.value = CloudAuthState.Authenticated(
                accountName = account.accountName,
                accountEmail = account.accountEmail
            )

            Result.success(account)
        } catch (e: Exception) {
            _authState.value = CloudAuthState.Error(e.message ?: "Sign-in failed")
            Result.failure(e)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            suspendCoroutine { continuation ->
                msalApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                    override fun onSignOut() {
                        currentAccount = null
                        currentAccessToken = null
                        _authState.value = CloudAuthState.NotAuthenticated
                        continuation.resume(Result.success(Unit))
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resume(Result.failure(exception))
                    }
                })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isSignedIn(): Boolean {
        return currentAccount != null && currentAccessToken != null
    }

    override fun getCurrentAccount(): CloudAccount? = currentAccount

    override suspend fun uploadFile(
        file: File,
        destinationFolderId: String?,
        fileName: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = currentAccessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val uploadFileName = fileName ?: file.name
            _uploadState.value = CloudUploadState.Uploading(0f, uploadFileName)

            val mimeType = getMimeType(file)

            // Build the upload URL
            val path = if (destinationFolderId != null) {
                "$GRAPH_ENDPOINT/me/drive/items/$destinationFolderId:/$uploadFileName:/content"
            } else {
                "$GRAPH_ENDPOINT/me/drive/root:/$uploadFileName:/content"
            }

            val requestBody = file.asRequestBody(mimeType.toMediaType())

            val request = Request.Builder()
                .url(path)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", mimeType)
                .put(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val fileId = parseFileIdFromResponse(responseBody)
                _uploadState.value = CloudUploadState.Success(fileId, uploadFileName)
                Result.success(fileId)
            } else {
                val error = "Upload failed: ${response.code} - ${response.message}"
                _uploadState.value = CloudUploadState.Error(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            _uploadState.value = CloudUploadState.Error(e.message ?: "Upload failed")
            Result.failure(e)
        }
    }

    private fun parseFileIdFromResponse(response: String?): String {
        return response?.let {
            try {
                JSONObject(it).optString("id", "unknown")
            } catch (e: Exception) {
                "unknown"
            }
        } ?: "unknown"
    }

    override suspend fun listFolders(parentFolderId: String?): Result<List<CloudFolder>> =
        withContext(Dispatchers.IO) {
            try {
                val token = currentAccessToken
                    ?: return@withContext Result.failure(Exception("Not authenticated"))

                val path = if (parentFolderId != null) {
                    "$GRAPH_ENDPOINT/me/drive/items/$parentFolderId/children?\$filter=folder ne null"
                } else {
                    "$GRAPH_ENDPOINT/me/drive/root/children?\$filter=folder ne null"
                }

                val request = Request.Builder()
                    .url(path)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val folders = parseFoldersFromResponse(responseBody)
                    Result.success(folders)
                } else {
                    Result.failure(Exception("Failed to list folders: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseFoldersFromResponse(response: String?): List<CloudFolder> {
        return response?.let {
            try {
                val json = JSONObject(it)
                val items = json.optJSONArray("value") ?: return emptyList()
                val folders = mutableListOf<CloudFolder>()

                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.has("folder")) {
                        folders.add(CloudFolder(
                            id = item.optString("id", ""),
                            name = item.optString("name", ""),
                            path = item.optString("name", "")
                        ))
                    }
                }
                folders
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    override suspend fun createFolder(
        folderName: String,
        parentFolderId: String?
    ): Result<CloudFolder> = withContext(Dispatchers.IO) {
        try {
            val token = currentAccessToken
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val path = if (parentFolderId != null) {
                "$GRAPH_ENDPOINT/me/drive/items/$parentFolderId/children"
            } else {
                "$GRAPH_ENDPOINT/me/drive/root/children"
            }

            val jsonBody = JSONObject().apply {
                put("name", folderName)
                put("folder", JSONObject())
                put("@microsoft.graph.conflictBehavior", "rename")
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(path)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "{}")
                Result.success(CloudFolder(
                    id = json.optString("id", ""),
                    name = json.optString("name", folderName),
                    path = json.optString("name", folderName)
                ))
            } else {
                Result.failure(Exception("Failed to create folder: ${response.code}"))
            }
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
