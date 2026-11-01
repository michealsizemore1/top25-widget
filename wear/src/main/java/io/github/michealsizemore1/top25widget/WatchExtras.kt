package io.github.michealsizemore1.top25widget

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.tiles.TileBuilders
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Cross country and track & field data for the watch. These read the same files
 * the phone widgets use, published by the Master App repo.
 */
object ExtrasFeed {
    private const val BASE = "https://michealsizemore1.github.io/ChatGPT-The-Master-App/"
    private const val XC_URL = BASE + "data/xc-meets.json"
    private const val TF_URL = BASE + "data/ncaa-tf.json"
    private const val TF_NEWS_URL = BASE + "data/tf-news.json"

    data class Meet(val name: String, val start: LocalDate, val end: LocalDate, val where: String, val watch: String)
    data class Headline(val date: LocalDate?, val text: String, val source: String)
    data class Standing(val rank: Int, val team: String, val points: Int)
    data class Champ(val men: Boolean, val event: String, val name: String, val team: String, val mark: String)

    data class Xc(val meets: List<Meet>, val results: List<Headline>)
    data class Tf(val next: Meet?, val men: List<Standing>, val women: List<Standing>, val champs: List<Champ>, val news: List<Headline>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun get(url: String, maxAgeMs: Long): String {
        cache[url]?.let { (at, body) -> if (System.currentTimeMillis() - at < maxAgeMs) return body }
        val body = String(Http.get(url + "?t=" + System.currentTimeMillis()))
        cache[url] = System.currentTimeMillis() to body
        return body
    }

    private fun date(s: String): LocalDate? = try { LocalDate.parse(s) } catch (e: Exception) { null }

    private fun headlines(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a ->
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            Headline(date(r.optString("date")), r.optString("headline"), r.optString("source"))
        }
    }.orEmpty()

    fun xc(maxAgeMs: Long = 10 * 60_000): Xc {
        val o = JSONObject(get(XC_URL, maxAgeMs))
        val meets = o.optJSONArray("meets")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val m = a.getJSONObject(i)
                val start = date(m.optString("start")) ?: return@mapNotNull null
                Meet(m.optString("name"), start, date(m.optString("end")) ?: start, m.optString("where"), m.optString("watch"))
            }
        }.orEmpty()
        return Xc(meets, headlines(o, "results"))
    }

    fun tf(maxAgeMs: Long = 30 * 60_000): Tf {
        val o = JSONObject(get(TF_URL, maxAgeMs))
        val today = LocalDate.now()
        val next = o.optJSONArray("championships")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val m = a.getJSONObject(i)
                val start = date(m.optString("start")) ?: return@mapNotNull null
                Meet(m.optString("name"), start, date(m.optString("end")) ?: start, m.optString("where"), "")
            }
        }.orEmpty().filter { !it.end.isBefore(today) }.minByOrNull { it.start }
        fun standings(key: String) = o.optJSONObject("standings")?.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Standing(it.optInt("rank"), it.optString("team"), it.optInt("points")) } }
        }.orEmpty()
        val champs = o.optJSONArray("champions")?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let {
                    Champ(it.optString("gender") == "men", it.optString("event"), it.optString("name"), it.optString("team"), it.optString("mark"))
                }
            }
        }.orEmpty()
        val news = try { headlines(JSONObject(get(TF_NEWS_URL, maxAgeMs)), "news") } catch (e: Exception) { emptyList() }
        return Tf(next, standings("men"), standings("women"), champs, news)
    }

    // ---- shared formatting

    private val md = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    fun nextMeet(meets: List<Meet>, today: LocalDate = LocalDate.now()) =
        meets.filter { !it.end.isBefore(today) }.minByOrNull { it.start }

    fun whenText(m: Meet, today: LocalDate = LocalDate.now()): String = when {
        !today.isBefore(m.start) && !today.isAfter(m.end) -> "TODAY"
        m.start == today.plusDays(1) -> "TOMORROW"
        m.start == m.end -> m.start.format(md).uppercase()
        else -> (m.start.format(md) + "–" + m.end.format(md)).uppercase()
    }

    fun countdown(m: Meet, today: LocalDate = LocalDate.now()): String {
        if (!today.isBefore(m.start) && !today.isAfter(m.end)) return "Happening now"
        val days = ChronoUnit.DAYS.between(today, m.start)
        return when {
            days == 1L -> "Tomorrow"
            days < 14 -> "In $days days"
            days < 60 -> "In ${days / 7} weeks"
            else -> "In ${days / 30} months"
        }
    }

    fun headlineDate(d: LocalDate?, today: LocalDate = LocalDate.now()) = when (d) {
        null -> ""
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(md)
    }
}

// ====================================================================== XC tile

class XcTileService : CoroutineTileService() {
    override suspend fun buildTile(): TileBuilders.Tile {
        val xc = try { ExtrasFeed.xc() } catch (e: Exception) {
            return TileUi.tile(TileUi.message("XC MEETS", "Can't reach results right now"), 30 * 60_000L)
        }
        val next = ExtrasFeed.nextMeet(xc.meets)
        val col = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(TileUi.text("XC MEETS", 12f, TileUi.ACCENT, bold = true))
            .addContent(TileUi.spacer(3f))
        if (next != null) {
            val w = ExtrasFeed.whenText(next)
            col.addContent(TileUi.text(if (w == "TODAY") "MEET DAY" else "NEXT · $w", 10f,
                if (w == "TODAY") TileUi.ACCENT else TileUi.MUTED, bold = true))
            col.addContent(TileUi.text(next.name, 14f, TileUi.WHITE, bold = true, maxLines = 2))
            col.addContent(TileUi.spacer(4f))
        }
        xc.results.take(if (next != null) 2 else 3).forEach { r ->
            col.addContent(TileUi.text(r.text, 11f, TileUi.WHITE, maxLines = 2))
            col.addContent(TileUi.text(ExtrasFeed.headlineDate(r.date), 9f, TileUi.ACCENT))
            col.addContent(TileUi.spacer(2f))
        }
        if (next == null && xc.results.isEmpty()) col.addContent(TileUi.text("No meet results yet", 12f, TileUi.MUTED))
        val root = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(TileUi.open(this, null, "xc", "xc")).build())
            .addContent(TileUi.screen(col.build())).build()
        return TileUi.tile(root, 30 * 60_000L)
    }
}

// ====================================================================== Track & field tile

class TfTileService : CoroutineTileService() {
    override suspend fun buildTile(): TileBuilders.Tile {
        val tf = try { ExtrasFeed.tf() } catch (e: Exception) {
            return TileUi.tile(TileUi.message("TRACK & FIELD", "Can't reach data right now"), 60 * 60_000L)
        }
        val col = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(TileUi.text("TRACK & FIELD", 12f, TileUi.ACCENT, bold = true))
            .addContent(TileUi.spacer(3f))
        tf.next?.let { n ->
            col.addContent(TileUi.text("NEXT · ${ExtrasFeed.countdown(n).uppercase()}", 10f, TileUi.MUTED, bold = true))
            col.addContent(TileUi.text(n.name, 14f, TileUi.WHITE, bold = true, maxLines = 2))
            col.addContent(TileUi.text(ExtrasFeed.whenText(n), 11f, TileUi.MUTED))
            col.addContent(TileUi.spacer(4f))
        }
        col.addContent(TileUi.text("2026 NCAA CHAMPIONS", 10f, TileUi.MUTED, bold = true))
        tf.men.firstOrNull()?.let { col.addContent(TileUi.text("Men: ${it.team} (${it.points})", 12f, 0xFF5EB8FF.toInt(), bold = true)) }
        tf.women.firstOrNull()?.let { col.addContent(TileUi.text("Women: ${it.team} (${it.points})", 12f, 0xFFFF7AB6.toInt(), bold = true)) }
        tf.news.firstOrNull()?.let {
            col.addContent(TileUi.spacer(4f))
            col.addContent(TileUi.text(it.text, 10f, TileUi.WHITE, maxLines = 2))
        }
        val root = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(TileUi.open(this, null, "tf", "tf")).build())
            .addContent(TileUi.screen(col.build())).build()
        return TileUi.tile(root, 6 * 60 * 60_000L)
    }
}
