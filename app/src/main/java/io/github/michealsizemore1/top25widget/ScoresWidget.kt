package io.github.michealsizemore1.top25widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which league the widget being drawn belongs to. */
private val LocalLeague = staticCompositionLocalOf { League.CFB }

/** Colors matched to the Top 25 site. */
internal object Palette {
    val bg = Color(0xFF121A17)
    val card = Color(0xFF0D0D0D)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFF9AA3A0)
    val dim = Color(0xFF6B7370)
    val accent = Color(0xFFD4E635)
}

internal fun style(
    color: Color = Palette.text,
    size: TextUnit = 14.sp,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Start,
) = TextStyle(
    color = ColorProvider(color),
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = align,
)

/** College Top 25 widget. */
class ScoresWidget : LeagueScoresWidget(League.CFB)

/** NFL widget. */
class NflScoresWidget : LeagueScoresWidget(League.NFL)

/** WNBA widget. */
class WnbaScoresWidget : LeagueScoresWidget(League.WNBA)

/** NHL widget. */
class NhlScoresWidget : LeagueScoresWidget(League.NHL)

abstract class LeagueScoresWidget(val league: League) : GlanceAppWidget() {

    companion object {
        // Layout "breakpoints". Glance picks the closest one as you resize.
        private val STRIP_SMALL = DpSize(110.dp, 50.dp)    // 2x1
        private val STRIP_WIDE = DpSize(250.dp, 50.dp)     // 4x1
        private val LIST_NARROW = DpSize(110.dp, 140.dp)   // 2x2 and taller
        private val LIST_WIDE = DpSize(250.dp, 140.dp)     // 4x2
        private val LIST_LARGE = DpSize(250.dp, 260.dp)    // 4x3 and bigger
    }

    // Exact: draw only the size the widget actually is. Drawing all five layouts at once
    // with ~50 logos made busy-Saturday updates too big for Android to deliver.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            CompositionLocalProvider(LocalLeague provides league) { WidgetContent() }
        }
    }
}

/** Header / empty-state tap: opens the app's main screen. */
@Composable
private fun openSite(): Action {
    val context = LocalContext.current
    return actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun WidgetContent() {
    val prefs = currentState<Preferences>()
    val json = prefs[Keys.GAMES]
    val games = remember(json) { GameJson.decode(json) }
    val updated = prefs[Keys.UPDATED]
    val refreshing = prefs[Keys.REFRESHING] ?: false
    val error = prefs[Keys.ERROR]

    val size = LocalSize.current
    val narrow = size.width < 200.dp
    val strip = size.height < 120.dp          // 1 row tall: show just the top game
    val detailed = !narrow && size.height >= 260.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(Palette.bg)
            .cornerRadius(18.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (strip) {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = GlanceModifier.defaultWeight()) {
                    val first = games.firstOrNull()
                    if (first == null) EmptyMessage(updated) else GameCard(first, narrow, detailed = false)
                }
                RefreshButton(refreshing)
            }
        } else {
            Header(updated, refreshing, error, narrow)
            if (games.isEmpty()) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyMessage(updated)
                }
            } else {
                // Scrollable list: drag the widget taller to see more games at once.
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(games, itemId = { g -> g.id.toLongOrNull() ?: g.id.hashCode().toLong() }) { g ->
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            GameCard(g, narrow, detailed)
                            Spacer(modifier = GlanceModifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(updated: Long?) {
    Text(
        text = when {
            updated == null -> "Loading scores…"
            else -> LocalLeague.current.emptyMessage
        },
        style = style(Palette.muted, 15.sp),
        modifier = GlanceModifier.clickable(openSite()),
    )
}

@Composable
private fun RefreshButton(refreshing: Boolean) {
    Image(
        provider = ImageProvider(R.drawable.ic_refresh),
        contentDescription = if (refreshing) "Refreshing" else "Refresh scores",
        modifier = GlanceModifier
            .size(30.dp)
            .padding(4.dp)
            .clickable(
                actionRunCallback<RefreshAction>(
                    actionParametersOf(RefreshAction.LEAGUE to LocalLeague.current.key)
                )
            ),
    )
}

@Composable
private fun Header(updated: Long?, refreshing: Boolean, error: String?, narrow: Boolean) {
    val stamp = updated?.let { formatTime(it) }
    val status = when {
        refreshing -> "Updating…"
        error != null && stamp != null -> "$error · $stamp"
        error != null -> error
        stamp != null -> stamp
        else -> ""
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = LocalLeague.current.let { if (narrow) it.shortTitle else it.title },
            style = style(size = 17.sp, bold = true),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().clickable(openSite()),
        )
        Text(text = status, style = style(Palette.muted, 12.sp), maxLines = 1)
        RefreshButton(refreshing)
    }
}

@Composable
private fun GameCard(game: Game, narrow: Boolean, detailed: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Palette.card)
            .cornerRadius(12.dp)
            .padding(horizontal = if (narrow) 8.dp else 10.dp, vertical = 6.dp)
            .clickable(actionStartActivity(GameActivity.intent(context, LocalLeague.current, game.id))),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                TeamLineRow(game.away, game, narrow, detailed)
                Spacer(modifier = GlanceModifier.height(3.dp))
                TeamLineRow(game.home, game, narrow, detailed)
            }
            Status(game, narrow)
        }
        val showTv = detailed && game.network.isNotBlank()
        if (showTv || game.highlights > 0) {
            Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = if (showTv) "TV: ${game.network}" else "", style = style(Palette.dim, 13.sp),
                    modifier = GlanceModifier.defaultWeight())
                if (game.highlights > 0) Text(text = "▶ Highlights", style = style(Palette.accent, 13.sp, bold = true))
            }
        }
    }
}

@Composable
private fun TeamLineRow(team: TeamLine, game: Game, narrow: Boolean, detailed: Boolean) {
    val context = LocalContext.current
    val lost = game.isFinal && !team.winner
    val nameSize = if (narrow) 15.sp else 17.sp

    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!narrow) {
            val logo = Logos.small(context, team.logoKey)
            if (logo != null) {
                Image(
                    provider = ImageProvider(logo),
                    contentDescription = null,
                    modifier = GlanceModifier.size(24.dp),
                )
            } else {
                Spacer(modifier = GlanceModifier.size(24.dp))
            }
            Spacer(modifier = GlanceModifier.width(6.dp))
        }
        team.rank?.let {
            Text(text = "$it ", style = style(Palette.muted, 13.sp))
        }
        Text(
            text = if (narrow) team.abbr.ifBlank { team.name } else team.name,
            style = style(
                color = if (lost) Palette.dim else Palette.text,
                size = nameSize,
                bold = !lost && (team.rank != null || team.winner),
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (game.isUpcoming) {
            if (!narrow) Text(text = team.record, style = style(Palette.muted, 14.sp))
        } else {
            if (detailed && team.record.isNotBlank()) {
                Text(text = "${team.record}  ", style = style(Palette.dim, 13.sp))
            }
            Text(
                text = team.score,
                style = style(if (lost) Palette.dim else Palette.text, nameSize, bold = true),
            )
        }
    }
}

@Composable
private fun Status(game: Game, narrow: Boolean) {
    Column(
        modifier = GlanceModifier.width(if (narrow) 56.dp else 96.dp).padding(start = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        when {
            game.isLive -> {
                Text(
                    text = game.detail.ifBlank { "Live" },
                    style = style(Palette.accent, if (narrow) 12.sp else 15.sp, bold = true, align = TextAlign.End),
                    maxLines = 2,
                )
                if (!narrow) Text(text = "LIVE", style = style(Palette.accent, 12.sp, align = TextAlign.End))
            }
            game.isFinal -> Text(
                text = game.detail.ifBlank { "Final" },
                style = style(Palette.muted, if (narrow) 12.sp else 15.sp, bold = true, align = TextAlign.End),
                maxLines = 1,
            )
            else -> {
                Text(
                    text = formatTime(game.startMillis),
                    style = style(size = if (narrow) 12.sp else 16.sp, bold = true, align = TextAlign.End),
                    maxLines = 1,
                )
                Text(
                    text = formatDay(game.startMillis, narrow),
                    style = style(Palette.muted, 13.sp, align = TextAlign.End),
                    maxLines = 1,
                )
            }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val shortDayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

internal fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFmt)

private fun formatDay(millis: Long, short: Boolean): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    if (zoned.toLocalDate() == LocalDate.now()) return "Today"
    return zoned.format(if (short) shortDayFmt else dayFmt)
}
