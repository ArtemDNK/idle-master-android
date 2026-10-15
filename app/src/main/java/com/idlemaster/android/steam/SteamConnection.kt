package com.idlemaster.android.steam

import android.util.Base64
import android.util.Log
import com.idlemaster.android.data.Account
import com.idlemaster.android.data.Settings
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.SteamAuthentication
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.PlayingSessionStateCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

enum class ConnState { OFFLINE, CONNECTING, AUTHENTICATING, LOGGING_ON, ONLINE, RECONNECTING }

data class SteamStatus(
    val state: ConnState = ConnState.OFFLINE,
    val accountName: String? = null,
    val personaName: String? = null,
    val steamId: Long? = null,
    /** True while another device (your PC, Steam Deck…) is running a game on this account. Steam ignores our "playing" report then. */
    val playingBlocked: Boolean = false,
    val lastError: String? = null,
)

/** Questions Steam Guard asks during sign-in; the UI answers them through [SteamConnection]. */
sealed interface AuthPrompt {
    /** Steam Mobile app is linked: approve in the app, or type the code instead. */
    data object ChooseConfirmation : AuthPrompt
    data object WaitingForApproval : AuthPrompt
    data class DeviceCode(val previousWrong: Boolean) : AuthPrompt
    data class EmailCode(val email: String?, val previousWrong: Boolean) : AuthPrompt
}

/**
 * The Steam client session. This replaces two desktop pieces at once:
 *  - the running Steam client that Idle Master needed (`SteamAPI.IsSteamRunning`), and
 *  - `steam-idle.exe`, which pretended to be a game via steam_api.dll.
 * Here we log in over the Steam CM protocol (JavaSteam) and send `ClientGamesPlayed`,
 * which is exactly what the real client sends when a game starts.
 */
class SteamConnection(private val settings: Settings) : SteamWeb.WebTokenProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(SteamStatus(accountName = settings.account?.accountName))
    val status: StateFlow<SteamStatus> = _status.asStateFlow()

    private val _authPrompt = MutableStateFlow<AuthPrompt?>(null)
    val authPrompt: StateFlow<AuthPrompt?> = _authPrompt.asStateFlow()

    private var client: SteamClient? = null
    private var manager: CallbackManager? = null
    private var user: SteamUser? = null
    private var apps: SteamApps? = null
    private var friends: SteamFriends? = null
    private var auth: SteamAuthentication? = null
    private var callbackThread: Thread? = null

    @Volatile private var running = false
    /** Whether we should stay connected (reconnect after network drops). */
    @Volatile private var wantOnline = false
    @Volatile private var pendingCredentials: Pair<String, String>? = null
    @Volatile private var loginResult: CompletableDeferred<Result<Unit>>? = null
    @Volatile private var currentGames: List<Int> = emptyList()
    @Volatile private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    private val tokenMutex = Mutex()
    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiry: Long = 0L

    private var codeAnswer: CompletableFuture<String>? = null
    private var confirmAnswer: CompletableFuture<Boolean>? = null

    val isOnline: Boolean get() = _status.value.state == ConnState.ONLINE

    override val steamId: Long? get() = _status.value.steamId ?: settings.account?.steamId?.takeIf { it != 0L }

    // ------------------------------------------------------------------ public API

    /** Signs in with a saved refresh token (no password needed). Does nothing if there is no saved account. */
    fun connectSaved() {
        if (settings.account == null || wantOnline) return
        wantOnline = true
        startClientAndConnect()
    }

    /** Full sign-in with username + password; Steam Guard questions arrive through [authPrompt]. */
    suspend fun login(username: String, password: String): Result<Unit> {
        disconnectInternal(userInitiated = true)
        val deferred = CompletableDeferred<Result<Unit>>()
        loginResult = deferred
        pendingCredentials = username.trim() to password
        wantOnline = true
        settings.lastAccountName = username.trim()
        startClientAndConnect()
        return deferred.await()
    }

    fun cancelLogin() {
        codeAnswer?.cancel(true)
        confirmAnswer?.cancel(true)
        _authPrompt.value = null
        pendingCredentials = null
        wantOnline = false
        loginResult?.complete(Result.failure(Exception("cancelled")))
        loginResult = null
        disconnectInternal(userInitiated = true)
    }

    fun submitCode(code: String) {
        _authPrompt.value = null
        codeAnswer?.complete(code.trim().uppercase())
    }

    fun chooseConfirmation(approveInApp: Boolean) {
        _authPrompt.value = if (approveInApp) AuthPrompt.WaitingForApproval else null
        confirmAnswer?.complete(approveInApp)
    }

    fun logout() {
        currentGames = emptyList()
        wantOnline = false
        runCatching { if (isOnline) sendGamesPlayed() }
        runCatching { user?.logOff() }
        settings.account = null
        accessToken = null
        disconnectInternal(userInitiated = true)
        _status.value = SteamStatus()
    }

    /** "Start" these games on Steam (empty list = stop). Re-sent automatically after reconnects. */
    fun setGamesPlayed(appIds: List<Int>) {
        val list = appIds.distinct().take(Settings.MAX_STEAM_GAMES)
        if (list == currentGames) return
        currentGames = list
        if (isOnline) runCatching { sendGamesPlayed() }.onFailure { Log.w(TAG, "gamesPlayed", it) }
    }

    /** Suspends until the session is online (or [timeoutMs] passes). */
    suspend fun awaitOnline(timeoutMs: Long = Long.MAX_VALUE): Boolean =
        withTimeoutOrNull(timeoutMs) { status.first { it.state == ConnState.ONLINE } } != null

    override suspend fun webAccessToken(forceRefresh: Boolean): String? {
        tokenMutex.withLock {
            return refreshAccessTokenLocked(forceRefresh)
        }
    }

    private suspend fun refreshAccessTokenLocked(forceRefresh: Boolean): String? {
        val now = System.currentTimeMillis()
        val cached = accessToken
        if (!forceRefresh && cached != null && accessTokenExpiry - now > 30 * 60_000L) return cached

        if (!awaitOnline(60_000)) return cached
        val acc = settings.account ?: return null
        val sid = steamId ?: return null
        return try {
            val res = auth!!.generateAccessTokenForApp(SteamID(sid), acc.refreshToken, true).await()
            if (res.refreshToken.isNotEmpty()) settings.account = acc.copy(refreshToken = res.refreshToken)
            setAccessToken(res.accessToken)
            res.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "generateAccessTokenForApp failed", e)
            cached
        }
    }

    // ------------------------------------------------------------------ client lifecycle

    private fun startClientAndConnect() {
        reconnectJob?.cancel()
        if (client == null) {
            val c = SteamClient()
            val m = CallbackManager(c)
            client = c
            manager = m
            user = c.getHandler(SteamUser::class.java)
            apps = c.getHandler(SteamApps::class.java)
            friends = c.getHandler(SteamFriends::class.java)
            auth = SteamAuthentication(c)

            m.subscribe(ConnectedCallback::class.java) { onConnected() }
            m.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) }
            m.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
            m.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) }
            m.subscribe(AccountInfoCallback::class.java) { cb ->
                _status.update { it.copy(personaName = cb.personaName) }
            }
            m.subscribe(PlayingSessionStateCallback::class.java) { onPlayingSessionState(it) }

            running = true
            callbackThread = Thread({
                while (running) {
                    try {
                        m.runWaitCallbacks(1000L)
                    } catch (t: Throwable) {
                        Log.e(TAG, "callback loop", t)
                    }
                }
            }, "steam-callbacks").apply { isDaemon = true; start() }
        }
        _status.update { it.copy(state = ConnState.CONNECTING, lastError = null) }
        client!!.connect()
    }

    private fun disconnectInternal(userInitiated: Boolean) {
        reconnectJob?.cancel()
        if (userInitiated) wantOnline = false
        running = false
        runCatching { client?.disconnect() }
        callbackThread?.interrupt()
        callbackThread = null
        client = null; manager = null; user = null; apps = null; friends = null; auth = null
        _status.update { it.copy(state = ConnState.OFFLINE, playingBlocked = false) }
    }

    private fun scheduleReconnect() {
        if (!wantOnline) return
        val delays = longArrayOf(5, 10, 30, 60, 120)
        val wait = delays[reconnectAttempt.coerceAtMost(delays.lastIndex)]
        reconnectAttempt++
        _status.update { it.copy(state = ConnState.RECONNECTING) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait * 1000)
            if (wantOnline) {
                _status.update { it.copy(state = ConnState.CONNECTING) }
                runCatching { client?.connect() ?: startClientAndConnect() }
            }
        }
    }

    // ------------------------------------------------------------------ callbacks

    private fun onConnected() {
        val creds = pendingCredentials
        if (creds != null) {
            pendingCredentials = null
            scope.launch { authenticateWithCredentials(creds.first, creds.second) }
            return
        }
        val acc = settings.account
        if (acc == null) {
            wantOnline = false
            disconnectInternal(userInitiated = true)
            return
        }
        logOn(acc.accountName, acc.refreshToken)
    }

    private suspend fun authenticateWithCredentials(username: String, password: String) {
        _status.update { it.copy(state = ConnState.AUTHENTICATING, accountName = username) }
        try {
            val details = AuthSessionDetails().apply {
                this.username = username
                this.password = password
                persistentSession = true
                deviceFriendlyName = "Idle Master (Android)"
                guardData = settings.guardData(username)
                authenticator = UiAuthenticator()
            }
            val session = auth!!.beginAuthSessionViaCredentials(details).await()
            val poll = session.pollingWaitForResult().await()
            _authPrompt.value = null

            poll.newGuardData?.let { settings.setGuardData(poll.accountName, it) }
            settings.account = Account(poll.accountName, 0L, poll.refreshToken)
            setAccessToken(poll.accessToken)
            logOn(poll.accountName, poll.refreshToken)
        } catch (e: Exception) {
            _authPrompt.value = null
            val msg = when {
                e is AuthenticationException && e.result == EResult.InvalidPassword -> "Неверный логин или пароль"
                e is AuthenticationException && e.result == EResult.RateLimitExceeded -> "Слишком много попыток входа, подождите"
                e.cause is AuthenticationException -> e.cause?.message
                else -> e.message
            } ?: e.javaClass.simpleName
            Log.w(TAG, "credential auth failed", e)
            failLogin(msg)
        }
    }

    private fun logOn(accountName: String, refreshToken: String) {
        _status.update { it.copy(state = ConnState.LOGGING_ON, accountName = accountName) }
        val details = LogOnDetails(
            username = accountName,
            accessToken = refreshToken,
            shouldRememberPassword = true,
            // Distinct from the desktop client's 0 so both can be online from the same network.
            loginID = LOGIN_ID,
        )
        user!!.logOn(details)
    }

    private fun onLoggedOn(cb: LoggedOnCallback) {
        if (cb.result == EResult.OK) {
            reconnectAttempt = 0
            val sid = cb.clientSteamID?.convertToUInt64()
            settings.account?.let { acc -> if (sid != null) settings.account = acc.copy(steamId = sid) }
            _status.update {
                it.copy(state = ConnState.ONLINE, steamId = sid, lastError = null, playingBlocked = false)
            }
            runCatching { friends?.setPersonaState(EPersonaState.Online) }
            if (currentGames.isNotEmpty()) runCatching { sendGamesPlayed() }
            loginResult?.complete(Result.success(Unit))
            loginResult = null
            return
        }

        Log.w(TAG, "logOn failed: ${cb.result} / ${cb.extendedResult}")
        when (cb.result) {
            EResult.InvalidPassword, EResult.AccessDenied, EResult.Expired, EResult.Revoked,
            EResult.InvalidSignature, EResult.AccountLogonDenied -> {
                // Refresh token is no longer valid: need a fresh sign-in.
                settings.account = null
                failLogin("Сессия Steam истекла, войдите заново (${cb.result})")
            }
            else -> {
                _status.update { it.copy(lastError = "Steam: ${cb.result}") }
                if (loginResult != null) failLogin("Steam: ${cb.result}") else {
                    runCatching { client?.disconnect() }
                }
            }
        }
    }

    private fun onLoggedOff(cb: LoggedOffCallback) {
        Log.i(TAG, "logged off: ${cb.result}")
        _status.update { it.copy(state = ConnState.RECONNECTING, lastError = "Steam: ${cb.result}") }
        // Disconnected callback follows and schedules the reconnect.
        if (cb.result == EResult.LogonSessionReplaced || cb.result == EResult.LoggedInElsewhere) {
            reconnectAttempt = maxOf(reconnectAttempt, 3) // give the other session a minute
        }
    }

    private fun onDisconnected(cb: DisconnectedCallback) {
        Log.i(TAG, "disconnected, userInitiated=${cb.isUserInitiated}")
        _status.update { it.copy(state = ConnState.OFFLINE, playingBlocked = false) }
        if (loginResult != null && settings.account == null) {
            failLogin(_status.value.lastError ?: "Не удалось подключиться к Steam")
            return
        }
        if (!cb.isUserInitiated && wantOnline) scheduleReconnect()
    }

    private fun onPlayingSessionState(cb: PlayingSessionStateCallback) {
        val wasBlocked = _status.value.playingBlocked
        _status.update { it.copy(playingBlocked = cb.isPlayingBlocked) }
        if (wasBlocked && !cb.isPlayingBlocked && currentGames.isNotEmpty()) {
            runCatching { sendGamesPlayed() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun failLogin(message: String) {
        wantOnline = false
        _status.update { it.copy(state = ConnState.OFFLINE, lastError = message) }
        loginResult?.complete(Result.failure(Exception(message)))
        loginResult = null
        disconnectInternal(userInitiated = true)
    }

    private fun sendGamesPlayed() {
        val ownerId = (steamId?.let { SteamID(it).accountID } ?: 0L).toInt()
        val games = currentGames.map { appId ->
            GamePlayedInfo(gameId = appId.toLong(), processId = 0, ownerId = ownerId, gameBuildId = 0)
        }
        apps?.notifyGamesPlayed(games, Utils.getOSType())
    }

    private fun setAccessToken(token: String?) {
        accessToken = token
        accessTokenExpiry = token?.let(::jwtExpiryMillis) ?: 0L
    }

    /** Reads the `exp` claim of a Steam JWT (seconds since epoch). */
    private fun jwtExpiryMillis(jwt: String): Long = runCatching {
        val payload = jwt.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).getLong("exp") * 1000
    }.getOrDefault(System.currentTimeMillis() + 60 * 60_000L)

    private inner class UiAuthenticator : IAuthenticator {
        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            val f = CompletableFuture<String>()
            codeAnswer = f
            _authPrompt.value = AuthPrompt.DeviceCode(previousCodeWasIncorrect)
            return f
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            val f = CompletableFuture<String>()
            codeAnswer = f
            _authPrompt.value = AuthPrompt.EmailCode(email, previousCodeWasIncorrect)
            return f
        }

        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            val f = CompletableFuture<Boolean>()
            confirmAnswer = f
            _authPrompt.value = AuthPrompt.ChooseConfirmation
            return f
        }
    }

    companion object {
        private const val TAG = "SteamConnection"
        private const val LOGIN_ID = 0x1D1E
    }
}
