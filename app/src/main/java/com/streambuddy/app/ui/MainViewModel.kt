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
import com.streambuddy.app.ai.Prediction
import com.streambuddy.app.ai.WatchEvent
import com.streambuddy.app.cache.AppScope
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.cache.PreCacheWorker
import com.streambuddy.app.cache.SmartPrefetch
import com.streambuddy.app.cache.SmartPrefetchWorker
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.PredictorRepository
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.StreamKind
import com.streambuddy.app.stream.StreamUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
 * Single activity-scoped view model: owns the queue flow, the ExoPlayer instance and the settings,
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

    /** Best guesses for what will be played next, keyed by stream id (empty when smart is off). */
    private val _predictions = MutableStateFlow<Map<Long, Prediction>>(emptyMap())
    val predictions: StateFlow<Map<Long, Prediction>> = _predictions.asStateFlow()

    /** Learned-model status for the Settings screen. */
    val modelState = PredictorRepository.get(app).state

    /** The playback currently being watched, so a session can be recorded when it ends. */
    private var session: WatchSession? = null
    private var sessionMaxPositionMs = 0L

    private data class WatchSession(val streamId: Long, val title: String, val startedAtMs: Long)

    /**
     * Cache-first player. Every data source request goes through [CacheManager], so audio and video
     * the pre-cache job already fetched play straight from disk and anything missing is fetched and
     * written back as it plays.
     */
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(CacheManager.cacheDataSource(app)))
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
                    if (playbackState == Player.STATE_ENDED) finishSession()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    _playback.update {
                        it.copy(error = "${error.errorCodeName}$detail", isBuffering = false)
                    }
                }
            },
        )

        // ExoPlayer has no position callback, so poll while something is loaded.
        viewModelScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    val position = player.currentPosition.coerceAtLeast(0L)
                    if (position > sessionMaxPositionMs) sessionMaxPositionMs = position
                    _playback.update {
                        it.copy(
                            positionMs = position,
                            durationMs = player.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }

        refreshCacheUsage()
        if (settings.value.smartPrefetch) SmartPrefetchWorker.schedule(app)

        // Re-rank the queue whenever it changes, the settings change, or a session is recorded.
        viewModelScope.launch {
            combine(streams, settings) { queue, current -> queue to current }.collect { (queue, current) ->
                if (current.smartPrefetch && queue.isNotEmpty()) {
                    val ranked = withContext(Dispatchers.Default) { SmartPrefetch.rankQueue(app) }
                    _predictions.value = ranked.associateBy { it.candidate.streamId }
                } else {
                    _predictions.value = emptyMap()
                }
            }
        }
    }

    // region queue

    /** Validates and enqueues a stream, then starts pre-caching it. */
    fun addStream(rawUrl: String, rawTitle: String) {
        when (val result = StreamUrl.validate(rawUrl)) {
            is StreamUrl.Result.Invalid -> post(result.message)
            is StreamUrl.Result.Valid -> viewModelScope.launch {
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
                SmartPrefetchWorker.schedule(app)
                post("Queued “$title” for pre-play")
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

        finishSession()
        session = WatchSession(
            streamId = stream.id,
            title = stream.title,
            startedAtMs = System.currentTimeMillis(),
        )
        sessionMaxPositionMs = 0L

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
        if (!_playback.value.hasMedia) return
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
        finishSession()
        player.stop()
        player.clearMediaItems()
        _playback.value = PlaybackUiState()
    }

    /**
     * Records how much of the current stream was watched and lets the predictor refit.
     *
     * This is the only place watch data is produced: a session counts once, either when playback
     * ends, another stream starts, or the app is closed. Nothing leaves the device.
     */
    private fun finishSession() {
        val current = session ?: return
        session = null
        val watchedMs = sessionMaxPositionMs
        if (watchedMs < MIN_RECORDED_WATCH_MS) return

        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        val completed = durationMs > 0L && watchedMs >= (durationMs - COMPLETION_TOLERANCE_MS)
        val event = WatchEvent(
            streamId = current.streamId,
            title = current.title,
            startedAtMs = current.startedAtMs,
            watchedMs = watchedMs,
            durationMs = durationMs,
            completed = completed,
        )
        // Not viewModelScope: this also runs from onCleared, where that scope is already gone.
        AppScope.io.launch {
            SmartPrefetch.recordPlayback(app, event)
            if (_predictions.value.isNotEmpty()) {
                val ranked = SmartPrefetch.rankQueue(app)
                _predictions.value = ranked.associateBy { it.candidate.streamId }
            }
        }
    }

    // endregion

    // region settings & cache

    fun setPrefetchMinutes(minutes: Int) = settingsRepository.setPrefetchMinutes(minutes)

    fun setPreferHighestQuality(preferHighestQuality: Boolean) =
        settingsRepository.setPreferHighestQuality(preferHighestQuality)

    fun setSmartPrefetch(enabled: Boolean) {
        settingsRepository.setSmartPrefetch(enabled)
        if (enabled) SmartPrefetchWorker.schedule(app) else SmartPrefetchWorker.cancel(app)
        post(if (enabled) "Smart pre-cache on" else "Smart pre-cache off")
    }

    /** Runs a prediction pass now instead of waiting for the periodic job. */
    fun enhanceQueueNow() {
        SmartPrefetchWorker.runNow(app)
        post("Looking for what to cache next…")
    }

    fun resetLearning() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SmartPrefetch.clearHistory(app) }
            _predictions.value = emptyMap()
            post("Watch history cleared; the model starts from scratch")
        }
    }

    fun refreshCacheUsage() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { CacheManager.usageBytes(app) }
            _cacheBytes.value = bytes
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
        finishSession()
        player.release()
        super.onCleared()
    }

    private fun post(message: String) {
        _messages.tryEmit(message)
    }

    private companion object {
        const val POSITION_POLL_MS = 500L

        /** Shorter than this and it was a mis-tap, not a viewing decision. */
        const val MIN_RECORDED_WATCH_MS = 15_000L
        const val COMPLETION_TOLERANCE_MS = 10_000L
    }
}
