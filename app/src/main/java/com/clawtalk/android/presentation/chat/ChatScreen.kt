package com.clawtalk.android.presentation.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.clawtalk.android.R
import com.clawtalk.android.domain.model.Message
import com.clawtalk.android.domain.model.MessageRole
import com.clawtalk.android.voice.VoiceMessagePlayer
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    agentId: String,
    onBackClick: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val playingState by viewModel.voicePlayer.playingState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var isVoiceMode by remember { mutableStateOf(false) }
    var isHolding by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isCancelZone by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    // SpeechRecognizer for parallel transcription
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.updateTranscription(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    viewModel.updateTranscription(matches[0])
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

    // Recording timer
    LaunchedEffect(isHolding) {
        if (isHolding) {
            recordingSeconds = 0
            while (isHolding) {
                kotlinx.coroutines.delay(1000)
                if (isHolding) recordingSeconds++
            }
        }
    }

    LaunchedEffect(agentId) { viewModel.setAgentId(agentId) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = agentId.replace("-", " ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium
                        )
                        AnimatedContent(
                            targetState = uiState.isLoading,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "status"
                        ) { loading ->
                            Text(
                                text = if (loading) "typing..." else "online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty()) {
                        EmptyChat()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(items = messages, key = { it.id }) { message ->
                                if (message.isVoice && message.audioUrl != null) {
                                    VoiceMessageBubble(
                                        message = message,
                                        isPlaying = (playingState as? VoiceMessagePlayer.PlayingState.Playing)?.messageId == message.id,
                                        onPlay = { viewModel.playVoiceMessage(message.id, message.audioUrl) },
                                        onStop = { viewModel.stopVoicePlayback() }
                                    )
                                } else {
                                    MessageBubble(message = message)
                                }
                            }
                            if (uiState.isLoading) { item { TypingIndicator() } }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.error != null,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        uiState.error?.let { error ->
                            Snackbar(
                                modifier = Modifier.padding(16.dp),
                                action = { TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") } }
                            ) { Text(error) }
                        }
                    }
                }

                // Input bar
                ChatInputBar(
                    value = uiState.inputText,
                    onValueChange = viewModel::onInputTextChange,
                    onSend = { viewModel.sendMessage() },
                    isLoading = uiState.isLoading,
                    isVoiceMode = isVoiceMode,
                    onToggleVoiceMode = {
                        if (!hasAudioPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            isVoiceMode = !isVoiceMode
                        }
                    },
                    isHolding = isHolding,
                    isCancelZone = isCancelZone,
                    onHoldStart = {
                        if (hasAudioPermission) {
                            isHolding = true
                            isCancelZone = false
                            dragOffsetY = 0f
                            viewModel.startVoiceRecording()
                            // Start speech recognition in parallel for transcription
                            try {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                }
                                speechRecognizer.startListening(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    onHoldEnd = {
                        try { speechRecognizer.stopListening() } catch (_: Exception) {}
                        viewModel.stopVoiceRecording(cancelled = isCancelZone)
                        isHolding = false
                        recordingSeconds = 0
                    },
                    onDragY = { dy ->
                        dragOffsetY += dy
                        isCancelZone = dragOffsetY < -100f
                    }
                )
            }

            // Recording overlay
            if (isHolding) {
                RecordingOverlay(seconds = recordingSeconds, isCancelZone = isCancelZone)
            }
        }
    }
}

@Composable
fun VoiceMessageBubble(
    message: Message,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = alignment
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 150.dp, max = 250.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Stop button
            IconButton(
                onClick = { if (isPlaying) onStop() else onPlay() },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.2f))
            ) {
                if (isPlaying) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_stop),
                        contentDescription = "Stop",
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Waveform placeholder + duration
            Column(modifier = Modifier.weight(1f)) {
                // Simple waveform bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(24.dp)
                ) {
                    val heights = listOf(0.3f, 0.6f, 0.9f, 0.5f, 0.8f, 0.4f, 0.7f, 0.5f, 0.9f, 0.3f, 0.6f, 0.8f, 0.4f, 0.7f, 0.5f)
                    heights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight(h)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isPlaying) contentColor
                                    else contentColor.copy(alpha = 0.5f)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${message.audioDuration}\"",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingOverlay(seconds: Int, isCancelZone: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 1.3f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "pulseScale"
            )

            Box(
                modifier = Modifier.size(80.dp).scale(pulseScale).clip(CircleShape)
                    .background(if (isCancelZone) Color(0xFFE53935) else Color(0xFF0088CC)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mic),
                    contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = String.format("%d:%02d", seconds / 60, seconds % 60),
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isCancelZone) "↑ Release to cancel" else "↑ Slide up to cancel",
                color = if (isCancelZone) Color(0xFFFF5252) else Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(index * 200)),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .animateContentSize(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp).clip(
                RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                )
            ).background(bubbleColor).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = message.content, color = textColor, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatTime(message.timestamp),
                color = textColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
fun ChatInputBar(
    value: String, onValueChange: (String) -> Unit, onSend: () -> Unit,
    isLoading: Boolean, isVoiceMode: Boolean, onToggleVoiceMode: () -> Unit,
    isHolding: Boolean, isCancelZone: Boolean,
    onHoldStart: () -> Unit, onHoldEnd: () -> Unit, onDragY: (Float) -> Unit
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleVoiceMode) {
                AnimatedContent(
                    targetState = isVoiceMode,
                    transitionSpec = { scaleIn(tween(150)) + fadeIn(tween(150)) togetherWith scaleOut(tween(150)) + fadeOut(tween(150)) },
                    label = "modeToggle"
                ) { voice ->
                    if (voice) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard), contentDescription = "Keyboard", modifier = Modifier.size(24.dp))
                    } else {
                        Icon(painter = painterResource(id = R.drawable.ic_mic), contentDescription = "Voice", modifier = Modifier.size(24.dp))
                    }
                }
            }

            if (isVoiceMode) {
                Box(
                    modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isHolding) {
                                if (isCancelZone) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { onHoldStart(); tryAwaitRelease(); onHoldEnd() })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount -> onDragY(dragAmount.y) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isHolding) {
                            if (isCancelZone) "Release to cancel" else "Release to send"
                        } else "Hold to Talk",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isHolding && isCancelZone) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                OutlinedTextField(
                    value = value, onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }, maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (value.isNotBlank()) {
                    FilledIconButton(
                        onClick = onSend, enabled = !isLoading,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChat() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "🎙️", style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tap 🎤 to switch to voice\nor type a message",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
