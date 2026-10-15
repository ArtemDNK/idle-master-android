package com.idlemaster.android.data

/**
 * One game on the Steam badges page. Mirrors `Badge.cs` from the desktop app:
 * [remainingCards] is -1 when unknown (whitelist mode), 0 when nothing is left to drop.
 */
data class Badge(
    val appId: Int,
    val name: String,
    val remainingCards: Int,
    val hoursPlayed: Double,
) {
    val headerImageUrl: String
        get() = "https://cdn.akamai.steamstatic.com/steam/apps/$appId/header.jpg"

    val storeUrl: String
        get() = "https://store.steampowered.com/app/$appId"

    override fun toString(): String = name.ifBlank { appId.toString() }
}
