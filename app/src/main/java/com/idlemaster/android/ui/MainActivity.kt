package com.idlemaster.android.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.idlemaster.android.IdleMasterApp
import com.idlemaster.android.steam.ConnState

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            IdleMasterTheme {
                Box(Modifier.fillMaxSize().background(SteamColors.Background)) {
                    AppRoot(IdleMasterApp.instance)
                }
            }
        }
    }
}

private enum class Screen { MAIN, SETTINGS }

@Composable
private fun AppRoot(app: IdleMasterApp) {
    val status by app.steam.status.collectAsState()
    val signedIn = status.state == ConnState.ONLINE || app.settings.account != null
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }

    if (!signedIn) {
        LoginScreen(app.steam, app.settings)
        return
    }
    when (screen) {
        Screen.MAIN -> MainScreen(app, onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.MAIN }
            SettingsScreen(app, onBack = { screen = Screen.MAIN })
        }
    }
}
