package com.voiceprompter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceprompter.match.MatchMode
import com.voiceprompter.prompter.PrompterStatus
import com.voiceprompter.prompter.PrompterViewModel

@Composable
fun PrompterScreen(
    viewModel: PrompterViewModel,
    onRequestMicPermission: () -> Unit,
    hasMicPermission: Boolean,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.currentIndex) {
        if (state.phrases.isNotEmpty()) {
            listState.animateScrollToItem(state.currentIndex.coerceAtMost(state.phrases.lastIndex))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        StatusBanner(state.status, state.error, state.modelProgress)

        LinearProgressIndicator(
            progress = { state.phraseProgress },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.phrases) { index, phrase ->
                val isCurrent = index == state.currentIndex
                val isPast = index < state.currentIndex

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .padding(8.dp),
                ) {
                    Text(
                        text = phrase.text,
                        fontSize = if (isCurrent) 30.sp else 22.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        lineHeight = if (isCurrent) 38.sp else 30.sp,
                        color = when {
                            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                            isPast -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        if (state.lastHeard.isNotBlank()) {
            Text(
                "heard: ${state.lastHeard}  (${"%.2f".format(state.lastScore)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MatchMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.matchMode == mode,
                    onClick = { viewModel.setMatchMode(mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::previous) { Text("Back") }

            when {
                !hasMicPermission -> Button(
                    onClick = onRequestMicPermission,
                    modifier = Modifier.weight(1f),
                ) { Text("Allow microphone") }

                state.status == PrompterStatus.PAUSED -> Button(
                    onClick = viewModel::resume,
                    modifier = Modifier.weight(1f),
                ) { Text("Resume") }

                state.isRunning -> Button(
                    onClick = viewModel::pause,
                    modifier = Modifier.weight(1f),
                ) { Text("Pause") }

                else -> Button(
                    onClick = viewModel::start,
                    modifier = Modifier.weight(1f),
                ) { Text("Start") }
            }

            OutlinedButton(onClick = viewModel::next) { Text("Skip") }
        }
    }
}

@Composable
private fun StatusBanner(status: PrompterStatus, error: String?, modelProgress: Float) {
    val label = when (status) {
        PrompterStatus.IDLE -> "Ready"
        PrompterStatus.PREPARING ->
            if (modelProgress > 0f) {
                "Downloading speech model… ${(modelProgress * 100).toInt()}%"
            } else {
                "Preparing…"
            }
        PrompterStatus.SPEAKING -> "Reading aloud — microphone off"
        PrompterStatus.LISTENING -> "Listening — your turn"
        PrompterStatus.AD_LIB -> "Off script — holding position"
        PrompterStatus.PAUSED -> "Paused"
        PrompterStatus.FINISHED -> "End of script"
        PrompterStatus.ERROR -> error ?: "Something went wrong"
    }

    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (status == PrompterStatus.ERROR) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
