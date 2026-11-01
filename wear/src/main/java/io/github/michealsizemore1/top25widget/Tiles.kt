package io.github.michealsizemore1.top25widget

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// ====================================================================== shared tile pieces

private const val RES_VERSION = "1"
private const val LIVE_REFRESH_MS = 2 * 60_000L
private const val IDLE_REFRESH_MS = 15 * 60_000L

object TileUi {
    const val ACCENT = 0xFFD4E635.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFF9AA3A0.toInt()
    const val DIM = 0xFF6B7370.toInt()
    const val CARD = 0xFF1C2421.toInt()
    const val OSU_RED = 0xFFBB0000.toInt()

    fun text(s: String, size: Float, color: Int = WHITE, bold: Boolean = false, maxLines: Int = 1,
             align: Int = LayoutElementBuilders.TEXT_ALIGN_CENTER): LayoutElement =
        Text.Builder()
            .setText(s)
            .setMaxLines(maxLines)
            .setMultilineAlignment(align)
            .setFontStyle(FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).apply {
                if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
            }.build())
            .build()

    fun spacer(h: Float): LayoutElement = Spacer.Builder().setHeight(dp(h)).build()

    /** Fixed-width cell so rows line up (protolayout rows have no weights). */
    fun cell(width: Float, content: LayoutElement, alignEnd: Boolean = false): LayoutElement =
        Box.Builder().setWidth(dp(width)).setHeight(wrap())
            .setHorizontalAlignment(if (alignEnd) LayoutElementBuilders.HORIZONTAL_ALIGN_END else LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .addContent(content).build()

    /** Opens the watch app, on a game when [gameId] is given. */
    fun open(ctx: Context, gameId: String?, clickId: String, mode: String = League.CFB.key): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(ctx.packageName)
            .setClassName(WatchActivity::class.java.name)
            .addKeyToExtraMapping(WatchActivity.EXTRA_MODE, ActionBuilders.AndroidStringExtra.Builder().setValue(mode).build())
        if (gameId != null) activity.addKeyToExtraMapping(
            WatchActivity.EXTRA_GAME, ActionBuilders.AndroidStringExtra.Builder().setValue(gameId).build(),
        )
        return ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    fun card(content: LayoutElement, click: ModifiersBuilders.Clickable, color: Int = CARD): LayoutElement =
        Box.Builder().setWidth(expand()).setHeight(wrap())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(12f)).build()).build())
                .setPadding(ModifiersBuilders.Padding.Builder()
                    .setStart(dp(10f)).setEnd(dp(10f)).setTop(dp(5f)).setBottom(dp(5f)).build())
                .setClickable(click)
                .build())
            .addContent(content).build()

    /** Centers content on the round screen with side margins. */
    fun screen(content: LayoutElement): LayoutElement =
        Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setPadding(ModifiersBuilders.Padding.Builder()
                    .setStart(dp(14f)).setEnd(dp(14f)).setTop(dp(10f)).setBottom(dp(10f)).build())
                .build())
            .addContent(content).build()

    fun tile(root: LayoutElement, freshnessMs: Long): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(freshnessMs)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()

    fun column(vararg items: LayoutElement): LayoutElement =
        Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .apply { items.forEach { addContent(it) } }
            .build()

    fun message(title: String, body: String): LayoutElement =
        screen(column(text(title, 14f, ACCENT, bold = true), spacer(6f), text(body, 13f, MUTED, maxLines = 4)))
}

/** Runs suspend work for a tile request on a background coroutine. */
abstract class CoroutineTileService : TileService() {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    abstract suspend fun buildTile(): TileBuilders.Tile

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { done ->
            scope.launch {
                try { done.set(buildTile()) } catch (e: Throwable) { done.setException(e) }
            }
            "tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { done ->
            done.set(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
            "resources"
        }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

// ====================================================================== Top 25 tile

class Top25TileService : LeagueTileService(League.CFB)
class NflTileService : LeagueTileService(League.NFL)
class WnbaTileService : LeagueTileService(League.WNBA)
class NhlTileService : LeagueTileService(League.NHL)

abstract class LeagueTileService(private val league: League) : CoroutineTileService() {
    private val title get() = league.shortTitle.uppercase()

    override suspend fun buildTile(): TileBuilders.Tile {
        val games = try { WatchFeed.games(league) } catch (e: Exception) {
            return TileUi.tile(TileUi.message(title, "Can't reach scores right now"), IDLE_REFRESH_MS)
        }
        if (games.isEmpty()) return TileUi.tile(TileUi.message(title, league.emptyMessage), 60 * 60_000L)

        val shown = games.take(3)
        val col = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(TileUi.text(title, 12f, TileUi.ACCENT, bold = true))
            .addContent(TileUi.spacer(4f))
        shown.forEachIndexed { i, g ->
            col.addContent(gameRow(g, i))
            col.addContent(TileUi.spacer(3f))
        }
        val more = games.size - shown.size
        col.addContent(Box.Builder().setWidth(wrap()).setHeight(wrap())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(TileUi.open(this, null, "all", league.key)).build())
            .addContent(TileUi.text(if (more > 0) "+$more more games" else "All games", 11f, TileUi.MUTED))
            .build())

        val anyLive = games.any { it.isLive }
        return TileUi.tile(TileUi.screen(col.build()), if (anyLive) LIVE_REFRESH_MS else IDLE_REFRESH_MS)
    }

    private fun gameRow(g: Game, i: Int): LayoutElement {
        fun teamLine(t: TeamLine): LayoutElement {
            val lost = g.isFinal && !t.winner
            val color = if (lost) TileUi.DIM else TileUi.WHITE
            return Row.Builder().setWidth(wrap())
                .addContent(TileUi.cell(70f, TileUi.text(WatchFeed.short(t), 13f, color, bold = t.rank != null && !lost,
                    align = LayoutElementBuilders.TEXT_ALIGN_START)))
                .addContent(TileUi.cell(24f, TileUi.text(if (g.isUpcoming) "" else t.score, 14f, color, bold = true,
                    align = LayoutElementBuilders.TEXT_ALIGN_END), alignEnd = true))
                .build()
        }
        val statusColor = if (g.isLive) TileUi.ACCENT else TileUi.MUTED
        val row = Row.Builder().setWidth(wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(Column.Builder().setWidth(wrap())
                .addContent(teamLine(g.away)).addContent(teamLine(g.home)).build())
            .addContent(TileUi.cell(54f, TileUi.text(WatchFeed.status(g), 11f, statusColor, bold = g.isLive, maxLines = 2,
                align = LayoutElementBuilders.TEXT_ALIGN_END), alignEnd = true))
            .build()
        return TileUi.card(row, TileUi.open(this, g.id, "game$i", league.key))
    }
}

// ====================================================================== Ohio State tile

class OsuTileService : CoroutineTileService() {
    override suspend fun buildTile(): TileBuilders.Tile {
        val game = try { WatchFeed.osuGame(WatchFeed.games()) } catch (e: Exception) {
            return TileUi.tile(TileUi.message("OHIO STATE", "Can't reach scores right now"), IDLE_REFRESH_MS)
        }
        if (game == null) {
            val next = WatchFeed.osuNext()
            return TileUi.tile(
                TileUi.screen(TileUi.column(
                    TileUi.text("OHIO STATE", 13f, TileUi.OSU_RED, bold = true),
                    TileUi.spacer(6f),
                    TileUi.text("NEXT GAME", 11f, TileUi.MUTED),
                    TileUi.spacer(2f),
                    TileUi.text(next, 15f, TileUi.WHITE, bold = true, maxLines = 3),
                )),
                60 * 60_000L,
            )
        }

        val osuAway = game.away.id == WatchFeed.OSU_ID || game.away.name == "Ohio State"
        val osu = if (osuAway) game.away else game.home
        val opp = if (osuAway) game.home else game.away
        val live = game.live

        val col = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(TileUi.text((osu.rank?.let { "#$it " } ?: "") + "OHIO STATE", 12f, TileUi.OSU_RED, bold = true))
            .addContent(TileUi.text((if (osuAway) "at " else "vs ") + WatchFeed.short(opp), 12f, TileUi.MUTED))
            .addContent(TileUi.spacer(2f))

        if (game.isUpcoming) {
            col.addContent(TileUi.text(WatchFeed.status(game), 26f, TileUi.WHITE, bold = true))
            if (game.network.isNotBlank()) col.addContent(TileUi.text(game.network, 12f, TileUi.MUTED))
        } else {
            val hasBall = live?.possessionId?.let { it.isNotBlank() && it == osu.id } == true
            val oppBall = live?.possessionId?.let { it.isNotBlank() && it == opp.id } == true
            col.addContent(TileUi.text(
                "${if (hasBall) "🏈 " else ""}${osu.score} – ${opp.score}${if (oppBall) " 🏈" else ""}",
                30f, TileUi.WHITE, bold = true))
            col.addContent(TileUi.text(WatchFeed.status(game), 13f,
                if (game.isLive) TileUi.ACCENT else TileUi.MUTED, bold = true))
            if (live != null) {
                val spot = live.possessionText.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
                if (live.downText.isNotBlank()) col.addContent(TileUi.text(live.downText + spot, 12f, TileUi.WHITE))
                if (live.lastPlay.isNotBlank()) {
                    col.addContent(TileUi.spacer(2f))
                    col.addContent(TileUi.text(live.lastPlay, 10f, TileUi.MUTED, maxLines = 2))
                }
                live.homeWinPct?.let { hwp ->
                    val osuPct = if (osuAway) 1 - hwp else hwp
                    col.addContent(TileUi.text("Win chance ${WatchFeed.pct(osuPct)}", 10f, TileUi.DIM))
                }
            }
        }

        val root = Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(TileUi.open(this, game.id, "osu")).build())
            .addContent(TileUi.screen(col.build()))
            .build()
        return TileUi.tile(root, if (game.isLive) LIVE_REFRESH_MS else IDLE_REFRESH_MS)
    }
}
