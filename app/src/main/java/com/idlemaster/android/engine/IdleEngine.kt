package com.idlemaster.android.engine

import android.util.Log
import com.idlemaster.android.data.Badge
import com.idlemaster.android.data.IdleMode
import com.idlemaster.android.data.IdleSettings
import com.idlemaster.android.data.Settings
import com.idlemaster.android.data.SortMode
import com.idlemaster.android.steam.ConnState
import com.idlemaster.android.steam.NotSignedInException
import com.idlemaster.android.steam.SteamConnection
import com.idlemaster.android.steam.SteamWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class Phase { STOPPED, WAITING_STEAM, LOADING, IDLING, NEXT_GAME, PAUSED, COMPLETE, ERROR }

data class EngineState(
    val phase: Phase = Phase.STOPPED,
    /** Extra status text: "page 2 of 5", error text, etc. */
    val message: String? = null,
    /** Games that still have drops (after blacklist / "only played" filters and sorting). */
    val queue: List<Badge> = emptyList(),
    /** Games currently reported to Steam as running. */
    val idling: List<Badge> = emptyList(),
    /** Set when exactly one game is idled (solo idle); null during simultaneous idling. */
    val current: Badge? = null,
    /** Seconds until the next card-drop check. */
    val timeLeft: Int = 0,
    val cardsRemaining: Int = 0,
    val progressMax: Int = 0,
    val sessionCards: Long = 0,
    val sessionMinutes: Long = 0,
) {
    val gamesRemaining: Int get() = queue.size
    val isActive: Boolean get() = phase in setOf(Phase.WAITING_STEAM, Phase.LOADING, Phase.IDLING, Phase.NEXT_GAME)
}

/**
 * The idling algorithm of Idle Master Extended (frmMain.cs: StartIdle / NextIdle / StartSoloIdle /
 * StartMultipleIdle / fast mode / tmrCardDropCheck), rewritten as one coroutine instead of
 * a set of WinForms timers. Timings are the original ones:
 *  - solo: check the game's card page every 15 min (5 min when 1 card is left);
 *  - simultaneous: re-read all badges every 6 min (fast mode: 5 min, then each game alone for 5 s);
 *  - 3–8 s random pause between games.
 */
class IdleEngine(
    private val steam: SteamConnection,
    private val web: SteamWeb,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var job: Job? = null
    private var statsJob: Job? = null

    @Volatile private var allBadges: List<Badge> = emptyList()
    private val skipped = mutableSetOf<Int>()
    @Volatile private var checkNowRequested = false
    @Volatile private var statsRemaining = -1

    val isRunning: Boolean get() = job?.isActive == true

    // ------------------------------------------------------------------ commands

    fun start() {
        if (isRunning) return
        job = scope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "engine crashed", e)
                setPhase(Phase.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) { steam.setGamesPlayed(emptyList()) }
                _state.update { it.copy(idling = emptyList(), current = null, timeLeft = 0) }
                stopStats()
            }
        }
        startStats()
    }

    fun pause() = command { setPhase(Phase.PAUSED) }

    fun stop() = command { setPhase(Phase.STOPPED) }

    fun resume() = command(restart = true) {}

    /** Skip the solo game for this session (btnSkip). */
    fun skipCurrent() = command(restart = true) {
        _state.value.current?.let { skipped += it.appId }
    }

    /** Add a game to the blacklist; restarts idling if that game is running. */
    fun blacklist(appId: Int) {
        statsRemaining = -1 // removing a game from the queue is not a card drop
        settings.update { it.copy(blacklist = it.blacklist + appId) }
        val running = _state.value.idling.any { it.appId == appId }
        if (running) command(restart = true) {} else publishQueue()
    }

    /** Clicking the timer in the desktop app: check drops in 2 seconds. */
    fun checkNow() {
        checkNowRequested = true
    }

    /** Settings changed (mode, sort…) — the desktop app stops, clears and reloads in that case. */
    fun onSettingsChanged() {
        statsRemaining = -1
        if (isRunning) command(restart = true) { allBadges = emptyList() } else publishQueue()
    }

    private fun command(restart: Boolean = false, block: () -> Unit) {
        scope.launch {
            job?.cancelAndJoin()
            block()
            if (restart) start()
        }
    }

    // ------------------------------------------------------------------ main loop

    private suspend fun run() {
        var needLoad = true
        var failures = 0
        while (true) {
            waitForSteam()
            val s = settings.settings.value

            if (s.mode == IdleMode.WHITELIST) {
                idleWhitelist(s)
                return
            }

            if (needLoad) {
                if (!loadBadges(s)) {
                    failures++
                    if (failures >= MAX_LOAD_FAILURES) {
                        setPhase(Phase.ERROR, "Не удалось прочитать страницу значков Steam")
                        return
                    }
                    retryCountdown()
                    continue
                }
                failures = 0
                needLoad = false
            }

            val can = canIdle(s)
            if (can.isEmpty()) {
                if (skipped.isNotEmpty()) {
                    // Every game left was skipped: start over, like btnSkip → LoadBadgesAsync.
                    skipped.clear()
                    needLoad = true
                    continue
                }
                setPhase(Phase.COMPLETE)
                return
            }

            when (s.mode) {
                IdleMode.ONE_BY_ONE -> soloIdle(can.first())

                IdleMode.ONE_THEN_MANY -> {
                    val longPlayed = can.filter { it.hoursPlayed >= 2 }
                    if (longPlayed.isNotEmpty()) {
                        soloIdle(longPlayed.first())
                    } else {
                        needLoad = !multiIdle(s, fast = false)
                    }
                }

                IdleMode.MANY_THEN_ONE, IdleMode.FAST -> {
                    val fast = s.mode == IdleMode.FAST
                    val multi = can.filter { fast || it.hoursPlayed < 2 }
                    if (multi.size >= 2) {
                        needLoad = !multiIdle(s, fast)
                    } else {
                        soloIdle(can.first())
                    }
                }

                IdleMode.WHITELIST -> Unit // handled above
            }
        }
    }

    /** StartSoloIdle + tmrCardDropCheck + CheckCardDrops + NextIdle. */
    private suspend fun soloIdle(start: Badge) {
        var badge = start
        showIdling(listOf(badge), solo = true)
        while (true) {
            countdown(if (badge.remainingCards == 1) 300 else 900)
            val refreshed = try {
                web.refreshBadge(badge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refreshBadge ${badge.appId}", e)
                null
            }
            if (refreshed == null) break
            badge = refreshed
            replaceBadge(badge)
            showIdling(listOf(badge), solo = true)
            if (badge.remainingCards == 0) break
        }
        nextGamePause()
    }

    /**
     * StartMultipleIdle / StartMultipleIdleFastMode / StartSoloIdleFastMode.
     * Returns false if the badge reload at the end failed.
     */
    private suspend fun multiIdle(s: IdleSettings, fast: Boolean): Boolean {
        val set = canIdle(s).filter { fast || it.hoursPlayed < 2 }.take(s.maxSimultaneous)
        showIdling(set, solo = false)
        countdown(if (fast) 300 else 360)

        if (!loadBadges(s)) return false

        if (fast) {
            // Stop, wait 5 s, then run each game alone for 5 s — this is what makes drops come faster.
            steam.setGamesPlayed(emptyList())
            setPhase(Phase.NEXT_GAME)
            delay(5_000)
            for (b in canIdle(s).take(s.maxSimultaneous)) {
                showIdling(listOf(b), solo = true)
                countdown(5)
            }
            steam.setGamesPlayed(emptyList())
        }
        return true
    }

    /** Whitelist mode: idle the listed App IDs indefinitely, no drop checks. */
    private suspend fun idleWhitelist(s: IdleSettings) {
        val ids = s.whitelist.toList().take(s.maxSimultaneous)
        if (ids.isEmpty()) {
            setPhase(Phase.ERROR, "Белый список пуст — добавьте App ID в настройках")
            return
        }
        val badges = ids.map { Badge(it, "App ID: $it", -1, 0.0) }
        allBadges = badges
        _state.update { it.copy(queue = badges, cardsRemaining = 0, progressMax = 0) }
        showIdling(badges, solo = badges.size == 1)
        _state.update { it.copy(timeLeft = 0) }
        awaitCancellation()
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun waitForSteam() {
        if (steam.isOnline) return
        setPhase(Phase.WAITING_STEAM)
        steam.connectSaved()
        steam.awaitOnline()
    }

    private suspend fun loadBadges(s: IdleSettings): Boolean {
        setPhase(Phase.LOADING, null)
        return try {
            val loaded = web.loadBadges(s.blacklist) { page, total ->
                _state.update { it.copy(message = "$page/$total") }
            }
            // Keep the order stable for games we already know.
            allBadges = loaded
            publishQueue()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotSignedInException) {
            Log.w(TAG, "loadBadges: not signed in")
            _state.update { it.copy(message = "Steam Community не принял вход") }
            false
        } catch (e: Exception) {
            Log.w(TAG, "loadBadges", e)
            _state.update { it.copy(message = e.message) }
            false
        }
    }

    /** tmrBadgeReload: wait 10 s with a visible countdown before trying the badge pages again. */
    private suspend fun retryCountdown() {
        for (left in 10 downTo 1) {
            _state.update { it.copy(phase = Phase.LOADING, timeLeft = left) }
            delay(1000)
        }
    }

    private fun canIdle(s: IdleSettings): List<Badge> {
        val list = allBadges.filter {
            it.remainingCards != 0 &&
                (!s.idleOnlyPlayed || it.hoursPlayed > 0.0) &&
                it.appId !in s.blacklist &&
                it.appId !in skipped
        }
        return when (s.sort) {
            SortMode.DEFAULT -> list
            SortMode.MOST_CARDS -> list.sortedByDescending { it.remainingCards }
            SortMode.LEAST_CARDS -> list.sortedBy { it.remainingCards }
        }
    }

    private fun replaceBadge(b: Badge) {
        allBadges = allBadges.map { if (it.appId == b.appId) b else it }
        publishQueue()
    }

    /** Recomputes queue / totals / progress and counts dropped cards (Statistics.checkCardRemaining). */
    private fun publishQueue() {
        val s = settings.settings.value
        if (s.mode == IdleMode.WHITELIST) return
        val queue = canIdle(s)
        val remaining = allBadges.filter {
            it.remainingCards > 0 && it.appId !in s.blacklist && (!s.idleOnlyPlayed || it.hoursPlayed > 0.0)
        }.sumOf { it.remainingCards }

        if (statsRemaining > 0 && remaining < statsRemaining) {
            val dropped = (statsRemaining - remaining).toLong()
            settings.addCards(dropped)
            _state.update { it.copy(sessionCards = it.sessionCards + dropped) }
        }
        statsRemaining = remaining

        _state.update {
            val max = maxOf(it.progressMax, remaining)
            it.copy(queue = queue, cardsRemaining = remaining, progressMax = max)
        }
    }

    private fun showIdling(games: List<Badge>, solo: Boolean) {
        steam.setGamesPlayed(games.map { it.appId })
        _state.update {
            it.copy(
                phase = Phase.IDLING,
                message = null,
                idling = games,
                current = if (solo) games.firstOrNull() else null,
            )
        }
    }

    /** NextIdle: stop the game and wait a short random time before the next one. */
    private suspend fun nextGamePause() {
        steam.setGamesPlayed(emptyList())
        _state.update { it.copy(phase = Phase.NEXT_GAME, idling = emptyList(), current = null) }
        delay(Random.nextLong(3_000, 9_000))
    }

    /**
     * Counts down [seconds]. Time does not run while Steam is offline or while another
     * device is playing on this account — Steam does not credit playtime then.
     */
    private suspend fun countdown(seconds: Int) {
        var left = seconds
        checkNowRequested = false
        while (left > 0) {
            if (checkNowRequested) {
                checkNowRequested = false
                left = minOf(left, 2)
            }
            _state.update { it.copy(timeLeft = left) }
            delay(1000)
            val st = steam.status.value
            if (st.state == ConnState.ONLINE && !st.playingBlocked) left--
            else if (st.state != ConnState.ONLINE) steam.connectSaved()
        }
        _state.update { it.copy(timeLeft = 0) }
    }

    private fun setPhase(phase: Phase, message: String? = null) {
        _state.update { it.copy(phase = phase, message = message) }
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                delay(60_000)
                val st = steam.status.value
                val e = _state.value
                if (e.phase == Phase.IDLING && e.idling.isNotEmpty() && st.state == ConnState.ONLINE && !st.playingBlocked) {
                    settings.addMinutes(1)
                    _state.update { it.copy(sessionMinutes = it.sessionMinutes + 1) }
                }
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    companion object {
        private const val TAG = "IdleEngine"
        private const val MAX_LOAD_FAILURES = 18
    }
}
