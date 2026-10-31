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
}

/**
 * Refresh schedule:
 *  - every 15 minutes, always (Android's minimum for background jobs)
 *  - every LIVE_REFRESH_MINUTES while any game is live or about to kick off
 *  - immediately when you tap the refresh button
 */
object Scheduler {
    private const val PERIODIC = "scores_periodic"
    private const val FAST = "scores_fast"
    private const val NOW = "scores_now"
    const val INPUT_FAST = "fast"

    private val online = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScoresWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build(),
        )
    }

    fun refreshNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScoresWorker>().setConstraints(online).build(),
        )
    }

    /** Called after each refresh. Keeps a fast refresh loop going only while it's needed. */
    fun scheduleFastIfNeeded(ctx: Context, games: List<Game>, calledFromFastRun: Boolean) {
        val now = System.currentTimeMillis()
        val busy = games.any {
            it.isLive || (it.isUpcoming && it.startMillis - now in -3_600_000L..15 * 60_000L)
        }
        if (!busy) return
        val request = OneTimeWorkRequestBuilder<ScoresWorker>()
            .setInitialDelay(Config.LIVE_REFRESH_MINUTES, TimeUnit.MINUTES)
            .setConstraints(online)
            .setInputData(workDataOf(INPUT_FAST to true))
            .build()
        // A fast run chains the next one after itself; any other run leaves an existing loop alone.
        val policy = if (calledFromFastRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniqueWork(FAST, policy, request)
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        listOf(PERIODIC, FAST, NOW).forEach { wm.cancelUniqueWork(it) }
    }
}

class ScoresWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ids = GlanceAppWidgetManager(ctx).getGlanceIds(ScoresWidget::class.java)
        if (ids.isEmpty()) {
            Scheduler.cancelAll(ctx)
            return Result.success()
        }

        return try {
            val games = withContext(Dispatchers.IO) {
                EspnSource.fetch().also { list ->
                    Logos.ensure(ctx, list.flatMap { listOf(it.away.id, it.home.id) }.toSet())
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
            ScoresWidget().updateAll(ctx)
            Scheduler.scheduleFastIfNeeded(ctx, games, inputData.getBoolean(Scheduler.INPUT_FAST, false))
            Result.success()
        } catch (e: Exception) {
            ids.forEach { id ->
                updateAppWidgetState(ctx, id) { prefs ->
                    prefs[Keys.ERROR] = "Offline"
                    prefs[Keys.REFRESHING] = false
                }
            }
            ScoresWidget().updateAll(ctx)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}

/** The ⟳ button. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[Keys.REFRESHING] = true }
        ScoresWidget().update(context, glanceId)
        Scheduler.refreshNow(context)
    }
}

class ScoresWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScoresWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Scheduler.ensurePeriodic(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Scheduler.cancelAll(context)
    }
}
