package com.streambuddy.app.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource

/**
 * Media data source used for playback: media segments go through the cache, manifests do not.
 *
 * ExoPlayer loads HLS playlists and DASH manifests with an unknown length. If those responses
 * were written to the cache, every refresh (live HLS reloads its media playlist every target
 * duration) would be served from the stale cached copy and playback would stall. Manifests are
 * therefore routed to a plain HTTP data source while segments keep read-through caching.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackDataSourceFactory(
    private val cacheFactory: CacheDataSource.Factory,
    private val networkFactory: DefaultHttpDataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = PlaybackDataSource(
        cacheDataSource = cacheFactory.createDataSource(),
        networkDataSource = networkFactory.createDataSource(),
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
private class PlaybackDataSource(
    private val cacheDataSource: DataSource,
    private val networkDataSource: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cacheDataSource.addTransferListener(transferListener)
        networkDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (isManifest(dataSpec.uri)) networkDataSource else cacheDataSource
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val target = active ?: error("DataSource is not open")
        return target.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        val target = active ?: return
        active = null
        target.close()
    }

    private fun isManifest(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")
    }
}
