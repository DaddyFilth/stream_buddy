package com.streambuddy.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.cache.PreCacheWorker
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.StreamKind
import com.streambuddy.app.stream.StreamUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Player tab shows. */
data class PlaybackUiState(
    val streamId: Long? = null,
    val title: String = "",
    val url: String = "",
    val kind: StreamKind = StreamKind.UNKNOWN,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
) {
    val hasMedia: Boolean get() = streamId != null
}

/**
 * Single activity-scoped view model: owns the queue flow, the ExoPlayer instance and settings,
 * so playback survives tab switches and configuration changes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val dao = AppDb.get(app).streams()
    private val settingsRepository = SettingsRepository.get(app)

    val streams: StateFlow<List<StreamEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Cache-first ExoPlayer: cached segments are served from disk, misses are cached on the fly. */
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(CacheManager.playbackDataSource(app)))
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playback.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _playback.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    _playback.update {
                        it.copy(error = "${error.errorCodeName}$detail", isBuffering = false)
                    }
                }
            },
        )

        viewModelScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    _playback.update {
                        it.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = player.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }

        refreshCacheUsage()
    }

    // region queue

    /** Validates and enqueues a stream. Returns `false` when the URL was rejected. */
    fun addStream(rawUrl: String, rawTitle: String): Boolean {
        return when (val result = StreamUrl.validate(rawUrl)) {
            is StreamUrl.Result.Invalid -> {
                post(result.message)
                false
            }

            is StreamUrl.Result.Valid -> {
                viewModelScope.launch {
                    val title = rawTitle.trim().ifBlank {
                        StreamUrl.suggestTitle(result.url).ifBlank { "Untitled stream" }
                    }
                    val id = dao.insert(
                        StreamEntity(
                            title = title,
                            url = result.url,
                            kind = result.kind.name,
                            state = CacheState.QUEUED.name,
                            prefetchMillis = settings.value.prefetchMillis,
                        ),
                    )
                    PreCacheWorker.enqueue(app, id)
                    post("Queued “$title” for pre-play")
                }
                true
            }
        }
    }

    fun startCaching(stream: StreamEntity) {
        viewModelScope.launch {
            dao.resetForCaching(
                id = stream.id,
                prefetchMillis = settings.value.prefetchMillis,
                state = CacheState.QUEUED.name,
                now = System.currentTimeMillis(),
            )
            PreCacheWorker.enqueue(app, stream.id)
            post("Pre-caching “${stream.title}”")
        }
    }

    fun cancelCaching(stream: StreamEntity) {
        PreCacheWorker.cancel(app, stream.id)
        viewModelScope.launch {
            dao.updateState(
                id = stream.id,
                state = CacheState.QUEUED.name,
                error = null,
                now = System.currentTimeMillis(),
            )
        }
    }

    fun deleteStream(stream: StreamEntity) {
        PreCacheWorker.cancel(app, stream.id)
        if (_playback.value.streamId == stream.id) stopPlayback()
        viewModelScope.launch { dao.deleteById(stream.id) }
    }

    // endregion

    // region playback

    fun play(stream: StreamEntity) {
        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .apply {
                when (stream.streamKind) {
                    StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamKind.PROGRESSIVE, StreamKind.UNKNOWN -> Unit
                }
            }
            .build()

        _playback.value = PlaybackUiState(
            streamId = stream.id,
            title = stream.title,
            url = stream.url,
            kind = stream.streamKind,
        )
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun pausePlayback() {
        if (player.isPlaying) player.pause()
    }

    fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        _playback.value = PlaybackUiState()
    }

    fun seekTo(positionMs: Long) {
        if (_playback.value.hasMedia) player.seekTo(positionMs.coerceAtLeast(0L))
    }

    // endregion

    // region settings & cache

    fun setPrefetchMinutes(minutes: Int) = settingsRepository.setPrefetchMinutes(minutes)

    fun setPreferHighestQuality(preferHighest: Boolean) =
        settingsRepository.setPreferHighestQuality(preferHighest)

    fun refreshCacheUsage() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(Dispatchers.IO) { CacheManager.usageBytes(app) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CacheManager.clear(app) }
            _cacheBytes.value = 0L
            post("Cache cleared")
        }
    }

    // endregion

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    private fun post(message: String) {
        _messages.tryEmit(message)
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
    }
}
