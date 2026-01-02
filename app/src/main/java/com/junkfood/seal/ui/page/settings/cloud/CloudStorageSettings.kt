package com.junkfood.seal.ui.page.settings.cloud

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.cloud.CLOUD_AUTO_UPLOAD
import com.junkfood.seal.cloud.CLOUD_UPLOAD_ENABLED
import com.junkfood.seal.cloud.CloudAuthState
import com.junkfood.seal.cloud.CloudProviderType
import com.junkfood.seal.cloud.CloudStorageManager
import com.junkfood.seal.ui.common.booleanState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferenceSubtitle
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.component.PreferenceSwitchWithContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStorageSettings(
    cloudStorageManager: CloudStorageManager,
    onNavigateBack: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
        canScroll = { true }
    )

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cloudUploadEnabled by CLOUD_UPLOAD_ENABLED.booleanState
    var autoUploadEnabled by CLOUD_AUTO_UPLOAD.booleanState

    val googleDriveAuthState by cloudStorageManager.getAuthState(CloudProviderType.GOOGLE_DRIVE).collectAsState()
    val oneDriveAuthState by cloudStorageManager.getAuthState(CloudProviderType.ONEDRIVE).collectAsState()
    val activeProvider by cloudStorageManager.activeProvider.collectAsState()

    var showFolderDialog by remember { mutableStateOf(false) }
    val (currentFolderId, currentFolderName) = cloudStorageManager.getDestinationFolder()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = "Cloud Storage") },
                navigationIcon = { BackButton { onNavigateBack() } },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            // Enable/Disable Cloud Upload
            item {
                PreferenceSwitchWithContainer(
                    title = "Enable Cloud Upload",
                    icon = Icons.Outlined.CloudUpload,
                    isChecked = cloudUploadEnabled,
                    onClick = { cloudUploadEnabled = !cloudUploadEnabled }
                )
            }

            item {
                PreferenceInfo(
                    text = "Connect your cloud storage accounts to automatically upload downloaded media. Your credentials are stored securely on your device."
                )
            }

            // Google Drive Section
            item {
                PreferenceSubtitle(text = "Google Drive")
            }

            item {
                val isGoogleSignedIn = googleDriveAuthState is CloudAuthState.Authenticated
                val isGoogleAuthenticating = googleDriveAuthState is CloudAuthState.Authenticating
                val googleAccountInfo = (googleDriveAuthState as? CloudAuthState.Authenticated)

                PreferenceItem(
                    title = if (isGoogleSignedIn) "Google Drive Connected" else "Connect Google Drive",
                    description = when {
                        isGoogleAuthenticating -> "Authenticating..."
                        isGoogleSignedIn -> googleAccountInfo?.accountEmail ?: "Connected"
                        googleDriveAuthState is CloudAuthState.Error ->
                            (googleDriveAuthState as CloudAuthState.Error).message
                        else -> "Sign in to upload to Google Drive"
                    },
                    icon = if (isGoogleSignedIn) Icons.Outlined.CloudDone else Icons.Outlined.AddToDrive,
                    enabled = !isGoogleAuthenticating,
                    trailingIcon = if (isGoogleAuthenticating) {
                        { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
                    } else if (isGoogleSignedIn) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "Connected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                ) {
                    if (!isGoogleSignedIn) {
                        scope.launch {
                            (context as? Activity)?.let { activity ->
                                cloudStorageManager.signIn(activity, CloudProviderType.GOOGLE_DRIVE)
                            }
                        }
                    }
                }
            }

            // Sign out Google Drive option
            if (googleDriveAuthState is CloudAuthState.Authenticated) {
                item {
                    PreferenceItem(
                        title = "Sign out from Google Drive",
                        description = "Disconnect your Google Drive account",
                        icon = Icons.AutoMirrored.Outlined.Logout
                    ) {
                        scope.launch {
                            cloudStorageManager.signOut(CloudProviderType.GOOGLE_DRIVE)
                        }
                    }
                }
            }

            // Microsoft OneDrive Section
            item {
                PreferenceSubtitle(text = "Microsoft OneDrive")
            }

            item {
                val isOneDriveSignedIn = oneDriveAuthState is CloudAuthState.Authenticated
                val isOneDriveAuthenticating = oneDriveAuthState is CloudAuthState.Authenticating
                val oneDriveAccountInfo = (oneDriveAuthState as? CloudAuthState.Authenticated)

                PreferenceItem(
                    title = if (isOneDriveSignedIn) "OneDrive Connected" else "Connect OneDrive",
                    description = when {
                        isOneDriveAuthenticating -> "Authenticating..."
                        isOneDriveSignedIn -> oneDriveAccountInfo?.accountEmail ?: "Connected"
                        oneDriveAuthState is CloudAuthState.Error ->
                            (oneDriveAuthState as CloudAuthState.Error).message
                        else -> "Sign in to upload to OneDrive"
                    },
                    icon = if (isOneDriveSignedIn) Icons.Outlined.CloudDone else Icons.Outlined.Cloud,
                    enabled = !isOneDriveAuthenticating,
                    trailingIcon = if (isOneDriveAuthenticating) {
                        { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
                    } else if (isOneDriveSignedIn) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "Connected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                ) {
                    if (!isOneDriveSignedIn) {
                        scope.launch {
                            (context as? Activity)?.let { activity ->
                                cloudStorageManager.signIn(activity, CloudProviderType.ONEDRIVE)
                            }
                        }
                    }
                }
            }

            // Sign out OneDrive option
            if (oneDriveAuthState is CloudAuthState.Authenticated) {
                item {
                    PreferenceItem(
                        title = "Sign out from OneDrive",
                        description = "Disconnect your OneDrive account",
                        icon = Icons.AutoMirrored.Outlined.Logout
                    ) {
                        scope.launch {
                            cloudStorageManager.signOut(CloudProviderType.ONEDRIVE)
                        }
                    }
                }
            }

            // Upload Settings Section
            item {
                PreferenceSubtitle(text = "Upload Settings")
            }

            item {
                val hasActiveProvider = cloudStorageManager.hasActiveProvider()

                PreferenceSwitch(
                    title = "Auto-upload after download",
                    description = "Automatically upload files after downloading",
                    icon = Icons.Outlined.CloudUpload,
                    isChecked = autoUploadEnabled,
                    enabled = hasActiveProvider && cloudUploadEnabled
                ) {
                    autoUploadEnabled = !autoUploadEnabled
                    cloudStorageManager.setAutoUploadEnabled(autoUploadEnabled)
                }
            }

            item {
                val hasActiveProvider = cloudStorageManager.hasActiveProvider()

                PreferenceItem(
                    title = "Destination Folder",
                    description = currentFolderName ?: "Root folder (default)",
                    icon = Icons.Outlined.Folder,
                    enabled = hasActiveProvider && cloudUploadEnabled
                ) {
                    showFolderDialog = true
                }
            }

            // Active Provider Section
            if (activeProvider != null) {
                item {
                    PreferenceSubtitle(text = "Active Provider")
                }

                item {
                    PreferenceInfo(
                        text = "Currently uploading to: ${
                            when (activeProvider?.providerType) {
                                CloudProviderType.GOOGLE_DRIVE -> "Google Drive"
                                CloudProviderType.ONEDRIVE -> "Microsoft OneDrive"
                                else -> "None"
                            }
                        }"
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Folder selection dialog
    if (showFolderDialog) {
        FolderSelectionDialog(
            cloudStorageManager = cloudStorageManager,
            currentFolderId = currentFolderId,
            onDismiss = { showFolderDialog = false },
            onFolderSelected = { folderId, folderName ->
                cloudStorageManager.setDestinationFolder(folderId, folderName)
                showFolderDialog = false
            }
        )
    }
}

@Composable
fun FolderSelectionDialog(
    cloudStorageManager: CloudStorageManager,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onFolderSelected: (String?, String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<com.junkfood.seal.cloud.CloudFolder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var showCreateFolder by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            cloudStorageManager.listFolders().fold(
                onSuccess = {
                    folders = it
                    isLoading = false
                },
                onFailure = {
                    errorMessage = it.message
                    isLoading = false
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Destination Folder") },
        text = {
            Column {
                if (isLoading) {
                    CircularProgressIndicator()
                } else if (errorMessage != null) {
                    Text(
                        text = "Error: $errorMessage",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    // Root folder option
                    PreferenceItem(
                        title = "Root Folder",
                        description = "Upload to root directory",
                        icon = Icons.Outlined.Folder
                    ) {
                        onFolderSelected(null, null)
                    }

                    folders.forEach { folder ->
                        PreferenceItem(
                            title = folder.name,
                            icon = Icons.Outlined.Folder
                        ) {
                            onFolderSelected(folder.id, folder.name)
                        }
                    }

                    if (showCreateFolder) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("New Folder Name") },
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (showCreateFolder && newFolderName.isNotBlank()) {
                TextButton(
                    onClick = {
                        scope.launch {
                            cloudStorageManager.createFolder(newFolderName).fold(
                                onSuccess = { folder ->
                                    onFolderSelected(folder.id, folder.name)
                                },
                                onFailure = {
                                    errorMessage = it.message
                                }
                            )
                        }
                    }
                ) {
                    Text("Create")
                }
            } else {
                TextButton(onClick = { showCreateFolder = true }) {
                    Text("New Folder")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
