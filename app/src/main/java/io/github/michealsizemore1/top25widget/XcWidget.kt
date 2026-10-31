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
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

// ====================================================================== data

/** Published every 3 hours by the Master App repo's update-xc-meets workflow. */
object XcConfig {
    const val BASE = "https://michealsizemore1.github.io/ChatGPT-The-Master-App/"
    const val DATA_URL = BASE + "data/xc-meets.json"
    const val PAGE_URL = BASE + "cross-country.html"
}

data class XcMeet(val name: String, val start: LocalDate, val end: LocalDate, val where: String, val watch: String)
data class XcResult(val date: LocalDate?, val headline: String, val source: String, val url: String)
data class XcData(val meets: List<XcMeet>, val results: List<XcResult>)

object XcFeed {
    fun download(): String = String(Http.get(XcConfig.DATA_URL + "?t=" + System.currentTimeMillis()))

    fun parse(json: String?): XcData {
        if (json.isNullOrBlank()) return XcData(emptyList(), emptyList())
        return try {
            val o = JSONObject(json)
            val meets = o.optJSONArray("meets")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val m = a.getJSONObject(i)
                    val start = date(m.optString("start")) ?: return@mapNotNull null
                    XcMeet(m.optString("name"), start, date(m.optString("end")) ?: start,
                        m.optString("where"), m.optString("watch"))
                }
            }.orEmpty()
            val results = o.optJSONArray("results")?.let { a ->
                (0 until a.length()).map { i ->
                    val r = a.getJSONObject(i)
                    XcResult(date(r.optString("date")), r.optString("headline"), r.optString("source"), r.optString("url"))
                }
            }.orEmpty()
            XcData(meets, results)
        } catch (e: Exception) {
            XcData(emptyList(), emptyList())
        }
    }

    private fun date(s: String): LocalDate? = try { LocalDate.parse(s) } catch (e: Exception) { null }

    /** The meet happening now, or the next one coming up. */
    fun nextMeet(meets: List<XcMeet>, today: LocalDate = LocalDate.now()): XcMeet? =
        meets.filter { !it.end.isBefore(today) }.minByOrNull { it.start }
}

// ====================================================================== refresh

object XcScheduler {
    private const val PERIODIC = "xc_periodic"
    private const val NOW = "xc_now"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<XcWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    fun refreshNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<XcWorker>().setConstraints(online).build(),
        )
    }

    fun cancelAll(ctx: Context) {
        WorkManager.getInstance(ctx).apply { cancelUniqueWork(PERIODIC); cancelUniqueWork(NOW) }
    }
}

class XcWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ids = GlanceAppWidgetManager(ctx).getGlanceIds(XcMeetsWidget::class.java)
        if (ids.isEmpty()) { XcScheduler.cancelAll(ctx); return Result.success() }
        return try {
            val json = withContext(Dispatchers.IO) { XcFeed.download() }
            XcFeed.parse(json) // validate before storing
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { p ->
                    p[Keys.GAMES] = json; p[Keys.UPDATED] = now; p[Keys.REFRESHING] = false; p.remove(Keys.ERROR)
                }
            }
            XcMeetsWidget().updateAll(ctx)
            Result.success()
        } catch (e: Exception) {
            ids.forEach { id -> updateAppWidgetState(ctx, id) { p -> p[Keys.ERROR] = "Offline"; p[Keys.REFRESHING] = false } }
            XcMeetsWidget().updateAll(ctx)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}

class XcRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[Keys.REFRESHING] = true }
        XcMeetsWidget().update(context, glanceId)
        XcScheduler.refreshNow(context)
    }
}

class XcWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = XcMeetsWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        XcScheduler.ensurePeriodic(context)
        XcScheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        XcScheduler.cancelAll(context)
    }
}

// ====================================================================== UI

class XcMeetsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 50.dp), DpSize(250.dp, 50.dp),
            DpSize(110.dp, 140.dp), DpSize(250.dp, 140.dp), DpSize(250.dp, 260.dp),
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { XcContent() }
    }
}

private val shortDate = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

private fun meetWhen(m: XcMeet, today: LocalDate): String = when {
    !today.isBefore(m.start) && !today.isAfter(m.end) -> "TODAY"
    m.start == today.plusDays(1) -> "TOMORROW"
    m.start == m.end -> m.start.format(shortDate)
    else -> m.start.format(shortDate) + "–" + m.end.format(shortDate)
}

private fun resultDate(d: LocalDate?, today: LocalDate): String = when (d) {
    null -> ""
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(shortDate)
}

private fun openUrl(url: String) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

@Composable
private fun XcContent() {
    val prefs = currentState<Preferences>()
    val json = prefs[Keys.GAMES]
    val data = remember(json) { XcFeed.parse(json) }
    val updated = prefs[Keys.UPDATED]
    val refreshing = prefs[Keys.REFRESHING] ?: false
    val error = prefs[Keys.ERROR]
    val today = LocalDate.now()
    val next = XcFeed.nextMeet(data.meets, today)
    val upcoming = data.meets.filter { it != next && it.start.isAfter(today) }.sortedBy { it.start }

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
                    val latest = data.results.firstOrNull()
                    when {
                        latest != null -> ResultCard(latest, today, narrow, lines = 2)
                        next != null -> MeetCard(next, today, narrow)
                        else -> Text(if (updated == null) "Loading meets…" else "No meet results yet",
                            style = style(Palette.muted, 15.sp))
                    }
                }
                XcRefreshButton(refreshing)
            }
            return@Column
        }

        // Header
        val stamp = updated?.let { formatTime(it) }
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (narrow) "XC" else "XC Meets",
                style = style(size = 17.sp, bold = true), maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(openUrl(XcConfig.PAGE_URL)),
            )
            Text(
                text = when {
                    refreshing -> "Updating…"
                    error != null -> listOfNotNull(error, stamp).joinToString(" · ")
                    else -> stamp.orEmpty()
                },
                style = style(Palette.muted, 12.sp), maxLines = 1,
            )
            XcRefreshButton(refreshing)
        }

        if (next == null && data.results.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (updated == null) "Loading meets…" else "No meet results yet",
                    style = style(Palette.muted, 15.sp))
            }
            return@Column
        }

        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            if (next != null) {
                item(itemId = 1_000_000_001L) {
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        MeetCard(next, today, narrow)
                        Spacer(modifier = GlanceModifier.height(8.dp))
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                item(itemId = 1_000_000_003L) {
                    Text(
                        text = "UPCOMING MEETS",
                        style = style(Palette.muted, 13.sp, bold = true),
                        modifier = GlanceModifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(upcoming, itemId = { m -> 2_000_000_000L + m.start.toEpochDay() * 10 + (m.name.hashCode() and 7) }) { m ->
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        UpcomingMeetRow(m, today, narrow)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                }
                item(itemId = 1_000_000_004L) { Spacer(modifier = GlanceModifier.height(4.dp)) }
            }
            if (data.results.isNotEmpty()) {
                item(itemId = 1_000_000_002L) {
                    Text(
                        text = "LATEST RESULTS",
                        style = style(Palette.muted, 13.sp, bold = true),
                        modifier = GlanceModifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            items(data.results, itemId = { r -> (r.url.hashCode().toLong() and 0x7fffffffL) }) { r ->
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    ResultCard(r, today, narrow, lines = if (narrow) 4 else 3)
                    Spacer(modifier = GlanceModifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MeetCard(m: XcMeet, today: LocalDate, narrow: Boolean) {
    val whenText = meetWhen(m, today)
    val live = whenText == "TODAY"
    Column(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(openUrl(XcConfig.PAGE_URL)),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (live) "MEET DAY" else "NEXT MEET",
                style = style(Palette.accent, 13.sp, bold = true),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(text = whenText, style = style(if (live) Palette.accent else Palette.text, 16.sp, bold = true))
        }
        Spacer(modifier = GlanceModifier.height(3.dp))
        Text(text = m.name, style = style(size = if (narrow) 15.sp else 17.sp, bold = true), maxLines = 2)
        if (!narrow) {
            Text(text = m.where, style = style(Palette.muted, 14.sp), maxLines = 1)
            if (m.watch.isNotBlank()) Text(text = "Watch: ${m.watch}", style = style(Palette.dim, 13.sp), maxLines = 2)
        }
    }
}

@Composable
private fun UpcomingMeetRow(m: XcMeet, today: LocalDate, narrow: Boolean) {
    val days = ChronoUnit.DAYS.between(today, m.start)
    val countdown = when (days) {
        1L -> "Tomorrow"
        in 2L..13L -> "in $days days"
        else -> "in ${days / 7} weeks"
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(openUrl(XcConfig.PAGE_URL)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.width(if (narrow) 58.dp else 72.dp)) {
            Text(text = m.start.format(shortDate).uppercase(Locale.getDefault()),
                style = style(Palette.accent, 15.sp, bold = true), maxLines = 1)
            Text(text = countdown, style = style(Palette.muted, 12.sp), maxLines = 1)
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(text = m.name, style = style(size = if (narrow) 15.sp else 17.sp, bold = true), maxLines = 2)
            if (!narrow) {
                Text(text = m.where, style = style(Palette.muted, 14.sp), maxLines = 1)
                if (m.watch.isNotBlank()) Text(text = "Watch: ${m.watch}", style = style(Palette.dim, 13.sp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ResultCard(r: XcResult, today: LocalDate, narrow: Boolean, lines: Int) {
    Column(
        modifier = GlanceModifier.fillMaxWidth().background(Palette.card).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .clickable(openUrl(r.url)),
    ) {
        Text(text = r.headline, style = style(size = if (narrow) 15.sp else 17.sp), maxLines = lines)
        Spacer(modifier = GlanceModifier.height(3.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(text = resultDate(r.date, today), style = style(Palette.accent, 13.sp, bold = true))
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(text = r.source, style = style(Palette.muted, 13.sp), maxLines = 1,
                modifier = GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun XcRefreshButton(refreshing: Boolean) {
    Image(
        provider = ImageProvider(R.drawable.ic_refresh),
        contentDescription = if (refreshing) "Refreshing" else "Refresh meets",
        modifier = GlanceModifier.size(30.dp).padding(4.dp).clickable(actionRunCallback<XcRefreshAction>()),
    )
}
