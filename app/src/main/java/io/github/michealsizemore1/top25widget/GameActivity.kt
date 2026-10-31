package io.github.michealsizemore1.top25widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Opened when you tap a game in the widget. Shows live game details from ESPN. */
class GameActivity : Activity() {

    companion object {
        const val EXTRA_ID = "game_id"
        const val EXTRA_LEAGUE = "league"
        private const val LIVE_REFRESH_MS = 30_000L

        fun intent(ctx: Context, league: League, gameId: String): Intent =
            Intent(ctx, GameActivity::class.java)
                .setData(Uri.parse("top25://game/${league.key}/$gameId"))   // keeps each game's tap distinct
                .putExtra(EXTRA_ID, gameId)
                .putExtra(EXTRA_LEAGUE, league.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private object C {
        val bg = Color.parseColor("#121A17")
        val card = Color.parseColor("#0D0D0D")
        val text = Color.WHITE
        val muted = Color.parseColor("#9AA3A0")
        val dim = Color.parseColor("#6B7370")
        val accent = Color.parseColor("#D4E635")
        val line = Color.parseColor("#22302B")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var gameId = ""
    private var league = League.CFB
    private var isLive = false
    private var visible = false

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (visible && isLive) load(quiet = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(40), dp(14), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.bg)
            addView(content)
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); visible = true; if (isLive) load(quiet = true) }
    override fun onPause() { super.onPause(); visible = false; handler.removeCallbacks(autoRefresh) }

    private fun handleIntent(i: Intent) {
        gameId = i.getStringExtra(EXTRA_ID) ?: i.data?.lastPathSegment.orEmpty()
        league = League.from(i.getStringExtra(EXTRA_LEAGUE))
        content.removeAllViews()
        content.addView(label("Loading game…", C.muted, 16f))
        load(quiet = false)
    }

    private fun load(quiet: Boolean) {
        handler.removeCallbacks(autoRefresh)
        val id = gameId
        Thread {
            val result = try {
                JSONObject(String(Http.get(league.summaryUrl + id)))
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || id != gameId) return@runOnUiThread
                if (result == null) {
                    if (!quiet) {
                        content.removeAllViews()
                        content.addView(label("Couldn't load this game. Check your connection.", C.muted, 16f))
                        content.addView(button("Try again") { handleIntent(intent) })
                    }
                } else {
                    render(result)
                }
                if (isLive && visible) handler.postDelayed(autoRefresh, LIVE_REFRESH_MS)
            }
        }.start()
    }

    // ------------------------------------------------------------------ rendering

    private fun render(j: JSONObject) {
        content.removeAllViews()
        val comp = j.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: JSONObject()
        val type = comp.optJSONObject("status")?.optJSONObject("type") ?: JSONObject()
        val state = type.optString("state", "pre")
        isLive = state == "in"

        val competitors = comp.optJSONArray("competitors") ?: JSONArray()
        val away = (0 until competitors.length()).map { competitors.getJSONObject(it) }
            .firstOrNull { it.optString("homeAway") == "away" } ?: JSONObject()
        val home = (0 until competitors.length()).map { competitors.getJSONObject(it) }
            .firstOrNull { it.optString("homeAway") == "home" } ?: JSONObject()

        renderScoreHeader(away, home, state, type)
        renderLinescore(away, home)
        renderSituation(j, state)
        renderScoringPlays(j)
        renderTeamStats(j, away, home)
        renderLeaders(j)
        renderGameInfo(j, comp)

        content.addView(button("Refresh") { load(quiet = true) })
        content.addView(button("Open on ESPN") {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(league.espnGameUrl(gameId))))
        })
        content.addView(button("Open ${league.shortTitle} app") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(league.siteUrl)))
        })
    }

    private fun renderScoreHeader(away: JSONObject, home: JSONObject, state: String, type: JSONObject) {
        val box = card()
        val statusText = when (state) {
            "in" -> type.optString("shortDetail", "Live")
            "post" -> type.optString("shortDetail", "Final")
            else -> type.optString("shortDetail", "")
        }
        box.addView(label(statusText, if (state == "in") C.accent else C.muted, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })
        box.addView(space(10))
        box.addView(teamScoreRow(away, state))
        box.addView(space(8))
        box.addView(teamScoreRow(home, state))
        content.addView(box)
    }

    private fun teamScoreRow(c: JSONObject, state: String): View {
        val team = c.optJSONObject("team") ?: JSONObject()
        val rank = c.optInt("rank", 99).takeIf { it in 1..25 }
            ?: c.optJSONObject("curatedRank")?.optInt("current", 99)?.takeIf { it in 1..25 }
        val record = c.optJSONArray("record")?.optJSONObject(0)?.optString("summary")
            ?: c.optJSONArray("records")?.optJSONObject(0)?.optString("summary").orEmpty()
        val lost = state == "post" && !c.optBoolean("winner", false)

        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            val logo = Logos.bitmap(this@GameActivity, league.logoKey(team.optString("id")))
            addView(ImageView(context).apply {
                if (logo != null) setImageBitmap(logo)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(label(
                    (rank?.let { "$it  " } ?: "") +
                        if (league.usesNicknames) team.optString("displayName")
                        else team.optString("location", team.optString("displayName")),
                    if (lost) C.dim else C.text, 20f, bold = !lost,
                ))
                if (record.isNotBlank()) addView(label(record, C.muted, 14f))
            })
            if (state != "pre") {
                addView(label(c.optString("score", ""), if (lost) C.dim else C.text, 30f, bold = true))
            }
        }
    }

    private fun renderLinescore(away: JSONObject, home: JSONObject) {
        val a = away.optJSONArray("linescores") ?: return
        val h = home.optJSONArray("linescores") ?: return
        if (a.length() == 0) return
        val periods = maxOf(a.length(), h.length())
        val box = section(if (league == League.NHL) "Score by period" else "Score by quarter")
        val reg = league.regulationPeriods

        fun line(name: String, arr: JSONArray?, total: String, header: Boolean) = row().apply {
            addView(cell(name, 1.6f, if (header) C.muted else C.text, Gravity.START, bold = !header))
            for (p in 0 until periods) {
                val v = if (header) when {
                    p < reg -> "${p + 1}"
                    league == League.NHL && p == reg -> "OT"
                    league == League.NHL -> "SO"
                    p == reg -> "OT"
                    else -> "${p - reg + 1}OT"
                }
                else arr?.optJSONObject(p)?.optString("displayValue", "-") ?: "-"
                addView(cell(v, 1f, if (header) C.muted else C.text, Gravity.CENTER))
            }
            addView(cell(total, 1f, if (header) C.muted else C.text, Gravity.CENTER, bold = true))
        }

        box.addView(line("", null, "T", header = true))
        box.addView(line(abbr(away), a, away.optString("score", "-"), header = false))
        box.addView(line(abbr(home), h, home.optString("score", "-"), header = false))
    }

    /** Down & distance / last play, when ESPN provides it during a live game. */
    private fun renderSituation(j: JSONObject, state: String) {
        if (state != "in") return
        val sit = j.optJSONObject("situation") ?: return
        val down = sit.optString("downDistanceText", sit.optString("shortDownDistanceText", ""))
        val last = sit.optJSONObject("lastPlay")?.optString("text").orEmpty()
        if (down.isBlank() && last.isBlank()) return
        val box = section("Now")
        if (down.isNotBlank()) box.addView(label(down, C.accent, 16f, bold = true))
        if (last.isNotBlank()) box.addView(label("Last play: $last", C.text, 15f))
    }

    private fun renderScoringPlays(j: JSONObject) {
        val plays = j.optJSONArray("scoringPlays") ?: return
        if (plays.length() == 0) return
        val box = section("Scoring plays")
        for (i in 0 until plays.length()) {
            val p = plays.getJSONObject(i)
            val q = p.optJSONObject("period")?.optInt("number") ?: 0
            val clock = p.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val team = p.optJSONObject("team")?.optString("abbreviation").orEmpty()
            val score = "${p.optString("awayScore")}-${p.optString("homeScore")}"
            box.addView(row().apply {
                addView(label("Q$q $clock", C.muted, 13f).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(74), WRAP_CONTENT)
                })
                addView(label("$team  ${p.optString("text")}", C.text, 14f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                addView(label(score, C.text, 14f, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
            })
            if (i < plays.length() - 1) box.addView(divider())
        }
    }

    private fun renderTeamStats(j: JSONObject, away: JSONObject, home: JSONObject) {
        val teams = j.optJSONObject("boxscore")?.optJSONArray("teams") ?: return
        if (teams.length() < 2) return
        val awayId = away.optJSONObject("team")?.optString("id")
        val list = (0 until teams.length()).map { teams.getJSONObject(it) }
        val a = list.firstOrNull { it.optJSONObject("team")?.optString("id") == awayId } ?: list[0]
        val h = list.firstOrNull { it !== a } ?: list[1]
        val aStats = a.optJSONArray("statistics") ?: return
        val hStats = h.optJSONArray("statistics") ?: return
        if (aStats.length() == 0) return

        val hByName = (0 until hStats.length()).associate {
            val s = hStats.getJSONObject(it); s.optString("name") to s.optString("displayValue")
        }
        val box = section("Team stats")
        box.addView(row().apply {
            addView(cell(abbr(away), 1f, C.muted, Gravity.START, bold = true))
            addView(cell("", 1.6f, C.muted, Gravity.CENTER))
            addView(cell(abbr(home), 1f, C.muted, Gravity.END, bold = true))
        })
        for (i in 0 until aStats.length()) {
            val s = aStats.getJSONObject(i)
            box.addView(row().apply {
                setPadding(0, dp(4), 0, dp(4))
                addView(cell(s.optString("displayValue"), 1f, C.text, Gravity.START))
                addView(cell(s.optString("label"), 1.6f, C.muted, Gravity.CENTER))
                addView(cell(hByName[s.optString("name")] ?: "-", 1f, C.text, Gravity.END))
            })
        }
    }

    private fun renderLeaders(j: JSONObject) {
        val teams = j.optJSONArray("leaders") ?: return
        if (teams.length() == 0) return
        val box = section("Game leaders")
        for (t in 0 until teams.length()) {
            val team = teams.getJSONObject(t)
            val cats = team.optJSONArray("leaders") ?: continue
            if (cats.length() == 0) continue
            box.addView(label(team.optJSONObject("team")?.optString("displayName").orEmpty(), C.accent, 14f, bold = true)
                .apply { setPadding(0, if (t > 0) dp(10) else 0, 0, dp(4)) })
            for (c in 0 until cats.length()) {
                val cat = cats.getJSONObject(c)
                val top = cat.optJSONArray("leaders")?.optJSONObject(0) ?: continue
                val name = top.optJSONObject("athlete")?.optString("displayName").orEmpty()
                box.addView(row().apply {
                    setPadding(0, dp(3), 0, dp(3))
                    addView(label(cat.optString("displayName"), C.muted, 13f).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(110), WRAP_CONTENT)
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        addView(label(name, C.text, 15f, bold = true))
                        addView(label(top.optString("displayValue"), C.muted, 13f))
                    })
                })
            }
        }
    }

    private fun renderGameInfo(j: JSONObject, comp: JSONObject) {
        val lines = mutableListOf<String>()
        val venue = j.optJSONObject("gameInfo")?.optJSONObject("venue")
        venue?.let {
            val addr = it.optJSONObject("address")
            val place = listOfNotNull(addr?.optString("city")?.takeIf(String::isNotBlank),
                addr?.optString("state")?.takeIf(String::isNotBlank)).joinToString(", ")
            lines += "Stadium: ${it.optString("fullName")}" + if (place.isNotBlank()) " ($place)" else ""
        }
        j.optJSONObject("gameInfo")?.optInt("attendance", 0)?.takeIf { it > 0 }?.let {
            lines += "Attendance: %,d".format(it)
        }
        comp.optJSONArray("broadcasts")?.optJSONObject(0)?.optJSONObject("media")
            ?.optString("shortName")?.takeIf(String::isNotBlank)?.let { lines += "TV: $it" }
        j.optJSONArray("pickcenter")?.optJSONObject(0)?.let { odds ->
            odds.optString("details").takeIf(String::isNotBlank)?.let { lines += "Line: $it" }
            odds.optDouble("overUnder").takeIf { !it.isNaN() }?.let { lines += "Over/under: $it" }
        }
        if (lines.isEmpty()) return
        val box = section("Game info")
        lines.forEach { box.addView(label(it, C.text, 14f).apply { setPadding(0, dp(3), 0, dp(3)) }) }
    }

    // ------------------------------------------------------------------ view helpers

    private fun abbr(c: JSONObject) = c.optJSONObject("team")?.optString("abbreviation").orEmpty()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply { setColor(C.card); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun section(title: String): LinearLayout {
        val box = card()
        box.addView(label(title.uppercase(), C.muted, 12f, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(box)
        return box
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun label(text: String, color: Int, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cell(text: String, weight: Float, color: Int, gravity: Int, bold: Boolean = false) =
        label(text, color, 15f, bold).apply {
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(C.line)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
    }
}
