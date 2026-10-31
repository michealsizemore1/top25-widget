package io.github.michealsizemore1.top25widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

// ====================================================================== data

/** Exported from ncaa-track-field.html by the Master App repo's export-ncaa-tf workflow. */
object TfConfig {
    const val DATA_URL = XcConfig.BASE + "data/ncaa-tf.json"
    const val PAGE_URL = XcConfig.BASE + "ncaa-track-field.html"
}

data class TfStanding(val rank: Int, val team: String, val points: Int, val flag: String)
data class TfChampion(val event: String, val men: Boolean, val name: String, val team: String, val mark: String, val note: String)
data class TfChampionship(val name: String, val start: LocalDate, val end: LocalDate, val where: String)
data class TfCalendarItem(val whenText: String, val name: String, val where: String)
data class TfData(
    val season: String,
    val men: List<TfStanding>,
    val women: List<TfStanding>,
    val champions: List<TfChampion>,
    val championships: List<TfChampionship>,
    val calendar: List<TfCalendarItem>,
) {
    val isEmpty get() = men.isEmpty() && women.isEmpty() && champions.isEmpty()
}

object TfFeed {
    fun download(): String = String(Http.get(TfConfig.DATA_URL + "?t=" + System.currentTimeMillis()))

    private fun <T> JSONArray?.list(f: (JSONObject) -> T?): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { f(getJSONObject(it)) }

    private fun date(s: String): LocalDate? = try { LocalDate.parse(s) } catch (e: Exception) { null }

    fun parse(json: String?): TfData {
        val empty = TfData("", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        if (json.isNullOrBlank()) return empty
        return try {
            val o = JSONObject(json)
            val st = o.optJSONObject("standings")
            val standing = { s: JSONObject ->
                TfStanding(s.optInt("rank"), s.optString("team"), s.optInt("points"), s.optString("flag"))
            }
            TfData(
                season = o.optString("season"),
                men = st?.optJSONArray("men").list(standing),
                women = st?.optJSONArray("women").list(standing),
                champions = o.optJSONArray("champions").list { c ->
                    TfChampion(c.optString("event"), c.optString("gender") == "men", c.optString("name"),
                        c.optString("team"), c.optString("mark"), c.optString("note"))
                },
                championships = o.optJSONArray("championships").list { c ->
                    val start = date(c.optString("start")) ?: return@list null
                    TfChampionship(c.optString("name"), start, date(c.optString("end")) ?: start, c.optString("where"))
                },
                calendar = o.optJSONArray("calendar").list { c ->
                    TfCalendarItem(c.optString("when"), c.optString("name"), c.optString("where"))
                },
            )
        } catch (e: Exception) {
            empty
        }
    }
}

// ====================================================================== refresh

object TfScheduler {
    private const val PERIODIC = "tf_periodic"
    private const val NOW = "tf_now"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TfWorker>(6, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }

    fun refreshNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TfWorker>().setConstraints(online).build(),
        )
    }

    fun cancelAll(ctx: Context) {
        WorkManager.getInstance(ctx).apply { cancelUniqueWork(PERIODIC); cancelUniqueWork(NOW) }
    }
}

class TfWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ids = GlanceAppWidgetManager(ctx).getGlanceIds(TrackFieldWidget::class.java)
        if (ids.isEmpty()) { TfScheduler.cancelAll(ctx); return Result.success() }
        return try {
            val json = withContext(Dispatchers.IO) { TfFeed.download() }
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { p ->
                    p[Keys.GAMES] = json; p[Keys.UPDATED] = now; p[Keys.REFRESHING] = false; p.remove(Keys.ERROR)
                }
            }
            TrackFieldWidget().updateAll(ctx)
            Result.success()
        } catch (e: Exception) {
            ids.forEach { id -> updateAppWidgetState(ctx, id) { p -> p[Keys.ERROR] = "Offline"; p[Keys.REFRESHING] = false } }
            TrackFieldWidget().updateAll(ctx)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}

class TfRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[Keys.REFRESHING] = true }
        TrackFieldWidget().update(context, glanceId)
        TfScheduler.refreshNow(context)
    }
}

class TfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackFieldWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        TfScheduler.ensurePeriodic(context)
        TfScheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TfScheduler.cancelAll(context)
    }
}

// ====================================================================== UI

class TrackFieldWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 50.dp), DpSize(250.dp, 50.dp),
            DpSize(110.dp, 140.dp), DpSize(250.dp, 140.dp), DpSize(250.dp, 260.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { TfContent() }
    }
}

private val tfMonthDay = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

private fun dateRange(c: TfChampionship): String =
    if (c.start == c.end) c.start.format(tfMonthDay)
    else if (c.start.month == c.end.month) "${c.start.format(tfMonthDay)}–${c.end.dayOfMonth}"
    else "${c.start.format(tfMonthDay)}–${c.end.format(tfMonthDay)}"

private fun countdown(start: LocalDate, end: LocalDate, today: LocalDate): String {
    if (!today.isBefore(start) && !today.isAfter(end)) return "HAPPENING NOW"
    val days = ChronoUnit.DAYS.between(today, start)
    return when {
        days == 1L -> "TOMORROW"
        days < 14 -> "IN $days DAYS"
        days < 60 -> "IN ${days / 7} WEEKS"
        else -> "IN ${days / 30} MONTHS"
    }
}

/** "Men's 110m Hurdles" etc., plus the note with the event name stripped out of it. */
private fun eventLabel(c: TfChampion): Pair<String, String> {
    val who = if (c.men) "Men's" else "Women's"
    var event = c.event
    var note = c.note
    if (event.startsWith("Hurdles")) {
        event = if (c.men) "110m Hurdles" else "100m Hurdles"
        note = note.replace(Regex("^1[01]0m hurdles,?\\s*", RegexOption.IGNORE_CASE), "")
    }
    return "$who $event" to note
}

private fun openTfPage() =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TfConfig.PAGE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun TfContent() {
    val prefs = currentState<Preferences>()
    val json = prefs[Keys.GAMES]
    val data = remember(json) { TfFeed.parse(json) }
    val updated = prefs[Keys.UPDATED]
    val refreshing = prefs[Keys.REFRESHING] ?: false
    val error = prefs[Keys.ERROR]
    val today = LocalDate.now()
    val next = data.championships.filter { !it.end.isBefore(today) }.minByOrNull { it.start }

    val size = LocalSize.current
    val narrow = size.width < 200.dp
    val strip = size.height < 120.dp

    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(Palette.bg)
            .cornerRadius(18.dp).padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (strip) {
            Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = GlanceModifier.defaultWeight()) {
                    if (next != null) NextCard(next, today, narrow)
                    else Text(if (updated == null) "Loading…" else "Track & Field", style = style(Palette.muted, 15.sp))
                }
                TfRefreshButton(refreshing)
            }
            return@Column
        }

        val stamp = updated?.let { formatTime(it) }
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (narrow) "Track" else "Track & Field",
                style = style(size = 17.sp, bold = true), maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(openTfPage()),
            )
            Text(
                text = when {
                    refreshing -> "Updating…"
                    error != null -> listOfNotNull(error, stamp).joinToString(" · ")
                    else -> stamp.orEmpty()
                },
                style = style(Palette.muted, 12.sp), maxLines = 1,
            )
            TfRefreshButton(refreshing)
        }

        if (data.isEmpty) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (updated == null) "Loading…" else "No data yet", style = style(Palette.muted, 15.sp))
            }
            return@Column
        }

        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            if (next != null) item(itemId = 3_000_000_001L) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    NextCard(next, today, narrow)
                    Spacer(modifier = GlanceModifier.height(8.dp))
                }
            }

            item(itemId = 3_000_000_002L) { SectionLabel("2026 NCAA OUTDOOR · FINAL STANDINGS") }
            item(itemId = 3_000_000_003L) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    if (narrow) {
                        StandingsCard("Men", data.men.take(5))
                        Spacer(modifier = GlanceModifier.height(6.dp))
                        StandingsCard("Women", data.women.take(5))
                    } else {
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Box(modifier = GlanceModifier.defaultWeight()) { StandingsCard("Men", data.men.take(5)) }
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Box(modifier = GlanceModifier.defaultWeight()) { StandingsCard("Women", data.women.take(5)) }
                        }
                    }
                    Spacer(modifier = GlanceModifier.height(10.dp))
                }
            }

            if (data.champions.isNotEmpty()) {
                item(itemId = 3_000_000_004L) { SectionLabel("2026 NCAA CHAMPIONS") }
                items(data.champions, itemId = { c -> 4_000_000_000L + (("${c.event}|${c.men}").hashCode().toLong() and 0x7fffffffL) }) { c ->
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        ChampionRow(c, narrow)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                }
            }

            if (data.calendar.isNotEmpty()) {
                item(itemId = 3_000_000_005L) {
                    Column {
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        SectionLabel("2027 SEASON CALENDAR")
                    }
                }
                items(data.calendar, itemId = { c -> 5_000_000_000L + (c.name.hashCode().toLong() and 0x7fffffffL) }) { c ->
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        CalendarRow(c, narrow)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = style(Palette.muted, 13.sp, bold = true),
        modifier = GlanceModifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun NextCard(c: TfChampionship, today: LocalDate, narrow: Boolean) {
    val when_ = countdown(c.start, c.end, today)
    Column(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp).clickable(openTfPage()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "NEXT UP", style = style(Palette.accent, 13.sp, bold = true),
                modifier = GlanceModifier.defaultWeight())
            Text(text = when_, style = style(Palette.accent, if (narrow) 13.sp else 16.sp, bold = true))
        }
        Spacer(modifier = GlanceModifier.height(3.dp))
        Text(text = c.name, style = style(size = if (narrow) 15.sp else 17.sp, bold = true), maxLines = 2)
        Text(text = "${dateRange(c)}, ${c.start.year}" + if (narrow) "" else " · ${c.where}",
            style = style(Palette.muted, 14.sp), maxLines = 2)
    }
}

@Composable
private fun StandingsCard(title: String, rows: List<TfStanding>) {
    Column(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp).clickable(openTfPage()),
    ) {
        Text(text = title.uppercase(Locale.getDefault()), style = style(Palette.accent, 13.sp, bold = true))
        Spacer(modifier = GlanceModifier.height(4.dp))
        rows.forEach { r ->
            Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${r.rank}", style = style(Palette.muted, 13.sp), modifier = GlanceModifier.width(18.dp))
                Text(
                    text = r.team + if (r.flag.isNotBlank()) " ${r.flag}" else "",
                    style = style(size = 15.sp, bold = r.rank == 1), maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(text = "${r.points}", style = style(size = 15.sp, bold = true, align = TextAlign.End))
            }
        }
    }
}

@Composable
private fun ChampionRow(c: TfChampion, narrow: Boolean) {
    val (label, note) = eventLabel(c)
    val isRelay = c.name == c.team
    Column(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp).clickable(openTfPage()),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label.uppercase(Locale.getDefault()), style = style(Palette.accent, 12.sp, bold = true),
                maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text(text = c.mark, style = style(size = 16.sp, bold = true))
        }
        Text(text = c.name, style = style(size = if (narrow) 15.sp else 17.sp, bold = true), maxLines = 1)
        if (!isRelay) Text(text = c.team, style = style(Palette.muted, 14.sp), maxLines = 1)
        if (note.isNotBlank() && !narrow) Text(text = note, style = style(Palette.dim, 13.sp), maxLines = 2)
    }
}

@Composable
private fun CalendarRow(c: TfCalendarItem, narrow: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp).clickable(openTfPage()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = c.whenText.removeSuffix(" 2027").uppercase(Locale.getDefault()),
            style = style(Palette.accent, 14.sp, bold = true), maxLines = 2,
            modifier = GlanceModifier.width(if (narrow) 64.dp else 84.dp),
        )
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(text = c.name, style = style(size = if (narrow) 15.sp else 17.sp, bold = true), maxLines = 2)
            if (!narrow) Text(text = c.where, style = style(Palette.muted, 14.sp), maxLines = 1)
        }
    }
}

@Composable
private fun TfRefreshButton(refreshing: Boolean) {
    Image(
        provider = ImageProvider(R.drawable.ic_refresh),
        contentDescription = if (refreshing) "Refreshing" else "Refresh",
        modifier = GlanceModifier.size(30.dp).padding(4.dp).clickable(actionRunCallback<TfRefreshAction>()),
    )
}
