package com.idlemaster.android.steam

import com.idlemaster.android.data.Badge
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * HTML parsing of steamcommunity.com badge pages — the same XPath rules as
 * `frmMain.ProcessBadgesOnPage` / `Badge.CanCardDrops` in the desktop app, rewritten for jsoup.
 * Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 */
object BadgeParser {

    /** Apps that are never idled: Steam Sale/event badges the desktop app hard-codes. */
    private val IGNORED_APPS = setOf(368020, 335590)

    private val appIdRegex = Regex("""gamecards/(\d+)/""")
    private val hoursRegex = Regex("""[0-9.,]+""")
    private val cardsRegex = Regex("""[0-9]+""")
    private val steamIdRegex = Regex("""g_steamID\s*=\s*"(\d+)"""")

    fun parse(html: String, baseUri: String = "https://steamcommunity.com/"): Document =
        Jsoup.parse(html, baseUri)

    /** steamcommunity puts `g_steamID = "7656…"` into every page for a signed-in visitor, `false` otherwise. */
    fun signedInSteamId(html: String): Long? =
        steamIdRegex.find(html)?.groupValues?.get(1)?.toLongOrNull()

    /** Highest page number from the `a.pagelink` paginator; 1 when there is no paginator. */
    fun totalPages(doc: Document): Int =
        doc.select("a.pagelink")
            .mapNotNull { a -> a.attr("href").substringAfter("p=", "").takeWhile(Char::isDigit).toIntOrNull() }
            .maxOrNull()
            ?.coerceAtLeast(1) ?: 1

    /** All game badges on one page of /badges/, skipping foil rows, sale badges and the blacklist. */
    fun badgesOnPage(doc: Document, blacklist: Set<Int>): List<Badge> =
        doc.select("div.badge_row.is_link").mapNotNull { row ->
            val href = row.selectFirst("a.badge_row_overlay")?.attr("href") ?: return@mapNotNull null
            val appId = appIdRegex.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (appId in IGNORED_APPS || appId in blacklist || href.contains("border=1")) return@mapNotNull null

            val hours = row.selectFirst("div.badge_title_stats_playtime")?.text().orEmpty()
            val titleNode = row.selectFirst("div.badge_title")
            val name = titleNode?.ownText()?.trim().orEmpty().ifBlank { "App $appId" }
            val cards = row.selectFirst("span.progress_info_bold")?.text().orEmpty()

            Badge(appId, name, parseCards(cards), parseHours(hours))
        }

    /**
     * /gamecards/<appid>/ page of a single game. Returns (remaining cards, hours on record),
     * or null when the page has no playtime block (not signed in / page did not load).
     */
    fun gameCardStats(doc: Document): Pair<Int, Double>? {
        val hoursNode = doc.selectFirst("div.badge_title_stats_playtime") ?: return null
        val hours = parseHours(hoursNode.text())
        val cardsNode = hoursNode.parent()?.selectFirst("span.progress_info_bold")
        return parseCards(cardsNode?.text().orEmpty()) to hours
    }

    /** "3 card drops remaining" → 3, "No card drops remaining" → 0. */
    fun parseCards(text: String): Int = cardsRegex.find(text)?.value?.toIntOrNull() ?: 0

    /** "1,234.5 hrs on record" → 1234.5 (pages are requested with l=english, so ',' is a group separator). */
    fun parseHours(text: String): Double =
        hoursRegex.find(text)?.value?.replace(",", "")?.trimEnd('.')?.toDoubleOrNull() ?: 0.0
}
