package io.github.michealsizemore1.beyondstride

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

