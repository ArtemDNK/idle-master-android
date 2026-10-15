package com.idlemaster.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.idlemaster.android.IdleMasterApp
import com.idlemaster.android.R
import com.idlemaster.android.engine.EngineState
import com.idlemaster.android.engine.Phase
import com.idlemaster.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and the Steam connection) alive while idling —
 * the Android counterpart of the desktop "prevent sleep" option.
 */
class IdleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastPhase: Phase? = null
    /** Set once the engine reported an active phase since this service started. */
    private var sawActive = false
    private var stopRequested = false

    private val app get() = application as IdleMasterApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(buildNotification(app.engine.state.value))
        when (intent?.action) {
            ACTION_PAUSE -> app.engine.pause()
            ACTION_STOP -> {
                stopRequested = true
                app.engine.stop()
            }
            else -> {
                // ACTION_START, or a restart by the system after the process was killed
                stopRequested = false
                app.engine.resume()
            }
        }
        observe()
        return START_STICKY
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            combine(app.engine.state, app.steam.status) { e, _ -> e }.collect { state ->
                val nm = getSystemService(NotificationManager::class.java)
                when (state.phase) {
                    Phase.STOPPED, Phase.COMPLETE, Phase.ERROR -> {
                        if (sawActive || stopRequested) {
                            if (sawActive && state.phase == Phase.COMPLETE) {
                                nm.notify(NOTIF_EVENT, eventNotification(getString(R.string.idling_complete)))
                            }
                            if (sawActive && state.phase == Phase.ERROR) {
                                nm.notify(NOTIF_EVENT, eventNotification(state.message ?: getString(R.string.error)))
                            }
                            shutdown()
                            return@collect
                        }
                    }
                    Phase.PAUSED -> {
                        sawActive = true
                        releaseLocks()
                    }
                    else -> {
                        sawActive = true
                        acquireLocks()
                    }
                }
                lastPhase = state.phase
                nm.notify(NOTIF_ID, buildNotification(state))
            }
        }
    }

    private fun startInForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun shutdown() {
        sawActive = false
        stopRequested = false
        observeJob?.cancel()
        observeJob = null
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdleMaster:idle")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IdleMaster:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notifications

    private fun buildNotification(state: EngineState): Notification {
        val title = when (state.phase) {
            Phase.IDLING -> state.current?.name
                ?: getString(R.string.notif_idling_many, state.idling.size)
            Phase.LOADING -> getString(R.string.status_loading)
            Phase.WAITING_STEAM -> getString(R.string.status_waiting_steam)
            Phase.NEXT_GAME -> getString(R.string.status_next_game)
            Phase.PAUSED -> getString(R.string.status_paused)
            else -> getString(R.string.app_name)
        }
        val text = buildString {
            if (state.cardsRemaining > 0) append(getString(R.string.notif_cards_left, state.cardsRemaining, state.gamesRemaining))
            if (app.steam.status.value.playingBlocked) {
                if (isNotEmpty()) append(" · ")
                append(getString(R.string.playing_blocked_short))
            }
        }
        val b = NotificationCompat.Builder(this, IdleMasterApp.CHANNEL_IDLE)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
        if (state.progressMax > 0) {
            b.setProgress(state.progressMax, state.progressMax - state.cardsRemaining, false)
        }
        if (state.phase == Phase.PAUSED) {
            b.addAction(0, getString(R.string.resume), serviceIntent(ACTION_START, 1))
        } else {
            b.addAction(0, getString(R.string.pause), serviceIntent(ACTION_PAUSE, 2))
        }
        b.addAction(0, getString(R.string.stop), serviceIntent(ACTION_STOP, 3))
        return b.build()
    }

    private fun eventNotification(text: String): Notification =
        NotificationCompat.Builder(this, IdleMasterApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, IdleService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"
        private const val NOTIF_ID = 1
        private const val NOTIF_EVENT = 2

        fun start(context: Context) = send(context, ACTION_START)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, IdleService::class.java).setAction(action))
        }
    }
}
