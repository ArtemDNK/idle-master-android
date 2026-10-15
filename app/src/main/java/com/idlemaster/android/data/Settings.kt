package com.idlemaster.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Idling algorithms — same five options as the "Idling behavior" group
 * in the desktop Idle Master Extended settings window.
 */
enum class IdleMode {
    /** Idle games simultaneously for a short time, then each one for 5 s, repeat (desktop default). */
    FAST,
    /** Idle games with ≥ 2 h individually first, then the rest simultaneously. */
    ONE_THEN_MANY,
    /** Idle games simultaneously up to 2 h, then individually. */
    MANY_THEN_ONE,
    /** Idle each game individually (slow). */
    ONE_BY_ONE,
    /** Idle the App IDs from the whitelist forever, without checking drops (hour boosting). */
    WHITELIST,
}

enum class SortMode { DEFAULT, MOST_CARDS, LEAST_CARDS }

data class IdleSettings(
    val mode: IdleMode = IdleMode.FAST,
    val sort: SortMode = SortMode.DEFAULT,
    val idleOnlyPlayed: Boolean = false,
    val maxSimultaneous: Int = 30,
    val showUsername: Boolean = true,
    val blacklist: Set<Int> = emptySet(),
    val whitelist: Set<Int> = emptySet(),
)

data class Account(
    val accountName: String,
    val steamId: Long,
    val refreshToken: String,
)

/** Persistent storage: account tokens, idling settings, lifetime statistics. */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("idle_master", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<IdleSettings> = _settings.asStateFlow()

    private val _stats = MutableStateFlow(readStats())
    val stats: StateFlow<LifetimeStats> = _stats.asStateFlow()

    // ---------------- account ----------------

    var account: Account?
        get() {
            val name = sp.getString(K_ACCOUNT, null) ?: return null
            val token = sp.getString(K_REFRESH, null) ?: return null
            return Account(name, sp.getLong(K_STEAMID, 0L), token)
        }
        set(value) = sp.edit {
            if (value == null) {
                remove(K_ACCOUNT); remove(K_REFRESH); remove(K_STEAMID)
            } else {
                putString(K_ACCOUNT, value.accountName)
                putString(K_REFRESH, value.refreshToken)
                putLong(K_STEAMID, value.steamId)
            }
        }

    /** Steam Guard "machine" data returned by the auth server; lets future logins skip e-mail codes. */
    fun guardData(accountName: String): String? = sp.getString("$K_GUARD$accountName", null)

    fun setGuardData(accountName: String, data: String?) = sp.edit {
        if (data.isNullOrEmpty()) remove("$K_GUARD$accountName") else putString("$K_GUARD$accountName", data)
    }

    var lastAccountName: String
        get() = sp.getString(K_LAST_NAME, "") ?: ""
        set(value) = sp.edit { putString(K_LAST_NAME, value) }

    // ---------------- settings ----------------

    fun update(transform: (IdleSettings) -> IdleSettings) {
        val new = transform(_settings.value)
        sp.edit {
            putString(K_MODE, new.mode.name)
            putString(K_SORT, new.sort.name)
            putBoolean(K_ONLY_PLAYED, new.idleOnlyPlayed)
            putInt(K_MAX_SIM, new.maxSimultaneous)
            putBoolean(K_SHOW_NAME, new.showUsername)
            putStringSet(K_BLACKLIST, new.blacklist.map(Int::toString).toSet())
            putStringSet(K_WHITELIST, new.whitelist.map(Int::toString).toSet())
        }
        _settings.value = new
    }

    private fun readSettings() = IdleSettings(
        mode = enumOr(sp.getString(K_MODE, null), IdleMode.FAST),
        sort = enumOr(sp.getString(K_SORT, null), SortMode.DEFAULT),
        idleOnlyPlayed = sp.getBoolean(K_ONLY_PLAYED, false),
        maxSimultaneous = sp.getInt(K_MAX_SIM, 30).coerceIn(1, MAX_STEAM_GAMES),
        showUsername = sp.getBoolean(K_SHOW_NAME, true),
        blacklist = readIds(K_BLACKLIST),
        whitelist = readIds(K_WHITELIST),
    )

    private fun readIds(key: String): Set<Int> =
        sp.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    // ---------------- statistics ----------------

    fun addMinutes(n: Long) = updateStats { it.copy(totalMinutes = it.totalMinutes + n) }
    fun addCards(n: Long) = updateStats { it.copy(totalCards = it.totalCards + n) }

    private fun updateStats(transform: (LifetimeStats) -> LifetimeStats) {
        val new = transform(_stats.value)
        sp.edit {
            putLong(K_TOTAL_MIN, new.totalMinutes)
            putLong(K_TOTAL_CARDS, new.totalCards)
        }
        _stats.value = new
    }

    private fun readStats() = LifetimeStats(
        totalMinutes = sp.getLong(K_TOTAL_MIN, 0),
        totalCards = sp.getLong(K_TOTAL_CARDS, 0),
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        /** Steam accepts at most 32 games in one "playing" report. */
        const val MAX_STEAM_GAMES = 32

        private const val K_ACCOUNT = "account_name"
        private const val K_REFRESH = "refresh_token"
        private const val K_STEAMID = "steam_id"
        private const val K_GUARD = "guard_"
        private const val K_LAST_NAME = "last_account_name"
        private const val K_MODE = "idle_mode"
        private const val K_SORT = "sort"
        private const val K_ONLY_PLAYED = "idle_only_played"
        private const val K_MAX_SIM = "max_simultaneous"
        private const val K_SHOW_NAME = "show_username"
        private const val K_BLACKLIST = "blacklist"
        private const val K_WHITELIST = "whitelist"
        private const val K_TOTAL_MIN = "total_minutes"
        private const val K_TOTAL_CARDS = "total_cards"
    }
}

data class LifetimeStats(val totalMinutes: Long = 0, val totalCards: Long = 0)
