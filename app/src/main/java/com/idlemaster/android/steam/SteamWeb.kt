package com.idlemaster.android.steam

import com.idlemaster.android.data.Badge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class NotSignedInException : Exception("Steam Community session is not signed in")

/**
 * steamcommunity.com client. Replaces `CookieClient.cs`: instead of cookies copied from a browser,
 * the `steamLoginSecure` cookie is built from the access token of the app's own Steam session.
 */
class SteamWeb(private val tokens: WebTokenProvider) {

    interface WebTokenProvider {
        /** steamID64 of the signed-in user. */
        val steamId: Long?
        /** Fresh access token for steamLoginSecure; [forceRefresh] asks Steam for a new one. */
        suspend fun webAccessToken(forceRefresh: Boolean = false): String?
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sessionId: String = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun profileUrl(steamId: Long) = "https://steamcommunity.com/profiles/$steamId"

    /** GET a steamcommunity page as the signed-in user. Retries once with a refreshed token if the page says we're anonymous. */
    private suspend fun getSigned(url: String): String {
        repeat(2) { attempt ->
            val steamId = tokens.steamId ?: throw NotSignedInException()
            val token = tokens.webAccessToken(forceRefresh = attempt > 0) ?: throw NotSignedInException()
            val html = get(url, steamId, token)
            if (BadgeParser.signedInSteamId(html) == steamId) return html
        }
        throw NotSignedInException()
    }

    private suspend fun get(url: String, steamId: Long, accessToken: String): String =
        withContext(Dispatchers.IO) {
            val cookies = listOf(
                "steamLoginSecure=$steamId%7C%7C$accessToken",
                "sessionid=$sessionId",
                "Steam_Language=english",
                "timezoneOffset=0,0",
            ).joinToString("; ")
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} for $url")
                resp.body.string()
            }
        }

    /**
     * Reads every page of /badges/ (LoadBadgesAsync). [onPage] reports progress "page x of y".
     */
    suspend fun loadBadges(blacklist: Set<Int>, onPage: (Int, Int) -> Unit = { _, _ -> }): List<Badge> {
        val steamId = tokens.steamId ?: throw NotSignedInException()
        val result = LinkedHashMap<Int, Badge>()
        var page = 1
        var total = 1
        while (page <= total) {
            onPage(page, total)
            val html = getSigned("${profileUrl(steamId)}/badges/?p=$page&l=english")
            val doc = BadgeParser.parse(html)
            if (page == 1) total = BadgeParser.totalPages(doc)
            BadgeParser.badgesOnPage(doc, blacklist).forEach { result[it.appId] = it }
            page++
        }
        return result.values.toList()
    }

    /** Re-reads one game's card page (Badge.CanCardDrops). Null when the page could not be read. */
    suspend fun refreshBadge(badge: Badge): Badge? {
        val steamId = tokens.steamId ?: throw NotSignedInException()
        val html = getSigned("${profileUrl(steamId)}/gamecards/${badge.appId}/?l=english")
        val (cards, hours) = BadgeParser.gameCardStats(BadgeParser.parse(html)) ?: return null
        return badge.copy(remainingCards = cards, hoursPlayed = hours)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
