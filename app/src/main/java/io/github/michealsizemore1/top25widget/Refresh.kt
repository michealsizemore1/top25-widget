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
        // A fast run chains the next one after itself; any other run leaves an existing loop alone.
        val policy = if (calledFromFastRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniqueWork(name("fast", league), policy, request)
    }

    fun cancelAll(ctx: Context, league: League) {
        val wm = WorkManager.getInstance(ctx)
        listOf("periodic", "fast", "now").forEach { wm.cancelUniqueWork(name(it, league)) }
    }
}

class ScoresWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val league = League.from(inputData.getString(Scheduler.INPUT_LEAGUE))
        val widget = widgetFor(league)
        val ids = GlanceAppWidgetManager(ctx).getGlanceIds(widget.javaClass)
        if (ids.isEmpty()) {
            Scheduler.cancelAll(ctx, league)
            return Result.success()
        }

        return try {
            val games = withContext(Dispatchers.IO) {
                EspnSource.fetch(league).also { list ->
                    Logos.ensure(ctx, list.flatMap { listOf(it.away, it.home) }.associate { it.logoKey to it.logoUrl })
                }
            }
            val json = GameJson.encode(games)
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { prefs ->
                    prefs[Keys.GAMES] = json
                    prefs[Keys.UPDATED] = now
                    prefs[Keys.REFRESHING] = false
                    prefs.remove(Keys.ERROR)
                }
            }
            widget.updateAll(ctx)
            Scheduler.scheduleFastIfNeeded(ctx, league, games, inputData.getBoolean(Scheduler.INPUT_FAST, false))
            Result.success()
        } catch (e: Exception) {
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { prefs ->
                    prefs[Keys.ERROR] = "Offline"
                    prefs[Keys.REFRESHING] = false
                }
            }
            widget.updateAll(ctx)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
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
