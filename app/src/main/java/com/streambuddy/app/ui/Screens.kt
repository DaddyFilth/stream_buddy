package com.streambuddy.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.cache.PreCacheWorker
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.StreamEntity
import kotlinx.coroutines.launch

@Composable fun AppsScreen() {
    val ctx = LocalContext.current
    val videoApps = remember {
        ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { ctx.packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName.contains(Regex("netflix|hulu|disney|youtube|twitch|prime|max|peacock|plex|crunchyroll|tubi|paramount", RegexOption.IGNORE_CASE)) }
            .map { it.loadLabel(ctx.packageManager).toString() to it.packageName }
            .sortedBy { it.first }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Your Streaming Apps", style = MaterialTheme.typography.headlineSmall) }
        items(videoApps) { (name, pkg) ->
            ElevatedCard(Modifier.fillMaxWidth().clickable {
                ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg))
            }) { ListItem(headlineContent = { Text(name) }, supportingContent = { Text("Tap to launch • DRM apps can't be pre-cached") }) }
        }
    }
}

@Composable fun QueueScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDb.get(ctx) }
    val streams by db.streams().all().collectAsState(initial = emptyList())
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pre-Play Queue", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Stream URL (.m3u8 / .mpd / .mp4)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                val id = db.streams().insert(StreamEntity(title = title.ifBlank { "Untitled" }, url = url, cacheKey = url))
                PreCacheWorker.enqueue(ctx, id, url, 0.25f)
                url = ""; title = ""
            }
        }) { Text("Pre-Play ⬇") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(streams) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(s.title) }, supportingContent = { Text(s.url, maxLines = 1) })
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable fun PlayerScreen() {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    val streams by db.streams().all().collectAsState(initial = emptyList())
    var current by remember { mutableStateOf<StreamEntity?>(null) }

    val player = remember {
        val cacheDs = CacheDataSource.Factory().setCache(CacheManager.get(ctx))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(cacheDs)).build()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(current) {
        current?.let { player.setMediaItem(MediaItem.fromUri(it.url)); player.prepare(); player.playWhenReady = true }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Player (cache-first)", style = MaterialTheme.typography.headlineSmall)
        AndroidView(factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().height(220.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(streams) { s ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { current = s }) {
                    ListItem(headlineContent = { Text(s.title) }, supportingContent = { Text("Tap to play — cached segments load instantly") })
                }
            }
        }
    }
}

@Composable fun SettingsScreen() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings & Boost", style = MaterialTheme.typography.headlineSmall)
        Text("Cache cap: 2 GB (LRU eviction)")
        Text("Pre-play depth: 25% of stream")
        Text("Boost: cache-first playback + LRU eviction active")
        Text("DRM note: Widevine/PlayReady streams are launch-only.")
    }
}
