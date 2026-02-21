package com.clawtalk.android.presentation.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.clawtalk.android.domain.model.VoiceState

@Composable
fun VoiceOverlay(
    viewModel: VoiceViewModel = hiltViewModel()
) {
    val voiceState by viewModel.voiceState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Voice state indicator
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        when (voiceState) {
                            is VoiceState.Listening -> Color.Blue
                            is VoiceState.Processing -> Color.Yellow
                            is VoiceState.Speaking -> Color.Green
                            is VoiceState.Error -> Color.Red
                            else -> Color.Gray
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (voiceState) {
                        is VoiceState.Listening -> "🎤"
                        is VoiceState.Processing -> "⏳"
                        is VoiceState.Speaking -> "🔊"
                        is VoiceState.Error -> "❌"
                        else -> "⏸"
                    },
                    style = MaterialTheme.typography.displayLarge
                )
            }

            // State text
            Text(
                text = when (voiceState) {
                    is VoiceState.Listening -> "Listening..."
                    is VoiceState.Processing -> "Processing..."
                    is VoiceState.Speaking -> "Speaking..."
                    is VoiceState.Error -> (voiceState as VoiceState.Error).message
                    else -> "Idle"
                },
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )

            // Control button
            if (voiceState is VoiceState.Listening) {
                Button(
                    onClick = { viewModel.stopListening() }
                ) {
                    Text("Stop")
                }
            } else if (voiceState is VoiceState.Idle) {
                Button(
                    onClick = { viewModel.startListening() }
                ) {
                    Text("Start Listening")
                }
            }
        }
    }
}
