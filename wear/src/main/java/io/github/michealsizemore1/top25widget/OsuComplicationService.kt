package io.github.michealsizemore1.top25widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Watch face slot: Ohio State's score ("24-17" / "Q3"), or next game when idle. */
class OsuComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        fun requestUpdate(ctx: Context) {
            ComplicationDataSourceUpdateRequester
                .create(ctx, ComponentName(ctx, OsuComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    private fun plain(s: String) = PlainComplicationText.Builder(s).build()

    private fun tap(gameId: String?): PendingIntent {
        val i = Intent(this, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (gameId != null) i.putExtra(WatchActivity.EXTRA_GAME, gameId)
        return PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun build(type: ComplicationType, short: String, title: String, long: String, gameId: String?): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(short), plain(long))
                .setTitle(plain(title)).setTapAction(tap(gameId)).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(long), plain(long))
                .setTitle(plain("Ohio State")).setTapAction(tap(gameId)).build()
            else -> null
        }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, "24-17", "OSU", "OSU 24, USC 17 · Q3 4:12", null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val type = request.complicationType
        val game = try {
            withContext(Dispatchers.IO) { WatchFeed.osuGame(WatchFeed.games(maxAgeMs = 2 * 60_000)) }
        } catch (e: Exception) {
            return build(type, "--", "OSU", "Ohio State · scores unavailable", null)
        }
        if (game == null) {
            val next = withContext(Dispatchers.IO) { WatchFeed.osuNext() }.replace("\n", " · ")
            return build(type, "Next", "OSU", next, null)
        }
        val osuAway = game.away.id == WatchFeed.OSU_ID || game.away.name == "Ohio State"
        val osu = if (osuAway) game.away else game.home
        val opp = if (osuAway) game.home else game.away
        val status = WatchFeed.status(game)
        return if (game.isUpcoming) {
            build(type, status.substringBefore(" "), "OSU", "OSU vs ${WatchFeed.short(opp)} · $status", game.id)
        } else {
            // Short title: quarter ("Q3") or FINAL
            val title = when {
                game.isFinal -> "FINAL"
                Regex("(\\d)(st|nd|rd|th)").find(game.detail) != null ->
                    "Q" + Regex("(\\d)(st|nd|rd|th)").find(game.detail)!!.groupValues[1]
                game.detail.contains("Half", true) -> "HALF"
                game.detail.contains("OT") -> "OT"
                else -> "OSU"
            }
            build(type, "${osu.score}-${opp.score}", title,
                "OSU ${osu.score}, ${WatchFeed.short(opp)} ${opp.score} · $status", game.id)
        }
    }
}
