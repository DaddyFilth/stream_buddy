package com.streambuddy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.ui.AppsScreen
import com.streambuddy.app.ui.MainViewModel
import com.streambuddy.app.ui.PlayerScreen
import com.streambuddy.app.ui.QueueScreen
import com.streambuddy.app.ui.SettingsScreen
import com.streambuddy.app.ui.StreamBuddyTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamBuddyTheme {
                StreamBuddyApp(viewModel = viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // No media session is published, so pause instead of playing audio unattended.
        if (!isChangingConfigurations) viewModel.pausePlayback()
    }
}

private data class TabSpec(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabSpec("apps", R.string.tab_apps, Icons.Filled.Home),
    TabSpec("queue", R.string.tab_queue, Icons.Filled.List),
    TabSpec("player", R.string.tab_player, Icons.Filled.PlayArrow),
    TabSpec("settings", R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
private fun StreamBuddyApp(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    RequestNotificationPermissionOnce()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { if (currentRoute != tab.route) navController.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "apps",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("apps") {
                AppsScreen(
                    onMessage = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                )
            }
            composable("queue") {
                QueueScreen(
                    viewModel = viewModel,
                    onPlay = { stream ->
                        viewModel.play(stream)
                        navController.switchTab("player")
                    },
                )
            }
            composable("player") { PlayerScreen(viewModel = viewModel) }
            composable("settings") { SettingsScreen(viewModel = viewModel) }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Asks for POST_NOTIFICATIONS once on Android 13+, so pre-cache progress notifications are
 * visible. Skipped entirely when the permission is already granted.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val repository = remember { SettingsRepository.get(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Progress notifications simply stay hidden when denied. */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && repository.shouldRequestNotificationPermission()) {
            repository.markNotificationPermissionAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
