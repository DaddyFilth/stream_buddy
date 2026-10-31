package com.streambuddy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.util.Format

/**
 * Cache-first playback: the ExoPlayer instance in [MainViewModel] reads through the same
 * [com.streambuddy.app.cache.CacheManager] store that pre-caching fills.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val streams by viewModel.streams.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Player",
                subtitle = "Cached segments play from disk; anything not cached yet streams " +
                    "normally and is cached as it goes.",
            )
        }

        item {
            if (playback.hasMedia) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            player = viewModel.player
                            useController = true
                        }
                    },
                    update = { view -> view.player = viewModel.player },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.Black),
                )
            } else {
                ElevatedCard(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    EmptyState(
                        title = "Nothing playing",
                        detail = "Pick a stream below, or tap Play on a queue entry.",
                    )
                }
            }
        }

        if (playback.hasMedia) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = playback.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = playback.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(playback.kind.label)
                            if (playback.durationMs > 0L) {
                                append(" · ")
                                append(Format.duration(playback.positionMs))
                                append(" / ")
                                append(Format.duration(playback.durationMs))
                            }
                            if (playback.isBuffering) append(" · buffering…")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            playback.error?.let { error ->
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = viewModel::togglePlayPause) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp).height(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (playback.isPlaying) "Pause" else "Play")
                    }
                    OutlinedButton(onClick = viewModel::stopPlayback) { Text("Stop") }
                }
            }

            if (playback.durationMs > 0L) {
                item {
                    LinearProgressIndicator(
                        progress = {
                            (playback.positionMs.toFloat() / playback.durationMs.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("From your queue", style = MaterialTheme.typography.titleMedium)
        }

        if (streams.isEmpty()) {
            item {
                EmptyState(
                    title = "Queue is empty",
                    detail = "Add a stream on the Queue tab and it will show up here.",
                )
            }
        } else {
            items(streams, key = { it.id }) { stream ->
                PlayerQueueRow(
                    stream = stream,
                    isCurrent = playback.streamId == stream.id,
                    onClick = { viewModel.play(stream) },
                )
            }
        }
    }
}

@Composable
private fun PlayerQueueRow(stream: StreamEntity, isCurrent: Boolean, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stream.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isCurrent) "Now playing" else stream.progressDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TextButton(onClick = onClick) { Text(if (isCurrent) "Restart" else "Play") }
        }
    }
}
