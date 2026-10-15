package com.idlemaster.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.idlemaster.android.IdleMasterApp
import com.idlemaster.android.R
import com.idlemaster.android.data.Badge
import com.idlemaster.android.data.IdleMode
import com.idlemaster.android.engine.EngineState
import com.idlemaster.android.engine.Phase
import com.idlemaster.android.service.IdleService
import com.idlemaster.android.steam.ConnState
import com.idlemaster.android.steam.SteamStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(app: IdleMasterApp, onOpenSettings: () -> Unit, onOpenLog: () -> Unit) {
    val ctx = LocalContext.current
    val engine by app.engine.state.collectAsState()
    val steam by app.steam.status.collectAsState()
    val settings by app.settings.settings.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(true) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            batteryOk = isIgnoringBatteryOptimizations(ctx)
            app.steam.connectSaved()
        }
    }

    Scaffold(
        containerColor = SteamColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SteamColors.Surface),
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        val name = steam.personaName ?: steam.accountName
                        if (settings.showUsername && name != null) {
                            Text(name, style = MaterialTheme.typography.bodySmall, color = SteamColors.TextDim)
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (app.engine.isRunning) app.engine.onSettingsChanged() else IdleService.start(ctx) },
                    ) { Icon(Icons.Default.Refresh, stringResource(R.string.reload)) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.statistics)) },
                                onClick = { menu = false; showStats = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log)) },
                                onClick = { menu = false; onOpenLog() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sign_out)) },
                                onClick = {
                                    menu = false
                                    IdleService.stop(ctx)
                                    app.steam.logout()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionRow(steam) }
            if (steam.playingBlocked) item { WarningCard(stringResource(R.string.playing_blocked)) }
            if (!batteryOk) item { BatteryCard(ctx) }
            item { NowPlayingCard(app, engine, settings.mode) }

            val queue = engine.queue.filter { b -> engine.idling.none { it.appId == b.appId } }
            if (queue.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.queue_header, queue.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = SteamColors.TextDim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(queue, key = { it.appId }) { badge ->
                    GameRow(badge, onBlacklist = { app.engine.blacklist(badge.appId) })
                }
            }
        }
    }

    if (showStats) StatsDialog(app, engine, onDismiss = { showStats = false })
}

@Composable
private fun ConnectionRow(steam: SteamStatus) {
    val (color, text) = when (steam.state) {
        ConnState.ONLINE -> SteamColors.Green to stringResource(R.string.conn_online)
        ConnState.RECONNECTING -> SteamColors.Warning to stringResource(R.string.conn_reconnecting)
        ConnState.OFFLINE -> SteamColors.Error to stringResource(R.string.conn_offline)
        else -> SteamColors.Warning to stringResource(R.string.conn_connecting)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        steam.lastError?.takeIf { steam.state != ConnState.ONLINE }?.let {
            Text("  ·  $it", style = MaterialTheme.typography.bodySmall, color = SteamColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A3220))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = SteamColors.Warning)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BatteryCard(ctx: Context) {
    Card(colors = CardDefaults.cardColors(containerColor = SteamColors.Surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.battery_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.battery_text), style = MaterialTheme.typography.bodySmall, color = SteamColors.TextDim)
            OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) {
                Text(stringResource(R.string.battery_button))
            }
        }
    }
}

@Composable
private fun NowPlayingCard(app: IdleMasterApp, e: EngineState, mode: IdleMode) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = SteamColors.Surface)) {
        Column {
            val current = e.current
            if (current != null && e.phase == Phase.IDLING) {
                AsyncImage(
                    model = current.headerImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(460f / 215f).background(SteamColors.SurfaceHigh),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phaseLabel(e), style = MaterialTheme.typography.labelLarge, color = phaseColor(e.phase))

                if (current != null && e.phase == Phase.IDLING) {
                    Text(
                        current.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { openUrl(ctx, current.storeUrl) },
                    )
                    if (current.remainingCards > 0) Text(stringResource(R.string.drops_left_game, current.remainingCards))
                    if (mode != IdleMode.WHITELIST) {
                        Text(stringResource(R.string.hours_on_record, fmtHours(current.hoursPlayed)), color = SteamColors.TextDim)
                    }
                } else if (e.idling.isNotEmpty()) {
                    Text(stringResource(R.string.idling_now_header) + " (${e.idling.size})", fontWeight = FontWeight.SemiBold)
                    e.idling.take(40).forEach { b ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(b.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (mode != IdleMode.WHITELIST) Text(fmtHours(b.hoursPlayed), color = SteamColors.TextDim)
                        }
                    }
                } else if (e.phase == Phase.STOPPED && e.queue.isEmpty()) {
                    Text(stringResource(R.string.empty_queue), color = SteamColors.TextDim)
                }

                if (e.phase == Phase.LOADING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (e.timeLeft > 0 && e.phase == Phase.IDLING && mode != IdleMode.WHITELIST) {
                    Text(
                        stringResource(R.string.next_check, fmtTimer(e.timeLeft)) + "  (" + stringResource(R.string.tap_check_now) + ")",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteamColors.Blue,
                        modifier = Modifier.clickable { app.engine.checkNow() },
                    )
                }

                if (e.progressMax > 0 && mode != IdleMode.WHITELIST) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (e.progressMax - e.cardsRemaining).toFloat() / e.progressMax },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = SteamColors.Green,
                        trackColor = SteamColors.Outline,
                    )
                    Text(
                        stringResource(R.string.cards_remaining, e.cardsRemaining) + "  ·  " +
                            stringResource(R.string.games_left, e.gamesRemaining, e.idling.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = SteamColors.TextDim,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (e.phase) {
                        Phase.STOPPED, Phase.COMPLETE, Phase.ERROR -> Button(onClick = { IdleService.start(ctx) }) {
                            Text(stringResource(R.string.start))
                        }
                        Phase.PAUSED -> {
                            Button(onClick = { IdleService.start(ctx) }) { Text(stringResource(R.string.resume)) }
                            OutlinedButton(onClick = { IdleService.stop(ctx) }) { Text(stringResource(R.string.stop)) }
                        }
                        else -> {
                            Button(
                                onClick = { IdleService.pause(ctx) },
                                colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Outline, contentColor = SteamColors.Text),
                            ) { Text(stringResource(R.string.pause)) }
                            if (current != null && mode != IdleMode.FAST && mode != IdleMode.WHITELIST) {
                                OutlinedButton(onClick = { app.engine.skipCurrent() }) { Text(stringResource(R.string.skip)) }
                            }
                            OutlinedButton(onClick = { IdleService.stop(ctx) }) { Text(stringResource(R.string.stop)) }
                        }
                    }
                    if (e.phase == Phase.WAITING_STEAM || e.phase == Phase.NEXT_GAME) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (current != null && e.phase == Phase.IDLING && mode != IdleMode.WHITELIST) {
                    TextButton(onClick = { app.engine.blacklist(current.appId) }) {
                        Text(stringResource(R.string.to_blacklist), color = SteamColors.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRow(badge: Badge, onBlacklist: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SteamColors.Surface)
            .clickable { menu = true }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = "https://cdn.akamai.steamstatic.com/steam/apps/${badge.appId}/capsule_sm_120.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(80.dp).aspectRatio(120f / 45f).clip(RoundedCornerShape(3.dp)).background(SteamColors.SurfaceHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(badge.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (badge.remainingCards > 0) stringResource(R.string.drops_left_game, badge.remainingCards) + "  ·  " else "") +
                    stringResource(R.string.hours_on_record, fmtHours(badge.hoursPlayed)),
                style = MaterialTheme.typography.bodySmall,
                color = SteamColors.TextDim,
            )
        }
        Box {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.open_store)) }, onClick = { menu = false; openUrl(ctx, badge.storeUrl) })
                DropdownMenuItem(text = { Text(stringResource(R.string.to_blacklist)) }, onClick = { menu = false; onBlacklist() })
            }
        }
    }
}

@Composable
private fun StatsDialog(app: IdleMasterApp, e: EngineState, onDismiss: () -> Unit) {
    val total by app.settings.stats.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.statistics)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.stats_session), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.stats_time, fmtMinutes(e.sessionMinutes)))
                Text(stringResource(R.string.stats_cards, e.sessionCards))
                Text(stringResource(R.string.stats_cards_left, e.cardsRemaining))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.stats_total), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.stats_time, fmtMinutes(total.totalMinutes)))
                Text(stringResource(R.string.stats_cards, total.totalCards))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun phaseLabel(e: EngineState): String = when (e.phase) {
    Phase.STOPPED -> stringResource(R.string.status_stopped)
    Phase.WAITING_STEAM -> stringResource(R.string.status_waiting_steam)
    Phase.LOADING -> when {
        e.timeLeft > 0 -> stringResource(R.string.status_retry, e.timeLeft)
        e.message != null -> stringResource(R.string.status_loading_page, e.message)
        else -> stringResource(R.string.status_loading)
    }
    Phase.IDLING -> stringResource(R.string.status_idling)
    Phase.NEXT_GAME -> stringResource(R.string.status_next_game)
    Phase.PAUSED -> stringResource(R.string.status_paused)
    Phase.COMPLETE -> stringResource(R.string.idling_complete)
    Phase.ERROR -> e.message ?: stringResource(R.string.error)
}

private fun phaseColor(p: Phase): Color = when (p) {
    Phase.IDLING -> SteamColors.Green
    Phase.ERROR -> SteamColors.Error
    Phase.COMPLETE -> SteamColors.Blue
    else -> SteamColors.TextDim
}

private fun fmtHours(h: Double): String =
    if (h == Math.floor(h)) h.toLong().toString() else String.format(java.util.Locale.US, "%.1f", h)

private fun fmtTimer(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

private fun fmtMinutes(min: Long): String = if (min >= 60) "${min / 60} ч ${min % 60} мин" else "$min мин"

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
