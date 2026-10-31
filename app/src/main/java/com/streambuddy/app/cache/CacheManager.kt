package com.streambuddy.app.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object CacheManager {
    private const val MAX_BYTES: Long = 2L * 1024 * 1024 * 1024 // 2 GB cap, LRU evicts oldest
    @Volatile private var cache: SimpleCache? = null

    fun get(c: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(c.cacheDir, "precache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(c)).also { cache = it }
    }

    fun isDrmProtected(playlistText: String): Boolean =
        playlistText.contains("KEYFORMAT=\"com.apple.streamingkeydelivery\"".toRegex()) ||
        playlistText.contains("urn:uuid:edef8ba9") || // Widevine in DASH
        playlistText.contains("cenc:default_KID")
}
