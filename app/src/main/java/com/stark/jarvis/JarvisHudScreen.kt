package com.stark.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val HudBg = Color(0xFF030811)
private val HudPanel = Color(0xFF071526)
private val HudCyan = Color(0xFF00E5FF)
private val HudCyanDim = Color(0xFF0B4F6C)
private val HudText = Color(0xFFE0F7FA)

@Composable
fun JarvisHudScreen(viewModel: JarvisViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showKeyDialog by remember { mutableStateOf(false) }
    var typedCommand by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startPushToTalk()
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF02060D), HudBg, Color(0xFF04101E))
                )
            )
            .statusBarsPadding()
            .imePadding()
    ) {
        HudGridBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top HUD telemetry bar
            HudHeader(
                statusText = state.statusText,
                voiceLabel = state.voiceLabel,
                autoSpeak = state.autoSpeak,
                hasApiKey = state.apiKey.isNotBlank(),
                hasUpdate = state.availableUpdate != null,
                onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                onClear = viewModel::clearSession,
                onCheckUpdates = viewModel::openUpdateDialog,
                onOpenSettings = { showKeyDialog = true }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Center Arc Reactor Core
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                ArcReactorView(
                    mode = state.reactorMode,
                    audioLevel = state.audioLevel
                )
            }

            if (state.partialTranscript.isNotBlank()) {
                Text(
                    text = "\"${state.partialTranscript}\"",
                    color = HudCyan,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (state.reactorMode == ReactorMode.SPEAKING) {
                TextButton(
                    onClick = viewModel::stopSpeaking,
                    colors = ButtonDefaults.textButtonColors(contentColor = HudCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Speaking",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("MUTE VOICE", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            // Holographic Conversation Log
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    HudMessageCard(msg)
                }
            }

            // Text command bar + Push-to-Talk button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    placeholder = {
                        Text(
                            "Enter command, Sir...",
                            color = HudCyan.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (typedCommand.isNotBlank()) {
                                viewModel.submitPrompt(typedCommand)
                                typedCommand = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HudText,
                        unfocusedTextColor = HudText,
                        focusedBorderColor = HudCyan,
                        unfocusedBorderColor = HudCyanDim,
                        cursorColor = HudCyan,
                        focusedContainerColor = HudPanel.copy(alpha = 0.7f),
                        unfocusedContainerColor = HudPanel.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        if (typedCommand.isNotBlank()) {
                            viewModel.submitPrompt(typedCommand)
                            typedCommand = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HudPanel)
                        .border(1.dp, HudCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send command",
                        tint = HudCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Push-to-Talk Microphone Button
            PushToTalkButton(
                isListening = state.reactorMode == ReactorMode.LISTENING,
                onPressStart = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        viewModel.startPushToTalk()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onPressEnd = viewModel::stopPushToTalk
            )
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            currentKey = state.apiKey,
            currentGithubToken = state.githubToken,
            onDismiss = { showKeyDialog = false },
            onSave = { newKey, newToken ->
                viewModel.updateCredentials(newKey, newToken)
                showKeyDialog = false
            }
        )
    }

    if (state.showUpdateDialog) {
        UpdateDialog(
            currentVersion = BuildConfig.VERSION_NAME,
            release = state.availableUpdate,
            isChecking = state.isCheckingUpdate,
            isDownloading = state.isDownloadingUpdate,
            progress = state.downloadProgress,
            message = state.updateMessage,
            onCheckAgain = { viewModel.checkForUpdates(silent = false) },
            onInstall = viewModel::installAvailableUpdate,
            onDismiss = viewModel::dismissUpdateDialog
        )
    }
}

@Composable
private fun HudGridBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 36.dp.toPx()
        val gridColor = Color(0xFF00E5FF).copy(alpha = 0.045f)
        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

@Composable
private fun HudHeader(
    statusText: String,
    voiceLabel: String,
    autoSpeak: Boolean,
    hasApiKey: Boolean,
    hasUpdate: Boolean,
    onToggleAutoSpeak: () -> Unit,
    onClear: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = HudPanel.copy(alpha = 0.78f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, HudCyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "J.A.R.V.I.S. // v${BuildConfig.VERSION_NAME}",
                    color = HudCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = statusText,
                    color = HudText.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "TTS: $voiceLabel",
                    color = HudCyan.copy(alpha = 0.65f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            IconButton(onClick = onToggleAutoSpeak) {
                Icon(
                    imageVector = if (autoSpeak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Toggle voice output",
                    tint = if (autoSpeak) HudCyan else Color.Gray
                )
            }

            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset session",
                    tint = HudCyan
                )
            }

            IconButton(onClick = onCheckUpdates) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "Check for updates",
                    tint = if (hasUpdate) Color(0xFF00FFCC) else HudCyan
                )
            }

            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Credentials",
                    tint = if (hasApiKey) HudCyan else Color(0xFFFFB74D)
                )
            }
        }
    }
}

@Composable
private fun HudMessageCard(message: HudMessage) {
    val borderColor = if (message.isUser) {
        Color(0xFF40C4FF).copy(alpha = 0.4f)
    } else {
        HudCyan.copy(alpha = 0.65f)
    }
    val bgColor = if (message.isUser) {
        Color(0xFF0A1E36).copy(alpha = 0.75f)
    } else {
        HudPanel.copy(alpha = 0.88f)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = if (message.isUser) "SIR" else "J.A.R.V.I.S.",
                color = if (message.isUser) Color(0xFF80D8FF) else HudCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = message.text,
                color = HudText,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun PushToTalkButton(
    isListening: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val glowColor = if (isListening) Color(0xFF00FFCC) else HudCyan
    val containerColor = if (isListening) Color(0xFF004D61) else HudPanel

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(containerColor)
            .border(1.5.dp, glowColor, RoundedCornerShape(29.dp))
            .pointerInput(isListening) {
                detectTapGestures(
                    onPress = {
                        if (isListening) {
                            onPressEnd()
                            tryAwaitRelease()
                        } else {
                            val startMs = System.currentTimeMillis()
                            onPressStart()
                            tryAwaitRelease()
                            val heldMs = System.currentTimeMillis() - startMs
                            if (heldMs > 350L) {
                                onPressEnd()
                            }
                        }
                    }
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(glowColor.copy(alpha = 0.18f))
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Push to talk",
                    tint = glowColor
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isListening) "LISTENING... TAP OR RELEASE TO SEND" else "HOLD OR TAP TO SPEAK",
                color = glowColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ApiKeyDialog(
    currentKey: String,
    currentGithubToken: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var draftKey by remember(currentKey) { mutableStateOf(currentKey) }
    var draftToken by remember(currentGithubToken) { mutableStateOf(currentGithubToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HudPanel,
        titleContentColor = HudCyan,
        textContentColor = HudText,
        title = {
            Text(
                "HUD CREDENTIALS",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text(
                    "Gemini API key is required for J.A.R.V.I.S. responses. GitHub token is optional and used for in-app updates from your private repo.",
                    fontSize = 12.sp,
                    color = HudText.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it },
                    label = { Text("Gemini API Key", color = HudCyan) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HudText,
                        unfocusedTextColor = HudText,
                        focusedBorderColor = HudCyan,
                        unfocusedBorderColor = HudCyanDim
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = draftToken,
                    onValueChange = { draftToken = it },
                    label = { Text("GitHub Token (for updates)", color = HudCyan) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HudText,
                        unfocusedTextColor = HudText,
                        focusedBorderColor = HudCyan,
                        unfocusedBorderColor = HudCyanDim
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draftKey, draftToken) },
                colors = ButtonDefaults.buttonColors(containerColor = HudCyan, contentColor = HudBg)
            ) {
                Text("SAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = HudCyan, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun UpdateDialog(
    currentVersion: String,
    release: AppReleaseInfo?,
    isChecking: Boolean,
    isDownloading: Boolean,
    progress: Float,
    message: String?,
    onCheckAgain: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = HudPanel,
        titleContentColor = HudCyan,
        textContentColor = HudText,
        title = {
            Text(
                "SYSTEM UPDATE // v$currentVersion",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        },
        text = {
            Column {
                if (message != null) {
                    Text(
                        text = message,
                        fontSize = 13.sp,
                        color = HudText
                    )
                }
                if (release != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = release.releaseNotes,
                        fontSize = 12.sp,
                        color = HudText.copy(alpha = 0.8f)
                    )
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        color = HudCyan,
                        trackColor = HudCyanDim,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (release != null) {
                Button(
                    onClick = onInstall,
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = HudCyan, contentColor = HudBg)
                ) {
                    Text(
                        if (isDownloading) "DOWNLOADING..." else "DOWNLOAD & INSTALL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onCheckAgain,
                    enabled = !isChecking,
                    colors = ButtonDefaults.buttonColors(containerColor = HudCyan, contentColor = HudBg)
                ) {
                    Text(
                        if (isChecking) "SCANNING..." else "CHECK NOW",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", color = HudCyan, fontFamily = FontFamily.Monospace)
                }
            }
        }
    )
}
