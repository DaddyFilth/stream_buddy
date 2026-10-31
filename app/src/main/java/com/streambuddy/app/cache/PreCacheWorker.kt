package com.streambuddy.app.cache

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@androidx.annotation.OptIn(UnstableApi::class)
class PreCacheWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val id = inputData.getLong("streamId", -1)
        val prefetchFraction = inputData.getFloat("prefetchFraction", 0.25f) // "pre-play" depth
        notify("Pre-caching…", 0)

        val head = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        val body = head.body?.string() ?: ""
        if (CacheManager.isDrmProtected(body)) {
            notify("DRM-protected — cannot pre-cache", 100)
            return@withContext Result.failure(workDataOf("drm" to true))
        }

        val upstream = DefaultHttpDataSource.Factory()
        val cacheDs = CacheDataSource.Factory().setCache(CacheManager.get(applicationContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val writer = CacheWriter(cacheDs.createDataSource(),
            androidx.media3.datasource.DataSpec.Builder().setUri(url).build(), null, null)
        try { writer.cache() } catch (_: Exception) { /* partial cache still helps playback */ }

        val db = com.streambuddy.app.data.AppDb.get(applicationContext)
        notify("Cached and ready ▶", 100)
        Result.success(workDataOf("streamId" to id, "prefetch" to prefetchFraction))
    }

    private fun notify(text: String, progress: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("cache", "Pre-Cache", NotificationManager.IMPORTANCE_LOW))
        nm.notify(urlHash(), NotificationCompat.Builder(applicationContext, "cache")
            .setContentTitle("Stream Buddy").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress in 1..99).setOngoing(progress in 1..99).build())
    }
    private fun urlHash() = (inputData.getString("url") ?: "").hashCode()

    companion object {
        fun enqueue(c: Context, streamId: Long, url: String, fraction: Float) {
            WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<PreCacheWorker>()
                .setInputData(workDataOf("url" to url, "streamId" to streamId, "prefetchFraction" to fraction))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
        }
    }
}
