package com.idlemaster.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.idlemaster.android.data.DebugLog
import com.idlemaster.android.data.Settings
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
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
        DebugLog.install()
        installBouncyCastle()
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

    /**
     * Android ships a stripped-down provider registered as "BC", and since Android 9 it refuses
     * most ciphers. JavaSteam asks for "BC" by name and its own insertProviderAt() silently fails
     * because that name is taken — so the Steam channel handshake breaks and the connection drops.
     * Replace the platform provider with the full BouncyCastle before JavaSteam's CryptoHelper loads.
     */
    private fun installBouncyCastle() {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            DebugLog.i("App", "BouncyCastle provider: ${Security.getProvider("BC")?.javaClass?.name}")
        } catch (t: Throwable) {
            DebugLog.e("App", "BouncyCastle install failed", t)
        }
    }

    companion object {
        const val CHANNEL_IDLE = "idle"
        const val CHANNEL_EVENTS = "events"

        lateinit var instance: IdleMasterApp
            private set
    }
}
