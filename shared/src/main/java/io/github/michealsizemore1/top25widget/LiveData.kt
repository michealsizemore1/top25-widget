package io.github.michealsizemore1.top25widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * Live-game details shown under each in-progress game (the "Live Drive" panel):
 * possession, down & distance, ball spot, timeouts, score by quarter, last play,
 * and win probability. Comes from the scoreboard's `situation` block.
 */
/** A player in the last play, shown as a chip with their photo. */
data class LiveAthlete(val name: String, val position: String, val jersey: String, val headshot: String)

data class LiveInfo(
    val possessionId: String,
    val possessionText: String,   // "STAN 25"
    val downText: String,         // "1st & 10"
    val ballX: Int?,              // 0..100 measured from the AWAY team's goal line (left)
    val firstDownX: Int?,
    val redZone: Boolean,
    val awayTimeouts: Int,        // -1 = unknown
    val homeTimeouts: Int,
    val drive: String,            // "4 plays, 30 yards, 2:01"
    val lastPlay: String,
    val athlete: String,          // "Nick Keller · PK · #98"
    val homeWinPct: Double?,      // 0..1
    val awayColor: Int,
    val homeColor: Int,
    val awayLines: List<String>,
    val homeLines: List<String>,
    val athletes: List<LiveAthlete> = emptyList(),
    val driveStartX: Int? = null,   // where the current drive began, same scale as ballX
) {
    companion object {
        private val DEFAULT_AWAY = 0xFF8A1C1C.toInt()
        private val DEFAULT_HOME = 0xFF1C4E8A.toInt()

        private fun color(hex: String?, fallback: Int): Int = try {
            val h = hex.orEmpty().trim().removePrefix("#")
            if (h.length == 6) (0xFF000000 or h.toLong(16)).toInt() else fallback
        } catch (e: Exception) {
            fallback
        }

        private fun lines(c: JSONObject): List<String> {
            val a = c.optJSONArray("linescores") ?: return emptyList()
            return (0 until a.length()).map { i ->
                val o = a.optJSONObject(i)
                o?.optString("displayValue")?.takeIf { it.isNotBlank() }
                    ?: o?.optDouble("value")?.takeIf { !it.isNaN() }?.toInt()?.toString()
                    ?: "-"
            }
        }

        /** Build from a scoreboard competition. [awayC]/[homeC] are the raw competitor objects. */
        fun from(comp: JSONObject, awayC: JSONObject, homeC: JSONObject, away: TeamLine, home: TeamLine): LiveInfo {
            val sit = comp.optJSONObject("situation") ?: JSONObject()
            val possessionId = sit.optString("possession")
            val possessionText = sit.optString("possessionText")
            val down = sit.optString("shortDownDistanceText").ifBlank {
                sit.optString("downDistanceText").substringBefore(" at ")
            }

            // Ball spot: "STAN 25" -> 25 yards from Stanford's goal line.
            var ballX: Int? = null
            Regex("^(\\S+)\\s+(\\d{1,2})$").find(possessionText.trim())?.let { m ->
                val side = m.groupValues[1]
                val yd = m.groupValues[2].toInt()
                ballX = when {
                    side.equals(away.abbr, true) -> yd
                    side.equals(home.abbr, true) -> 100 - yd
                    else -> null
                }
            }
            if (ballX == null && possessionText.trim() == "50") ballX = 50

            // First-down marker: the offense moves toward the other team's goal.
            val distance = sit.optInt("distance", 0)
            val goalToGo = down.contains("goal", true)
            val towardHome = possessionId.isNotBlank() && possessionId == away.id
            val firstDownX = ballX?.takeIf { distance > 0 && !goalToGo && possessionId.isNotBlank() }?.let {
                (if (towardHome) it + distance else it - distance).coerceIn(0, 100)
            }

            val last = sit.optJSONObject("lastPlay")
            val ath = last?.optJSONArray("athletesInvolved")?.optJSONObject(0)
            val athlete = ath?.let {
                listOf(
                    it.optString("displayName").ifBlank { it.optString("shortName") },
                    it.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                    it.optString("jersey").takeIf { j -> j.isNotBlank() }?.let { j -> "#$j" }.orEmpty(),
                ).filter { s -> s.isNotBlank() }.joinToString(" · ")
            }.orEmpty()
            val athletes = last?.optJSONArray("athletesInvolved")?.let { a ->
                (0 until minOf(2, a.length())).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    LiveAthlete(
                        name = o.optString("displayName").ifBlank { o.optString("shortName") },
                        position = o.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                        jersey = o.optString("jersey"),
                        headshot = o.optJSONObject("headshot")?.optString("href") ?: o.optString("headshot"),
                    ).takeIf { it.name.isNotBlank() }
                }
            }.orEmpty()
            val prob = last?.optJSONObject("probability")?.optDouble("homeWinPercentage")?.takeIf { !it.isNaN() }

            return LiveInfo(
                possessionId = possessionId,
                possessionText = possessionText,
                downText = down,
                ballX = ballX,
                firstDownX = firstDownX,
                redZone = sit.optBoolean("isRedZone", false),
                awayTimeouts = sit.optInt("awayTimeouts", -1),
                homeTimeouts = sit.optInt("homeTimeouts", -1),
                drive = last?.optJSONObject("drive")?.optString("description").orEmpty(),
                lastPlay = last?.optString("text").orEmpty(),
                athlete = athlete,
                homeWinPct = prob,
                awayColor = color(awayC.optJSONObject("team")?.optString("color"), DEFAULT_AWAY),
                homeColor = color(homeC.optJSONObject("team")?.optString("color"), DEFAULT_HOME),
                awayLines = lines(awayC),
                homeLines = lines(homeC),
                athletes = athletes,
            )
        }

        /** "MINN 9" -> yards from the away goal line (left), or null. */
        fun spotX(text: String, away: TeamLine, home: TeamLine): Int? {
            if (text.trim() == "50") return 50
            val m = Regex("^(\\S+)\\s+(\\d{1,2})$").find(text.trim()) ?: return null
            val yd = m.groupValues[2].toInt()
            return when {
                m.groupValues[1].equals(away.abbr, true) -> yd
                m.groupValues[1].equals(home.abbr, true) -> 100 - yd
                else -> null
            }
        }

        fun toJson(l: LiveInfo) = JSONObject().apply {
            put("pos", l.possessionId); put("posText", l.possessionText); put("down", l.downText)
            put("ballX", l.ballX ?: -1); put("fdX", l.firstDownX ?: -1); put("rz", l.redZone)
            put("ato", l.awayTimeouts); put("hto", l.homeTimeouts); put("drive", l.drive)
            put("last", l.lastPlay); put("ath", l.athlete); put("hwp", l.homeWinPct ?: -1.0)
            put("ac", l.awayColor); put("hc", l.homeColor)
            put("al", JSONArray(l.awayLines)); put("hl", JSONArray(l.homeLines))
        }

        fun fromJson(o: JSONObject?): LiveInfo? {
            if (o == null) return null
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
            return LiveInfo(
                possessionId = o.optString("pos"), possessionText = o.optString("posText"),
                downText = o.optString("down"),
                ballX = o.optInt("ballX", -1).takeIf { it >= 0 }, firstDownX = o.optInt("fdX", -1).takeIf { it >= 0 },
                redZone = o.optBoolean("rz"), awayTimeouts = o.optInt("ato", -1), homeTimeouts = o.optInt("hto", -1),
                drive = o.optString("drive"), lastPlay = o.optString("last"), athlete = o.optString("ath"),
                homeWinPct = o.optDouble("hwp", -1.0).takeIf { it >= 0 },
                awayColor = o.optInt("ac", DEFAULT_AWAY), homeColor = o.optInt("hc", DEFAULT_HOME),
                awayLines = strs(o.optJSONArray("al")), homeLines = strs(o.optJSONArray("hl")),
            )
        }
    }
}
