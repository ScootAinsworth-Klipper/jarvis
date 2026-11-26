package com.stark.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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

private val HudBg = Color(0xFF020710)
private val HudPanel = Color(0xFF061322)
private val HudCyan = Color(0xFF00E5FF)
private val HudCyanDim = Color(0xFF0B4F6C)
private val HudGold = Color(0xFFFFB300)
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
            .navigationBarsPadding()
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
                useOnDeviceNano = state.useOnDeviceNano,
                autoSpeak = state.autoSpeak,
                hasApiKey = state.apiKey.isNotBlank(),
                hasUpdate = state.availableUpdate != null,
                onToggleEngine = viewModel::toggleInferenceEngine,
                onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                onOpenHistory = viewModel::openHistoryDialog,
                onClear = viewModel::clearSession,
                onCheckUpdates = viewModel::openUpdateDialog,
                onOpenSettings = { showKeyDialog = true }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Center-screen Arc Reactor in background + translucent full-height chat overlay
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
            ) {
                ArcReactorView(
                    mode = state.reactorMode,
                    audioLevel = state.audioLevel,
                    modifier = Modifier.align(Alignment.Center)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        HudMessageCard(msg, userName = state.userName)
                    }
                }
            }

            if (state.partialTranscript.isNotBlank()) {
                Surface(
                    color = HudBg.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, HudCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "\"${state.partialTranscript}\"",
                        color = HudCyan,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                            "Enter command, ${state.userName}...",
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

            // Push-to-Talk Microphone Button (+ inline Mute button when speaking so chat height never shifts)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    onPressEnd = viewModel::stopPushToTalk,
                    modifier = Modifier.weight(1f)
                )

                if (state.reactorMode == ReactorMode.SPEAKING) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(58.dp)
                            .clip(RoundedCornerShape(29.dp))
                            .background(Color(0xFF3A1219))
                            .border(1.5.dp, Color(0xFFFF6E6E), RoundedCornerShape(29.dp))
                            .clickable(onClick = viewModel::stopSpeaking)
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Mute Voice",
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "MUTE",
                                color = Color(0xFFFF8A80),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showNamePrompt) {
        NamePromptDialog(
            currentName = state.userName,
            onConfirm = viewModel::saveUserName,
            onDismiss = viewModel::dismissNamePrompt
        )
    }

    if (state.showHistoryDialog) {
        ChatHistoryDialog(
            sessions = state.savedChats,
            onSelectSession = viewModel::restoreChatSession,
            onDismiss = viewModel::dismissHistoryDialog
        )
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            currentUserName = state.userName,
            currentKey = state.apiKey,
            currentGithubToken = state.githubToken,
            currentSpeakGreeting = state.speakGreetingOnLaunch,
            onDismiss = { showKeyDialog = false },
            onSave = { newName, newKey, newToken, newSpeakGreeting ->
                viewModel.updateCredentials(newName, newKey, newToken, newSpeakGreeting)
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

        // Stark visor corner brackets
        val pad = 10.dp.toPx()
        val len = 24.dp.toPx()
        val bracketColor = HudGold.copy(alpha = 0.55f)
        val stroke = 2.dp.toPx()
        // Top-left
        drawLine(bracketColor, Offset(pad, pad), Offset(pad + len, pad), strokeWidth = stroke)
        drawLine(bracketColor, Offset(pad, pad), Offset(pad, pad + len), strokeWidth = stroke)
        // Top-right
        drawLine(bracketColor, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), strokeWidth = stroke)
        drawLine(bracketColor, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), strokeWidth = stroke)
        // Bottom-left
        drawLine(bracketColor, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), strokeWidth = stroke)
        drawLine(bracketColor, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), strokeWidth = stroke)
        // Bottom-right
        drawLine(bracketColor, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), strokeWidth = stroke)
        drawLine(bracketColor, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), strokeWidth = stroke)
    }
}

@Composable
private fun HudHeader(
    statusText: String,
    voiceLabel: String,
    useOnDeviceNano: Boolean,
    autoSpeak: Boolean,
    hasApiKey: Boolean,
    hasUpdate: Boolean,
    onToggleEngine: () -> Unit,
    onToggleAutoSpeak: () -> Unit,
    onOpenHistory: () -> Unit,
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "J.A.R.V.I.S. v${BuildConfig.VERSION_NAME}",
                    color = HudCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onToggleAutoSpeak, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (autoSpeak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Toggle voice output",
                        tint = if (autoSpeak) HudCyan else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = onOpenHistory, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Recent chats",
                        tint = HudCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "New chat",
                        tint = HudCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = onCheckUpdates, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Check for updates",
                        tint = if (hasUpdate) Color(0xFF00FFCC) else HudCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Preferences & Keys",
                        tint = if (hasApiKey || useOnDeviceNano) HudCyan else Color(0xFFFFB74D),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (useOnDeviceNano) Color(0xFF004D40) else HudCyanDim.copy(alpha = 0.65f))
                        .border(
                            1.dp,
                            if (useOnDeviceNano) Color(0xFF00FFCC) else HudCyan.copy(alpha = 0.75f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(onClick = onToggleEngine)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (useOnDeviceNano) "NANO (LOCAL)" else "CLOUD API",
                        color = if (useOnDeviceNano) Color(0xFF00FFCC) else HudCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = statusText,
                    color = HudText.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "TTS: $voiceLabel",
                color = HudCyan.copy(alpha = 0.65f),
                fontSize = 9.sp,
                maxLines = 1,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HudMessageCard(message: HudMessage, userName: String) {
    val borderColor = if (message.isUser) {
        HudCyan.copy(alpha = 0.45f)
    } else {
        HudGold.copy(alpha = 0.65f)
    }
    val bgColor = if (message.isUser) {
        Color(0xFF071B2E).copy(alpha = 0.48f)
    } else {
        Color(0xFF141108).copy(alpha = 0.52f)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = if (message.isUser) userName.uppercase() else "J.A.R.V.I.S.",
                color = if (message.isUser) Color(0xFF80D8FF) else HudGold,
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
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glowColor = if (isListening) Color(0xFF00FFCC) else HudCyan
    val containerColor = if (isListening) Color(0xFF004D61) else HudPanel
    val currentListening by rememberUpdatedState(isListening)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(containerColor)
            .border(1.5.dp, glowColor, RoundedCornerShape(29.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (currentListening) {
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
private fun NamePromptDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draftName by remember(currentName) {
        mutableStateOf(if (currentName == "Sir") "" else currentName)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HudPanel,
        titleContentColor = HudCyan,
        textContentColor = HudText,
        title = {
            Text(
                "IDENTITY CALIBRATION",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text(
                    "How should J.A.R.V.I.S. address you? (Leave blank for 'Sir')",
                    fontSize = 12.sp,
                    color = HudText.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("Your Name", color = HudCyan) },
                    placeholder = { Text("e.g. Alex, Tony, Sir", color = HudCyan.copy(alpha = 0.45f)) },
                    singleLine = true,
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
                onClick = { onConfirm(draftName) },
                colors = ButtonDefaults.buttonColors(containerColor = HudCyan, contentColor = HudBg)
            ) {
                Text("CONFIRM", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("USE SIR", color = HudCyan, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun ChatHistoryDialog(
    sessions: List<SavedChatSession>,
    onSelectSession: (SavedChatSession) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HudPanel,
        titleContentColor = HudCyan,
        textContentColor = HudText,
        title = {
            Text(
                "RECENT CHATS (LAST 5)",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    "No saved conversations yet.",
                    fontSize = 13.sp,
                    color = HudText.copy(alpha = 0.8f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessions.forEach { session ->
                        Surface(
                            onClick = { onSelectSession(session) },
                            color = HudBg.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, HudCyan.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = session.title,
                                    color = HudText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${session.messages.size} messages",
                                    color = HudCyan.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = HudCyan, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun ApiKeyDialog(
    currentUserName: String,
    currentKey: String,
    currentGithubToken: String,
    currentSpeakGreeting: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var draftName by remember(currentUserName) { mutableStateOf(currentUserName) }
    var draftKey by remember(currentKey) { mutableStateOf(currentKey) }
    var draftToken by remember(currentGithubToken) { mutableStateOf(currentGithubToken) }
    var draftSpeakGreeting by remember(currentSpeakGreeting) { mutableStateOf(currentSpeakGreeting) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HudPanel,
        titleContentColor = HudCyan,
        textContentColor = HudText,
        title = {
            Text(
                "HUD PREFERENCES & KEYS",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("Address Me As", color = HudCyan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HudText,
                        unfocusedTextColor = HudText,
                        focusedBorderColor = HudCyan,
                        unfocusedBorderColor = HudCyanDim
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speak Opening Greeting",
                        color = HudText,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draftSpeakGreeting,
                        onCheckedChange = { draftSpeakGreeting = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = HudBg,
                            checkedTrackColor = HudCyan,
                            uncheckedThumbColor = HudCyan,
                            uncheckedTrackColor = HudPanel
                        )
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Gemini API key is optional when using on-device NANO mode.",
                    fontSize = 11.sp,
                    color = HudText.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it },
                    label = { Text("Gemini API Key (Cloud)", color = HudCyan) },
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
                    label = { Text("GitHub Token (Updates)", color = HudCyan) },
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
                onClick = { onSave(draftName, draftKey, draftToken, draftSpeakGreeting) },
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
