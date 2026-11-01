package io.github.michealsizemore1.top25widget

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Score data for the watch: the Top 25 scoreboard (shared parser with the phone app),
 * cached briefly so the tiles and complication don't each download it.
 */
object WatchFeed {
    const val OSU_ID = "194"
    private const val OSU_TEAM_URL =
        "https://site.api.espn.com/apis/site/v2/sports/football/college-football/teams/$OSU_ID"

    @Volatile private var cached: Pair<Long, List<Game>>? = null
    @Volatile private var nextCached: Pair<Long, String>? = null

    /** Today's Top 25 games, live first. Reuses a download up to [maxAgeMs] old. */
    fun games(maxAgeMs: Long = 60_000): List<Game> {
        cached?.let { (at, list) -> if (System.currentTimeMillis() - at < maxAgeMs) return list }
        val fresh = EspnSource.fetch(League.CFB)
        cached = System.currentTimeMillis() to fresh
        return fresh
    }

    fun isOsu(g: Game) = g.away.id == OSU_ID || g.home.id == OSU_ID ||
        g.away.name == "Ohio State" || g.home.name == "Ohio State"

    fun osuGame(games: List<Game>): Game? = games.firstOrNull(::isOsu)

    /** "OSU @ MICH · Sat, Nov 28 · 10:00 AM" for Ohio State's next game (when not playing today). */
    fun osuNext(): String {
        nextCached?.let { (at, s) -> if (System.currentTimeMillis() - at < 30 * 60_000) return s }
        return try {
            val ev = JSONObject(String(Http.get(OSU_TEAM_URL)))
                .optJSONObject("team")?.optJSONArray("nextEvent")?.optJSONObject(0)
                ?: return "No game scheduled"
            val name = ev.optString("shortName").ifBlank { ev.optString("name") }
            val whenText = parseDate(ev.optString("date"))?.let {
                it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.getDefault()))
            }.orEmpty()
            listOf(name, whenText).filter { it.isNotBlank() }.joinToString("\n").also {
                nextCached = System.currentTimeMillis() to it
            }
        } catch (e: Exception) {
            "Next game unavailable"
        }
    }

    private fun parseDate(s: String): Instant? = try {
        Instant.parse(s)
    } catch (e: Exception) {
        try {
            OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")).toInstant()
        } catch (e2: Exception) {
            null
        }
    }

    // ---- display helpers

    fun short(t: TeamLine) = (t.rank?.let { "$it " } ?: "") + t.abbr.ifBlank { t.name }

    fun status(g: Game): String = when {
        g.isLive -> g.detail.ifBlank { "Live" }
        g.isFinal -> g.detail.ifBlank { "Final" }
        else -> Instant.ofEpochMilli(g.startMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }

    fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
}
