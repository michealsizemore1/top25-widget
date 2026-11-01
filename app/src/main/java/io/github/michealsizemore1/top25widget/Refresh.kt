package io.github.michealsizemore1.top25widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What each widget instance remembers between refreshes. */
object Keys {
    val GAMES = stringPreferencesKey("games")
    val UPDATED = longPreferencesKey("updated")
    val ERROR = stringPreferencesKey("error")
    val REFRESHING = booleanPreferencesKey("refreshing")
    val NEWS = stringPreferencesKey("news")
}

fun widgetFor(league: League): LeagueScoresWidget = when (league) {
    League.CFB -> ScoresWidget()
    League.NFL -> NflScoresWidget()
    League.WNBA -> WnbaScoresWidget()
    League.NHL -> NhlScoresWidget()
}

/**
 * Refresh schedule, per league:
 *  - every 15 minutes, always (Android's minimum for background jobs)
 *  - every LIVE_REFRESH_MINUTES while any game is live or about to kick off
 *  - immediately when you tap the refresh button
 */
object Scheduler {
    const val INPUT_FAST = "fast"
    const val INPUT_LEAGUE = "league"

    private fun name(kind: String, league: League) = "scores_${kind}_${league.key}"

    private val online = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(ctx: Context, league: League) {
        // Clean up jobs from versions before the NFL widget existed.
        WorkManager.getInstance(ctx).apply { listOf("scores_periodic", "scores_fast", "scores_now").forEach { cancelUniqueWork(it) } }
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            name("periodic", league),
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScoresWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .setInputData(workDataOf(INPUT_LEAGUE to league.key))
                .build(),
        )
    }

    fun refreshNow(ctx: Context, league: League) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            name("now", league),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScoresWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(INPUT_LEAGUE to league.key))
                // Ask Android to run a tapped refresh right away, not when it gets around to it.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    /** Called after each refresh. Keeps a fast refresh loop going only while it's needed. */
    fun scheduleFastIfNeeded(ctx: Context, league: League, games: List<Game>, calledFromFastRun: Boolean) {
        val now = System.currentTimeMillis()
        val busy = games.any {
            it.isLive || (it.isUpcoming && it.startMillis - now in -3_600_000L..15 * 60_000L)
        }
        if (!busy) return
        val request = OneTimeWorkRequestBuilder<ScoresWorker>()
            .setInitialDelay(Config.LIVE_REFRESH_MINUTES, TimeUnit.MINUTES)
            .setConstraints(online)
            .setInputData(workDataOf(INPUT_FAST to true, INPUT_LEAGUE to league.key))
            .build()
        // REPLACE: every run (fast, periodic, or tapped) sets the next check 2 minutes out,
        // so a failed or stuck run can never leave the live loop stalled.
        WorkManager.getInstance(ctx).enqueueUniqueWork(name("fast", league), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(ctx: Context, league: League) {
        val wm = WorkManager.getInstance(ctx)
        listOf("periodic", "fast", "now").forEach { wm.cancelUniqueWork(name(it, league)) }
    }
}

class ScoresWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Needed for expedited runs on Android 11 and older; Android 12+ doesn't show it. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("sync", "Score updates", android.app.NotificationManager.IMPORTANCE_MIN))
        val n = android.app.Notification.Builder(applicationContext, "sync")
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle("Updating scores…")
            .build()
        return ForegroundInfo(42, n)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val league = League.from(inputData.getString(Scheduler.INPUT_LEAGUE))
        val fast = inputData.getBoolean(Scheduler.INPUT_FAST, false)
        val widget = widgetFor(league)
        val ids = GlanceAppWidgetManager(ctx).getGlanceIds(widget.javaClass)
        if (ids.isEmpty()) {
            Scheduler.cancelAll(ctx, league)
            return Result.success()
        }
        Status.attempt(ctx, league)

        var games: List<Game>? = null
        try {
            val fetched: List<Game> = withTimeout(45_000) {
                withContext(Dispatchers.IO) { EspnSource.fetch(league) }
            }
            games = fetched
            val json = GameJson.encode(fetched)
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { prefs ->
                    prefs[Keys.GAMES] = json
                    prefs[Keys.UPDATED] = now
                    prefs[Keys.REFRESHING] = false
                    prefs.remove(Keys.ERROR)
                }
            }
            withTimeoutOrNull(20_000) { widget.updateAll(ctx) }
            Status.success(ctx, league, fetched.size)

            // Logos and alerts come after the scores are on screen.
            withTimeoutOrNull(30_000) {
                withContext(Dispatchers.IO) {
                    Logos.ensure(ctx, fetched.flatMap { listOf(it.away, it.home) }.associate { it.logoKey to it.logoUrl })
                }
                widget.updateAll(ctx)
            }
            if (league == League.CFB) {
                try { Alerts.check(ctx, fetched) } catch (e: Exception) { /* never let alerts break scores */ }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Status.failure(ctx, league, e)
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { prefs ->
                    prefs[Keys.ERROR] = if (e is kotlinx.coroutines.TimeoutCancellationException) "Slow" else "Offline"
                    prefs[Keys.REFRESHING] = false
                }
            }
            withTimeoutOrNull(20_000) { widget.updateAll(ctx) }
        } finally {
            // Keep the 2-minute live loop going even if this run failed: use the last good list.
            val known = games ?: lastGames(ctx, ids.first())
            try { Scheduler.scheduleFastIfNeeded(ctx, league, known, fast) } catch (e: Exception) { }
        }
        return Result.success()
    }

    private suspend fun lastGames(ctx: Context, id: GlanceId): List<Game> = try {
        val prefs = androidx.glance.appwidget.state.getAppWidgetState(
            ctx, androidx.glance.state.PreferencesGlanceStateDefinition, id,
        )
        GameJson.decode(prefs[Keys.GAMES])
    } catch (e: Exception) {
        emptyList()
    }
}

/** What the last refresh did, shown on the app's main screen to help diagnose problems. */
object Status {
    private fun p(ctx: Context) = ctx.getSharedPreferences("status", Context.MODE_PRIVATE)
    fun attempt(ctx: Context, l: League) = p(ctx).edit().putLong("${l.key}_try", System.currentTimeMillis()).apply()
    fun success(ctx: Context, l: League, n: Int) = p(ctx).edit()
        .putLong("${l.key}_ok", System.currentTimeMillis()).putInt("${l.key}_n", n).remove("${l.key}_err").apply()
    fun failure(ctx: Context, l: League, e: Exception) = p(ctx).edit()
        .putString("${l.key}_err", (e::class.simpleName ?: "Error") + ": " + (e.message ?: "").take(80)).apply()

    fun describe(ctx: Context, l: League): String {
        val fmt = java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault())
        val sp = p(ctx)
        val tried = sp.getLong("${l.key}_try", 0)
        val ok = sp.getLong("${l.key}_ok", 0)
        val err = sp.getString("${l.key}_err", null)
        if (tried == 0L) return "${l.shortTitle}: no refresh yet"
        return "${l.shortTitle}: checked ${fmt.format(tried)}" +
            (if (ok > 0) " · updated ${fmt.format(ok)} (${sp.getInt("${l.key}_n", 0)} games)" else "") +
            (err?.let { " · last error: $it" } ?: "")
    }
}

/** The ⟳ button. */
class RefreshAction : ActionCallback {
    companion object {
        val LEAGUE = ActionParameters.Key<String>("league")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val league = League.from(parameters[LEAGUE])
        updateAppWidgetState(context, glanceId) { it[Keys.REFRESHING] = true }
        widgetFor(league).update(context, glanceId)
        Scheduler.refreshNow(context, league)
    }
}

abstract class LeagueWidgetReceiver(private val league: League) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = widgetFor(league)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Scheduler.ensurePeriodic(context, league)
        Scheduler.refreshNow(context, league)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Scheduler.cancelAll(context, league)
    }
}

/** College Top 25 widget (class name kept so existing widgets survive the update). */
class ScoresWidgetReceiver : LeagueWidgetReceiver(League.CFB)

class NflScoresWidgetReceiver : LeagueWidgetReceiver(League.NFL)

class WnbaScoresWidgetReceiver : LeagueWidgetReceiver(League.WNBA)

class NhlScoresWidgetReceiver : LeagueWidgetReceiver(League.NHL)
