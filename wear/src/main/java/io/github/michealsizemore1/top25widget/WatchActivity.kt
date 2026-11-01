package io.github.michealsizemore1.top25widget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.wear.tiles.TileService

/**
 * The watch app: the Top 25 list, or one game's details when opened from a tile,
 * the complication, or the list. Refreshes every 30 seconds while a game is live.
 */
class WatchActivity : Activity() {

    companion object {
        const val EXTRA_GAME = "game"
        private const val LIVE_REFRESH_MS = 30_000L
    }

    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var gameId: String? = null
    private var visible = false
    private var anyLive = false

    private val refresh = Runnable { if (visible) load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = resources.displayMetrics.widthPixels
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Round screen: generous side padding, extra at top and bottom.
            setPadding(w / 9, w / 7, w / 9, w / 4)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            addView(content)
            requestFocus()   // rotating bezel scrolls
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); visible = true; load() }
    override fun onPause() { super.onPause(); visible = false; handler.removeCallbacks(refresh) }

    private fun handleIntent(i: Intent) {
        gameId = i.getStringExtra(EXTRA_GAME)
        content.removeAllViews()
        content.addView(label("Loading…", 0xFF9AA3A0.toInt(), 14f))
    }

    private fun load() {
        handler.removeCallbacks(refresh)
        Thread {
            val games = try { WatchFeed.games(maxAgeMs = 15_000) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                content.removeAllViews()
                when {
                    games == null -> content.addView(label("Can't reach scores.\nTap to retry.", Color.WHITE, 14f).apply {
                        setOnClickListener { load() }
                    })
                    gameId != null -> showGame(games.firstOrNull { it.id == gameId }, games)
                    else -> showList(games)
                }
                anyLive = games?.any { it.isLive } == true
                if (visible && anyLive) handler.postDelayed(refresh, LIVE_REFRESH_MS)
                // Keep the tiles and watch face in step with what's on screen.
                TileService.getUpdater(this).requestUpdate(Top25TileService::class.java)
                TileService.getUpdater(this).requestUpdate(OsuTileService::class.java)
                OsuComplicationService.requestUpdate(this)
            }
        }.start()
    }

    // ------------------------------------------------------------------ list

    private fun showList(games: List<Game>) {
        content.addView(label("TOP 25", 0xFFD4E635.toInt(), 13f, bold = true).apply { letterSpacing = 0.1f })
        if (games.isEmpty()) {
            content.addView(label("No Top 25 games today", 0xFF9AA3A0.toInt(), 14f).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        games.forEach { g ->
            content.addView(card().apply {
                addView(teamRow(g, g.away))
                addView(teamRow(g, g.home))
                addView(label(WatchFeed.status(g) + if (g.network.isNotBlank() && g.isUpcoming) " · ${g.network}" else "",
                    if (g.isLive) 0xFFD4E635.toInt() else 0xFF9AA3A0.toInt(), 12f, bold = g.isLive).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
                })
                setOnClickListener {
                    gameId = g.id
                    content.removeAllViews()
                    showGame(g, games)
                }
            })
        }
    }

    private fun teamRow(g: Game, t: TeamLine) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lost = g.isFinal && !t.winner
        val color = if (lost) 0xFF6B7370.toInt() else Color.WHITE
        val hasBall = g.live?.possessionId?.let { it.isNotBlank() && it == t.id } == true
        addView(label((if (hasBall) "🏈 " else "") + WatchFeed.short(t), color, 15f, bold = t.rank != null && !lost).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            gravity = Gravity.START
        })
        addView(label(if (g.isUpcoming) t.record else t.score, color, 15f, bold = !g.isUpcoming).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        })
    }

    // ------------------------------------------------------------------ one game

    private fun showGame(g: Game?, all: List<Game>) {
        if (g == null) {
            content.addView(label("Game not found", Color.WHITE, 14f))
            content.addView(label("‹ All games", 0xFF9AA3A0.toInt(), 13f).apply {
                setPadding(0, dp(10), 0, 0); setOnClickListener { gameId = null; content.removeAllViews(); showList(all) }
            })
            return
        }
        val live = g.live
        content.addView(label(WatchFeed.status(g), if (g.isLive) 0xFFD4E635.toInt() else 0xFF9AA3A0.toInt(), 14f, bold = true))
        content.addView(card().apply { addView(teamRow(g, g.away)); addView(teamRow(g, g.home)) })

        if (live != null) {
            val spot = live.possessionText.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
            if (live.downText.isNotBlank()) content.addView(label(live.downText + spot, Color.WHITE, 16f, bold = true).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); setPadding(0, dp(8), 0, dp(2))
            })
            if (live.drive.isNotBlank()) content.addView(label(live.drive, 0xFF9AA3A0.toInt(), 12f))
            if (live.lastPlay.isNotBlank()) content.addView(label(live.lastPlay, 0xFFDDDDDD.toInt(), 13f).apply { setPadding(0, dp(6), 0, 0) })
            live.athletes.firstOrNull()?.let { a ->
                content.addView(label("▸ ${a.name}" + (a.position.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    Color.WHITE, 12f, bold = true).apply { setPadding(0, dp(4), 0, 0) })
            }

            // Score by quarter
            val periods = maxOf(live.awayLines.size, live.homeLines.size)
            if (periods > 0) {
                content.addView(card().apply {
                    fun row(name: String, cells: List<String>, total: String, header: Boolean) = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val c = if (header) 0xFF6B7370.toInt() else Color.WHITE
                        addView(label(name, c, 12f, bold = !header).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f); gravity = Gravity.START })
                        cells.forEach { addView(label(it, c, 12f).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }) }
                        addView(label(total, if (header) c else 0xFFD4E635.toInt(), 12f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                    }
                    addView(row("", (0 until periods).map { if (it < 4) "${it + 1}" else "OT" }, "T", true))
                    addView(row(g.away.abbr, (0 until periods).map { live.awayLines.getOrElse(it) { "-" } }, g.away.score, false))
                    addView(row(g.home.abbr, (0 until periods).map { live.homeLines.getOrElse(it) { "-" } }, g.home.score, false))
                })
            }
            live.homeWinPct?.let { hwp ->
                content.addView(label("Win chance: ${g.away.abbr} ${WatchFeed.pct(1 - hwp)} · ${g.home.abbr} ${WatchFeed.pct(hwp)}",
                    0xFF9AA3A0.toInt(), 11f).apply { setPadding(0, dp(6), 0, 0) })
            }
        }
        if (g.network.isNotBlank()) content.addView(label("TV: ${g.network}", 0xFF6B7370.toInt(), 11f).apply { setPadding(0, dp(4), 0, 0) })

        content.addView(label("‹ All games", 0xFF9AA3A0.toInt(), 13f, bold = true).apply {
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { gameId = null; content.removeAllViews(); showList(all) }
        })
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { setColor(0xFF1C2421.toInt()); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        isClickable = true
    }
}
