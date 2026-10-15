package com.idlemaster.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.idlemaster.android.data.Settings
import com.idlemaster.android.engine.IdleEngine
import com.idlemaster.android.steam.SteamConnection
import com.idlemaster.android.steam.SteamWeb

class IdleMasterApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var steam: SteamConnection
        private set
    lateinit var web: SteamWeb
        private set
    lateinit var engine: IdleEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        steam = SteamConnection(settings)
        web = SteamWeb(steam)
        engine = IdleEngine(steam, web, settings)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_IDLE, getString(R.string.channel_idle), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, getString(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT)
        )

        // Sign in with the saved token right away so the main screen shows the account state.
        steam.connectSaved()
    }

    companion object {
        const val CHANNEL_IDLE = "idle"
        const val CHANNEL_EVENTS = "events"

        lateinit var instance: IdleMasterApp
            private set
    }
}
