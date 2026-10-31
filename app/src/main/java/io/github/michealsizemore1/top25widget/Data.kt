package io.github.michealsizemore1.top25widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Change these to point the widget somewhere else. */
object Config {
    /** Opened when you tap a game in the widget. */
    const val SITE_URL = ""  // set to your Top 25 site's full address to show "Open site" links


    /** How often to refresh while a game is live (minutes). Normal refresh is every 15. */
    const val LIVE_REFRESH_MINUTES = 2L
}

/** The two leagues the app has widgets for. */
enum class League(
    val key: String,
    val title: String,
    val shortTitle: String,
    private val path: String,
    private val espnPath: String,
    val rankedOnly: Boolean,
) {
    CFB("cfb", "Top 25 Scores", "Top 25", "football/college-football", "college-football", true),
    NFL("nfl", "NFL Scores", "NFL", "football/nfl", "nfl", false),
    WNBA("wnba", "WNBA Scores", "WNBA", "basketball/wnba", "wnba", false);

    /** Pro leagues show team nicknames ("Chiefs", "Liberty"); college shows schools. */
    val usesNicknames get() = this != CFB
    val emptyMessage get() = when (this) {
        CFB -> "No Top 25 games today"
        NFL -> "No NFL games this week"
        WNBA -> "No WNBA games today"
    }

    /** ESPN's public scoreboard. For college, with no filter it returns games involving ranked teams. */
    val scoreboardUrl get() = "https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"
    val summaryUrl get() = "https://site.api.espn.com/apis/site/v2/sports/$path/summary?event="
    fun espnGameUrl(id: String) = "https://www.espn.com/$espnPath/game/_/gameId/$id"
    fun logoKey(teamId: String) = "$key-$teamId"

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CFB
    }
}

data class TeamLine(
    val id: String,
    val name: String,
    val abbr: String,
    val rank: Int?,
    val record: String,
    val score: String,
    val winner: Boolean,
    val logoKey: String = "",
    val logoUrl: String = "",
)

data class Game(
    val id: String,
    val state: String,        // "pre", "in", or "post"
    val detail: String,       // e.g. "Q2 5:12", "Halftime", "Final/OT"
    val startMillis: Long,
    val network: String,
    val away: TeamLine,
    val home: TeamLine,
) {
    val isLive get() = state == "in"
    val isFinal get() = state == "post"
    val isUpcoming get() = state == "pre"
}

// ---------------------------------------------------------------- fetching

object EspnSource {

    fun fetch(league: League): List<Game> {
        val body = String(Http.get(league.scoreboardUrl))
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val games = (0 until events.length()).mapNotNull { parseEvent(events.getJSONObject(it), league) }
        if (!league.rankedOnly) return sort(games)
        val ranked = games.filter { it.away.rank != null || it.home.rank != null }
        return sort(ranked.ifEmpty { games })
    }

    /** Live games first, then upcoming by kickoff, then finals. */
    private fun sort(games: List<Game>): List<Game> = games.sortedWith(
        compareBy<Game> { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }
            .thenBy { it.startMillis }
    )

    private fun parseEvent(ev: JSONObject, league: League): Game? {
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = comp.optJSONObject("status") ?: ev.optJSONObject("status")
        val type = status?.optJSONObject("type")
        val competitors = comp.optJSONArray("competitors") ?: return null

        var home: TeamLine? = null
        var away: TeamLine? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.getJSONObject(i)
            val team = parseTeam(c, league)
            if (c.optString("homeAway") == "home") home = team else away = team
        }
        if (home == null || away == null) return null

        return Game(
            id = ev.optString("id"),
            state = type?.optString("state", "pre") ?: "pre",
            detail = type?.optString("shortDetail", "") ?: "",
            startMillis = parseDate(comp.optString("date", ev.optString("date"))),
            network = parseNetwork(comp),
            away = away,
            home = home,
        )
    }

    private fun parseTeam(c: JSONObject, league: League): TeamLine {
        val team = c.optJSONObject("team") ?: JSONObject()
        val rank = c.optJSONObject("curatedRank")?.optInt("current", 99) ?: 99
        val record = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary", "") ?: ""
        return TeamLine(
            id = team.optString("id"),
            name = if (league.usesNicknames) team.optString("shortDisplayName").ifBlank { team.optString("name") }
                   else team.optString("location").ifBlank { team.optString("shortDisplayName") },
            abbr = team.optString("abbreviation"),
            rank = rank.takeIf { it in 1..25 },
            record = record,
            score = c.optString("score", ""),
            winner = c.optBoolean("winner", false),
            logoKey = league.logoKey(team.optString("id")),
            logoUrl = team.optString("logo"),
        )
    }

    private fun parseNetwork(comp: JSONObject): String {
        comp.optJSONArray("broadcasts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optJSONArray("names")?.optString(0).orEmpty()
                if (name.isNotBlank()) return name
            }
        }
        return comp.optJSONArray("geoBroadcasts")?.optJSONObject(0)
            ?.optJSONObject("media")?.optString("shortName").orEmpty()
    }

    private fun parseDate(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        try {
            OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX"))
                .toInstant().toEpochMilli()
        } catch (e2: Exception) {
            0L
        }
    }
}

object Http {
    fun get(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Top25Widget/1.0 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------- team logos

object Logos {
    private const val SIZE_PX = 72
    private val cache = LruCache<String, Bitmap>(80)

    private fun file(ctx: Context, key: String): File =
        File(File(ctx.filesDir, "logos").apply { mkdirs() }, "$key.png")

    /** Downloads any logos we don't have yet (key -> URL). Dark-background versions first, so Iowa etc. stay visible. */
    fun ensure(ctx: Context, logos: Map<String, String>) {
        for ((key, url) in logos) {
            if (key.isBlank() || url.isBlank()) continue
            val f = file(ctx, key)
            if (f.exists() && f.length() > 0) continue
            val urls = listOf(url.replace("/500/", "/500-dark/"), url).distinct()
            for (u in urls) {
                val bmp = try {
                    val bytes = Http.get(u)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                } ?: continue
                val scaled = Bitmap.createScaledBitmap(bmp, SIZE_PX, SIZE_PX, true)
                f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                break
            }
        }
    }

    fun bitmap(ctx: Context, key: String): Bitmap? {
        if (key.isBlank()) return null
        cache.get(key)?.let { return it }
        val f = file(ctx, key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { cache.put(key, it) }
    }
}

// ---------------------------------------------------------------- storage format

object GameJson {
    fun encode(games: List<Game>): String = JSONArray().apply {
        games.forEach { g ->
            put(JSONObject().apply {
                put("id", g.id); put("state", g.state); put("detail", g.detail)
                put("start", g.startMillis); put("network", g.network)
                put("away", team(g.away)); put("home", team(g.home))
            })
        }
    }.toString()

    fun decode(s: String?): List<Game> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Game(
                    id = o.optString("id"), state = o.optString("state"),
                    detail = o.optString("detail"), startMillis = o.optLong("start"),
                    network = o.optString("network"),
                    away = team(o.getJSONObject("away")), home = team(o.getJSONObject("home")),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun team(t: TeamLine) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("abbr", t.abbr); put("rank", t.rank ?: -1)
        put("record", t.record); put("score", t.score); put("winner", t.winner)
        put("logoKey", t.logoKey); put("logoUrl", t.logoUrl)
    }

    private fun team(o: JSONObject) = TeamLine(
        id = o.optString("id"), name = o.optString("name"), abbr = o.optString("abbr"),
        rank = o.optInt("rank", -1).takeIf { it in 1..25 }, record = o.optString("record"),
        score = o.optString("score"), winner = o.optBoolean("winner"),
        logoKey = o.optString("logoKey"), logoUrl = o.optString("logoUrl"),
    )
}
