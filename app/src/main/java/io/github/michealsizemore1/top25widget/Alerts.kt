package io.github.michealsizemore1.top25widget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Phone notifications for the Top 25 widget, checked on every refresh
 * (every ~2 minutes while games are live):
 *
 *  - Upset alerts: a ranked team trailing an unranked or lower-ranked opponent in the
 *    4th quarter/OT ("Upset watch"), and the final if the upset happens ("UPSET").
 *  - Ohio State alerts: kickoff, every score (TD, FG, safety), halftime, and final.
 *
 * Each alert fires once per game; the "fired" set remembers what was already sent.
 */
object Alerts {
    private const val PREFS = "alerts"
    private const val CH_UPSETS = "upsets"
    private const val CH_TEAM = "ohio_state"
    private const val OSU_ID = "194"   // ESPN's team id for Ohio State

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun upsetsOn(ctx: Context) = prefs(ctx).getBoolean("upsets_on", true)
    fun osuOn(ctx: Context) = prefs(ctx).getBoolean("osu_on", true)
    fun setUpsetsOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("upsets_on", on).apply()
    fun setOsuOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("osu_on", on).apply()

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_UPSETS, "Upset alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ranked teams in trouble late, and upset finals"
        })
        nm.createNotificationChannel(NotificationChannel(CH_TEAM, "Ohio State alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ohio State kickoff, scores, halftime, and final"
        })
    }

    // ------------------------------------------------------------------ checking

    @Synchronized
    fun check(ctx: Context, games: List<Game>) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val p = prefs(ctx)
        val fired = p.getStringSet("fired", emptySet())!!.toMutableSet()
        val edit = p.edit()

        for (g in games) {
            if (upsetsOn(ctx)) checkUpset(ctx, g, fired)
            if (osuOn(ctx) && isOsu(g)) checkOsu(ctx, g, fired, p, edit)
        }

        // Only remember games still on today's scoreboard, so the set stays small.
        val ids = games.map { it.id }.toSet()
        fired.retainAll { key -> ids.any { key.endsWith(":$it") } }
        edit.putStringSet("fired", fired).commit()
    }

    private fun isOsu(g: Game) = g.away.id == OSU_ID || g.home.id == OSU_ID ||
        g.away.name == "Ohio State" || g.home.name == "Ohio State"

    private fun label(t: TeamLine) = (t.rank?.let { "#$it " } ?: "") + t.name
    private fun score(t: TeamLine) = t.score.toIntOrNull() ?: 0
    private fun isLate(g: Game) = g.isLive && (g.detail.contains("4th") || g.detail.contains("OT"))

    /** The better-ranked team (the favorite) and its opponent, if this matchup could be an upset. */
    private fun favorite(g: Game): Pair<TeamLine, TeamLine>? {
        val a = g.away; val h = g.home
        return when {
            a.rank != null && (h.rank == null || h.rank > a.rank) -> a to h
            h.rank != null && (a.rank == null || a.rank > h.rank) -> h to a
            else -> null
        }
    }

    private fun checkUpset(ctx: Context, g: Game, fired: MutableSet<String>) {
        val (fav, dog) = favorite(g) ?: return
        val trailing = score(fav) < score(dog)

        if (isLate(g) && trailing && fired.add("watch:${g.id}")) {
            notify(ctx, CH_UPSETS, g, "watch",
                "⚠️ Upset watch: ${label(fav)} trails",
                "${label(dog)} ${dog.score}, ${label(fav)} ${fav.score} · ${g.detail}")
        }
        if (g.isFinal && trailing && fired.add("upset:${g.id}")) {
            notify(ctx, CH_UPSETS, g, "upset",
                "🚨 UPSET: ${label(dog)} beats ${label(fav)}",
                "Final: ${label(dog)} ${dog.score}, ${label(fav)} ${fav.score}")
        }
    }

    private fun checkOsu(
        ctx: Context, g: Game, fired: MutableSet<String>,
        p: android.content.SharedPreferences, edit: android.content.SharedPreferences.Editor,
    ) {
        val osu = if (g.away.id == OSU_ID || g.away.name == "Ohio State") g.away else g.home
        val opp = if (osu === g.away) g.home else g.away
        val line = "${label(osu)} ${osu.score}, ${label(opp)} ${opp.score}"

        if (g.isLive && fired.add("kick:${g.id}")) {
            notify(ctx, CH_TEAM, g, "kick", "🏈 Ohio State is underway",
                "vs ${label(opp)}" + if (g.network.isNotBlank()) " · ${g.network}" else "")
        }

        // Scores: compare with the last score we saw for this game.
        if (g.isLive || g.isFinal) {
            val key = "osu_score_${g.id}"
            val now = "${score(osu)}-${score(opp)}"
            val before = p.getString(key, null)
            if (before != null && before != now) {
                val (ob, pb) = before.split("-").map { it.toIntOrNull() ?: 0 }
                val osuGain = score(osu) - ob
                val oppGain = score(opp) - pb
                val (who, gain) = if (osuGain > 0) osu to osuGain else opp to oppGain
                val play = when (gain) {
                    in 6..8 -> "Touchdown"
                    3 -> "Field goal"
                    2 -> "Safety / 2-pt"
                    else -> null   // a lone extra point: just update the score silently
                }
                if (play != null && gain > 0) {
                    val icon = if (who === osu) "🌰" else "😬"
                    notify(ctx, CH_TEAM, g, "score", "$icon $play, ${who.name}", "$line · ${g.detail}")
                }
            }
            edit.putString(key, now)
        }

        if (g.isLive && g.detail.equals("Halftime", true) && fired.add("half:${g.id}")) {
            notify(ctx, CH_TEAM, g, "half", "Halftime: Ohio State ${if (score(osu) >= score(opp)) "leads" else "trails"}", line)
        }

        if (g.isFinal && fired.add("final:${g.id}")) {
            val won = score(osu) > score(opp)
            notify(ctx, CH_TEAM, g, "final",
                if (won) "✅ FINAL: Ohio State wins" else "❌ FINAL: Ohio State loses",
                "${g.detail}: $line")
        }
    }

    // ------------------------------------------------------------------ posting

    private fun notify(ctx: Context, channel: String, g: Game, kind: String, title: String, text: String) {
        val tap = PendingIntent.getActivity(
            ctx, g.id.hashCode(), GameActivity.intent(ctx, League.CFB, g.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        // Ohio State score updates replace each other; everything else is its own alert.
        val id = if (kind == "score") ("osu-score" + g.id).hashCode() else ("$kind:${g.id}").hashCode()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** A sample alert so you can check notifications work. */
    fun test(ctx: Context) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_TEAM)
            .setSmallIcon(R.drawable.ic_stat_football)
            .setContentTitle("🌰 Test alert")
            .setContentText("Alerts are working. You'll hear about upsets and every Ohio State score.")
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1, n)
    }
}
