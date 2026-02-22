package com.clawtalk.android.presentation.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.clawtalk.android.R
import com.clawtalk.android.domain.model.Message
import com.clawtalk.android.domain.model.MessageRole
import com.clawtalk.android.voice.VoiceMessagePlayer
import java.io.File
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

    var isHolding by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isCancelZone by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var inputMode by remember { mutableStateOf(InputMode.IDLE) }

    // Forward dialog
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    // Media picker
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendMedia(it) }
    }

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendMedia(it) }
    }

    // Attachment menu
    var showAttachMenu by remember { mutableStateOf(false) }

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

    // Forward dialog
    if (showForwardDialog && messageToForward != null) {
        ForwardDialog(
            onDismiss = { showForwardDialog = false },
            onForward = { targetAgent ->
                messageToForward?.let { viewModel.forwardMessage(it, targetAgent) }
                showForwardDialog = false
            }
        )
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
                                MessageItem(
                                    message = message,
                                    isPlaying = (playingState as? VoiceMessagePlayer.PlayingState.Playing)?.messageId == message.id,
                                    onPlay = { message.audioUrl?.let { viewModel.playVoiceMessage(message.id, it) } },
                                    onStop = { viewModel.stopVoicePlayback() },
                                    onReply = { viewModel.setReplyTo(message) },
                                    onForward = {
                                        messageToForward = message
                                        showForwardDialog = true
                                    }
                                )
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

                // Reply bar
                androidx.compose.animation.AnimatedVisibility(visible = uiState.replyTo != null) {
                    uiState.replyTo?.let { replyMsg ->
                        ReplyBar(
                            replyTo = replyMsg,
                            onDismiss = { viewModel.clearReply() }
                        )
                    }
                }

                // Input bar
                ChatInputBar(
                    value = uiState.inputText,
                    onValueChange = viewModel::onInputTextChange,
                    onSend = { viewModel.sendMessage() },
                    isLoading = uiState.isLoading,
                    isHolding = isHolding,
                    isCancelZone = isCancelZone,
                    inputMode = inputMode,
                    onInputModeChange = { inputMode = it },
                    showAttachMenu = showAttachMenu,
                    onToggleAttachMenu = { showAttachMenu = !showAttachMenu },
                    onPickImage = {
                        showAttachMenu = false
                        mediaLauncher.launch("image/*")
                    },
                    onPickFile = {
                        showAttachMenu = false
                        fileLauncher.launch(arrayOf("*/*"))
                    },
                    onHoldStart = {
                        if (!hasAudioPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            isHolding = true
                            isCancelZone = false
                            dragOffsetY = 0f
                            viewModel.startVoiceRecording()
                        }
                    },
                    onHoldEnd = {
                        viewModel.stopVoiceRecording(cancelled = isCancelZone)
                        isHolding = false
                        recordingSeconds = 0
                    },
                    onDragY = { dy ->
                        dragOffsetY += dy
                        isCancelZone = dragOffsetY < -150f
                    }
                )
            }

            if (isHolding) {
                RecordingOverlay(seconds = recordingSeconds, isCancelZone = isCancelZone)
            }
        }
    }
}

// ── Reply Bar ────────────────────────────────────────────────────

@Composable
fun ReplyBar(replyTo: Message, onDismiss: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (replyTo.role == MessageRole.USER) "You" else "Agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = replyTo.content.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Message Item (with long press menu) ──────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: Message,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true }
            ),
        contentAlignment = if (message.role == MessageRole.USER) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start) {
            // Show quoted reply if present
            if (message.replyToContent != null) {
                QuotedReply(
                    content = message.replyToContent,
                    isFromUser = message.replyToRole == MessageRole.USER,
                    bubbleIsUser = message.role == MessageRole.USER
                )
            }

            // Render message content
            when {
                message.mediaType == "image" && message.mediaUrl != null -> {
                    ImageBubble(message = message)
                }
                message.mediaType == "file" -> {
                    FileBubble(message = message)
                }
                message.isVoice && message.audioUrl != null -> {
                    VoiceMessageBubble(
                        message = message,
                        isPlaying = isPlaying,
                        onPlay = onPlay,
                        onStop = onStop,
                        showTranscript = message.role == MessageRole.ASSISTANT
                    )
                }
                else -> {
                    MessageBubble(message = message)
                }
            }
        }

        // Long press context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = DpOffset(0.dp, 0.dp)
        ) {
            DropdownMenuItem(
                text = { Text("Reply") },
                onClick = { showMenu = false; onReply() },
                leadingIcon = { Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
            DropdownMenuItem(
                text = { Text("Forward") },
                onClick = { showMenu = false; onForward() },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }
    }
}

// ── Quoted Reply ─────────────────────────────────────────────────

@Composable
fun QuotedReply(content: String, isFromUser: Boolean, bubbleIsUser: Boolean) {
    val bgColor = if (bubbleIsUser)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)

    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = if (isFromUser) "You" else "Agent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = content.take(60),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (bubbleIsUser) Color.White.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

// ── Image Bubble ─────────────────────────────────────────────────

@Composable
fun ImageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                )
            )
            .background(bubbleColor)
            .padding(4.dp)
    ) {
        AsyncImage(
            model = File(message.mediaUrl!!),
            contentDescription = "Photo",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 260.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        if (message.content != "📷 Photo") {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Text(
            text = formatTime(message.timestamp),
            color = textColor.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 8.dp, bottom = 4.dp)
        )
    }
}

// ── File Bubble ──────────────────────────────────────────────────

@Composable
fun FileBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                )
            )
            .background(bubbleColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.7f),
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.mediaName ?: "File",
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (message.mediaSize > 0) {
                Text(
                    text = formatFileSize(message.mediaSize),
                    color = textColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = formatTime(message.timestamp),
                color = textColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ── Forward Dialog ───────────────────────────────────────────────

@Composable
fun ForwardDialog(onDismiss: () -> Unit, onForward: (String) -> Unit) {
    var targetAgent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward Message") },
        text = {
            Column {
                Text("Enter agent ID to forward to:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetAgent,
                    onValueChange = { targetAgent = it },
                    placeholder = { Text("Agent ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (targetAgent.isNotBlank()) onForward(targetAgent.trim()) },
                enabled = targetAgent.isNotBlank()
            ) { Text("Forward") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Input Mode ───────────────────────────────────────────────────

enum class InputMode { IDLE, LEFT, RIGHT }

// ── Chat Input Bar ───────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatInputBar(
    value: String, onValueChange: (String) -> Unit, onSend: () -> Unit,
    isLoading: Boolean, isHolding: Boolean, isCancelZone: Boolean,
    inputMode: InputMode, onInputModeChange: (InputMode) -> Unit,
    showAttachMenu: Boolean, onToggleAttachMenu: () -> Unit,
    onPickImage: () -> Unit, onPickFile: () -> Unit,
    onHoldStart: () -> Unit, onHoldEnd: () -> Unit, onDragY: (Float) -> Unit
) {
    val focusManager = LocalFocusManager.current

    val micSize by animateDpAsState(
        targetValue = if (inputMode == InputMode.IDLE) 64.dp else 48.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "micSize"
    )
    val holdScale by animateFloatAsState(
        targetValue = if (isHolding) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "holdScale"
    )
    val micIconSize by animateDpAsState(
        targetValue = if (inputMode == InputMode.IDLE) 32.dp else 24.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "micIconSize"
    )
    var lastY by remember { mutableFloatStateOf(0f) }

    @Composable
    fun MicButton() {
        Box(
            modifier = Modifier
                .size(micSize)
                .scale(holdScale)
                .clip(CircleShape)
                .background(
                    if (isHolding) {
                        if (isCancelZone) Color(0xFFE53935) else Color(0xFF0088CC)
                    } else Color(0xFF0088CC)
                )
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { lastY = event.y; onHoldStart(); true }
                        MotionEvent.ACTION_MOVE -> { val dy = event.y - lastY; lastY = event.y; onDragY(dy); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onHoldEnd(); true }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_mic),
                contentDescription = "Hold to record", tint = Color.White,
                modifier = Modifier.size(micIconSize)
            )
        }
    }

    @Composable
    fun SmallMicButton() {
        FilledIconButton(
            onClick = { focusManager.clearFocus(); onInputModeChange(InputMode.IDLE) },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF0088CC))
        ) {
            Icon(painter = painterResource(id = R.drawable.ic_mic), contentDescription = "Back to voice",
                tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }

    @Composable
    fun RowScope.TextFieldWithSend() {
        // Attach button
        Box {
            IconButton(onClick = onToggleAttachMenu, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = showAttachMenu,
                onDismissRequest = onToggleAttachMenu
            ) {
                DropdownMenuItem(
                    text = { Text("📷 Photo") },
                    onClick = onPickImage
                )
                DropdownMenuItem(
                    text = { Text("📎 File") },
                    onClick = onPickFile
                )
            }
        }

        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") }, maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            ),
            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSend() })
        )
        if (value.isNotBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend, enabled = !isLoading, modifier = Modifier.size(48.dp),
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

    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            when (inputMode) {
                InputMode.IDLE -> {
                    TextButton(onClick = { onInputModeChange(InputMode.LEFT) }, modifier = Modifier.weight(1f)) {
                        Text("Type message...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    MicButton()
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onInputModeChange(InputMode.RIGHT) }, modifier = Modifier.weight(1f)) {
                        Text("Type message...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
                InputMode.LEFT -> {
                    TextFieldWithSend()
                    Spacer(modifier = Modifier.width(6.dp))
                    SmallMicButton()
                }
                InputMode.RIGHT -> {
                    SmallMicButton()
                    Spacer(modifier = Modifier.width(6.dp))
                    TextFieldWithSend()
                }
            }
        }
    }
}

// ── Voice Message Bubble ─────────────────────────────────────────

@Composable
fun VoiceMessageBubble(
    message: Message, isPlaying: Boolean, onPlay: () -> Unit, onStop: () -> Unit,
    showTranscript: Boolean = false
) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .widthIn(min = 150.dp, max = 250.dp)
            .clip(RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ))
            .background(bubbleColor)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { if (isPlaying) onStop() else onPlay() },
            modifier = Modifier.size(40.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.2f))
        ) {
            if (isPlaying) {
                Icon(painter = painterResource(id = R.drawable.ic_stop), contentDescription = "Stop", tint = contentColor, modifier = Modifier.size(24.dp))
            } else {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = contentColor, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
                val heights = listOf(0.3f, 0.6f, 0.9f, 0.5f, 0.8f, 0.4f, 0.7f, 0.5f, 0.9f, 0.3f, 0.6f, 0.8f, 0.4f, 0.7f, 0.5f)
                heights.forEach { h ->
                    Box(modifier = Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp))
                        .background(if (isPlaying) contentColor else contentColor.copy(alpha = 0.5f)))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "${message.audioDuration}\"", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
                Text(text = formatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.6f))
            }
            if (showTranscript && message.content.isNotBlank() && !message.content.startsWith("🎤")) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = message.content, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.8f), maxLines = 3)
            }
        }
    }
}

// ── Text Message Bubble ──────────────────────────────────────────

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.widthIn(max = 300.dp).clip(
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp)
        ).background(bubbleColor).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = message.content, color = textColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = formatTime(message.timestamp), color = textColor.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
    }
}

// ── Other composables ────────────────────────────────────────────

@Composable
fun RecordingOverlay(seconds: Int, isCancelZone: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 1.3f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseScale"
            )
            Box(
                modifier = Modifier.size(80.dp).scale(pulseScale).clip(CircleShape)
                    .background(if (isCancelZone) Color(0xFFE53935) else Color(0xFF0088CC)),
                contentAlignment = Alignment.Center
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_mic), contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = String.format("%d:%02d", seconds / 60, seconds % 60), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isCancelZone) "↑ Release to cancel" else "↑ Slide up to cancel",
                color = if (isCancelZone) Color(0xFFFF5252) else Color.White.copy(alpha = 0.8f), fontSize = 14.sp
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(index * 200)), label = "dot_$index")
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
        }
    }
}

@Composable
fun EmptyChat() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "🎙️", style = MaterialTheme.typography.displayLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Hold the mic button to talk\nor tap to type a message",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
