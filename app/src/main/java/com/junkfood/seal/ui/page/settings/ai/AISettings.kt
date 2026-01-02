package com.junkfood.seal.ui.page.settings.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.junkfood.seal.ai.AIManager
import com.junkfood.seal.ai.AUTO_TRANSCRIBE
import com.junkfood.seal.ai.TRANSCRIPTION_ENABLED
import com.junkfood.seal.ai.TranscriptionOutputFormat
import com.junkfood.seal.ai.TranscriptionState
import com.junkfood.seal.ui.common.booleanState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferenceSingleChoiceItem
import com.junkfood.seal.ui.component.PreferenceSubtitle
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.component.PreferenceSwitchWithContainer
import com.junkfood.seal.ui.component.PreferencesHintCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AISettings(
    aiManager: AIManager,
    onNavigateBack: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
        canScroll = { true }
    )

    var transcriptionEnabled by TRANSCRIPTION_ENABLED.booleanState
    var autoTranscribeEnabled by AUTO_TRANSCRIBE.booleanState

    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showOutputFormatDialog by remember { mutableStateOf(false) }

    val transcriptionState by aiManager.transcriber.state.collectAsState()
    val transcriptionHistory by aiManager.transcriber.transcriptionHistory.collectAsState()

    val isApiKeyConfigured = aiManager.transcriber.isConfigured()
    val currentLanguage = aiManager.getTranscriptionLanguage()
    val currentOutputFormat = aiManager.getOutputFormat()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = "AI Features") },
                navigationIcon = { BackButton { onNavigateBack() } },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            // Main toggle
            item {
                PreferenceSwitchWithContainer(
                    title = "Enable AI Transcription",
                    icon = Icons.Outlined.Psychology,
                    isChecked = transcriptionEnabled,
                    onClick = {
                        transcriptionEnabled = !transcriptionEnabled
                        aiManager.setTranscriptionEnabled(transcriptionEnabled)
                    }
                )
            }

            item {
                PreferenceInfo(
                    text = "Use Google Gemini AI to transcribe audio and video content. Requires a Gemini API key."
                )
            }

            // API Key Configuration
            item {
                PreferenceSubtitle(text = "API Configuration")
            }

            item {
                PreferenceItem(
                    title = "Gemini API Key",
                    description = if (isApiKeyConfigured) "API key configured" else "Tap to enter your API key",
                    icon = Icons.Outlined.Key,
                    trailingIcon = if (isApiKeyConfigured) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "Configured",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                ) {
                    showApiKeyDialog = true
                }
            }

            if (!isApiKeyConfigured) {
                item {
                    PreferencesHintCard(
                        title = "API Key Required",
                        description = "Get your free API key from Google AI Studio",
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = { showApiKeyDialog = true }
                    )
                }
            }

            // Transcription Settings
            item {
                PreferenceSubtitle(text = "Transcription Settings")
            }

            item {
                PreferenceSwitch(
                    title = "Auto-transcribe downloads",
                    description = "Automatically transcribe audio/video after downloading",
                    icon = Icons.Outlined.AutoAwesome,
                    isChecked = autoTranscribeEnabled,
                    enabled = isApiKeyConfigured && transcriptionEnabled
                ) {
                    autoTranscribeEnabled = !autoTranscribeEnabled
                    aiManager.setAutoTranscribeEnabled(autoTranscribeEnabled)
                }
            }

            item {
                val languageName = AIManager.SUPPORTED_LANGUAGES.find { it.first == currentLanguage }?.second ?: "English"
                PreferenceItem(
                    title = "Transcription Language",
                    description = languageName,
                    icon = Icons.Outlined.Language,
                    enabled = transcriptionEnabled
                ) {
                    showLanguageDialog = true
                }
            }

            item {
                val formatName = when (currentOutputFormat) {
                    TranscriptionOutputFormat.PLAIN_TEXT -> "Plain Text (.txt)"
                    TranscriptionOutputFormat.SRT -> "SubRip (.srt)"
                    TranscriptionOutputFormat.VTT -> "WebVTT (.vtt)"
                }
                PreferenceItem(
                    title = "Output Format",
                    description = formatName,
                    icon = Icons.Outlined.Subtitles,
                    enabled = transcriptionEnabled
                ) {
                    showOutputFormatDialog = true
                }
            }

            // Recent Transcriptions
            if (transcriptionHistory.isNotEmpty()) {
                item {
                    PreferenceSubtitle(text = "Recent Transcriptions")
                }

                items(transcriptionHistory.takeLast(5).reversed()) { result ->
                    PreferenceItem(
                        title = result.sourceFileName,
                        description = "${result.wordCount} words - ${result.language.uppercase()}",
                        icon = Icons.Outlined.TextFields
                    ) {
                        // Could show transcription details
                    }
                }
            }

            // Status indicator
            item {
                PreferenceSubtitle(text = "Status")
            }

            item {
                val statusText = when (transcriptionState) {
                    is TranscriptionState.Idle -> "Ready"
                    is TranscriptionState.Processing -> "Processing..."
                    is TranscriptionState.Success -> "Last transcription completed"
                    is TranscriptionState.Error -> "Error: ${(transcriptionState as TranscriptionState.Error).message}"
                }

                PreferenceInfo(
                    text = "Status: $statusText"
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = if (isApiKeyConfigured) "••••••••" else "",
            onDismiss = { showApiKeyDialog = false },
            onConfirm = { newKey ->
                aiManager.transcriber.setApiKey(newKey)
                aiManager.initialize()
                showApiKeyDialog = false
            }
        )
    }

    // Language Selection Dialog
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onLanguageSelected = { language ->
                aiManager.setTranscriptionLanguage(language)
                showLanguageDialog = false
            }
        )
    }

    // Output Format Dialog
    if (showOutputFormatDialog) {
        OutputFormatDialog(
            currentFormat = currentOutputFormat,
            onDismiss = { showOutputFormatDialog = false },
            onFormatSelected = { format ->
                aiManager.setOutputFormat(format)
                showOutputFormatDialog = false
            }
        )
    }
}

@Composable
fun ApiKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Gemini API Key") },
        text = {
            Column {
                Text(
                    text = "Get your free API key from Google AI Studio (aistudio.google.com)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(apiKey) },
                enabled = apiKey.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
        text = {
            LazyColumn {
                items(AIManager.SUPPORTED_LANGUAGES) { (code, name) ->
                    PreferenceSingleChoiceItem(
                        text = name,
                        selected = code == currentLanguage,
                        onClick = { onLanguageSelected(code) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun OutputFormatDialog(
    currentFormat: TranscriptionOutputFormat,
    onDismiss: () -> Unit,
    onFormatSelected: (TranscriptionOutputFormat) -> Unit
) {
    val formats = listOf(
        TranscriptionOutputFormat.PLAIN_TEXT to "Plain Text (.txt)",
        TranscriptionOutputFormat.SRT to "SubRip Subtitle (.srt)",
        TranscriptionOutputFormat.VTT to "WebVTT Subtitle (.vtt)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Output Format") },
        text = {
            Column {
                formats.forEach { (format, name) ->
                    PreferenceSingleChoiceItem(
                        text = name,
                        selected = format == currentFormat,
                        onClick = { onFormatSelected(format) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
