package com.streambuddy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.*
import com.streambuddy.app.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0D1117))) {
                val nav = rememberNavController()
                val tabs = listOf("apps" to "Apps", "queue" to "Queue", "player" to "Player", "settings" to "Settings")
                Scaffold(bottomBar = {
                    NavigationBar {
                        val cur = nav.currentBackStackEntryAsState().value?.destination?.route
                        tabs.forEach { (route, label) ->
                            NavigationBarItem(selected = cur == route,
                                onClick = { nav.navigate(route) { launchSingleTop = true } },
                                icon = {}, label = { Text(label) })
                        }
                    }
                }) { pad ->
                    NavHost(nav, "apps", Modifier.padding(pad)) {
                        composable("apps") { AppsScreen() }
                        composable("queue") { QueueScreen() }
                        composable("player") { PlayerScreen() }
                        composable("settings") { SettingsScreen() }
                    }
                }
            }
        }
    }
}
