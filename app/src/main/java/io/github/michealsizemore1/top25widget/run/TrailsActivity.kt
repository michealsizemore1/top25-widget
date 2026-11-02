package io.github.michealsizemore1.top25widget.run

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.michealsizemore1.top25widget.Trail
import io.github.michealsizemore1.top25widget.TrailSync
import io.github.michealsizemore1.top25widget.Trails
import org.json.JSONArray
import java.util.Locale

/** Trails near you (same list as the watch). Choosing one sends it to the watch, ready to start. */
class TrailsActivity : Activity() {

    private var trails: List<Trail>? = null
    private var status = "Finding your location…"
    private var open: Trail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        else locate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
        else { status = "Allow location to find trails near you"; render() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (open != null) { open = null; render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @SuppressLint("MissingPermission")
    private fun locate() {
        val lm = getSystemService(LocationManager::class.java)
        val recent = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }.maxByOrNull { it.time }
        if (recent != null) { search(recent); return }
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
            if (loc != null) search(loc) else { status = "Couldn't get your location"; render() }
        } else {
            lm.requestLocationUpdates(provider, 0L, 0f, object : LocationListener {
                override fun onLocationChanged(l: Location) { lm.removeUpdates(this); search(l) }
            }, mainLooper)
        }
    }

    private fun search(loc: Location) {
        status = "Looking for trails…"; render()
        Thread {
            val msg = try { trails = Trails.fetchNearby(loc.latitude, loc.longitude); "" } catch (e: Exception) { "Couldn't get trails: ${e.message}" }
            runOnUiThread { status = msg; render() }
        }.start()
    }

    private fun distText(m: Double) = if (m < 160) "${(m * 3.28084).toInt()} ft" else String.format(Locale.US, "%.1f mi", m / 1609.344)

    private fun render() {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(40)) }
        val t = open
        if (t != null) renderTrail(col, t) else {
            col.addView(RunUi.text(this, "Trails near me", Color.WHITE, 28f, true))
            col.addView(RunUi.text(this, "From OpenStreetMap. Pick one to send it to your watch — it's ready when you press Start. " +
                "You can also find and pick trails right on the watch.", RunUi.MUTED, 13f).apply { setPadding(dp(4), dp(8), dp(4), 0) })
            TrailSync.current(this)?.let { (cur, oab) ->
                col.addView(RunUi.card(this).apply {
                    addView(RunUi.text(ctx, "ON YOUR WATCH", RunUi.MUTED, 11f, true))
                    addView(RunUi.text(ctx, cur.name, Color.WHITE, 17f, true))
                    addView(RunUi.text(ctx, String.format(Locale.US, "%.2f mi %s", cur.lengthM / 1609.344 * (if (oab) 2 else 1),
                        if (oab) "out & back" else if (cur.loop) "loop" else "one way"), 0xFF46C46E.toInt(), 13f))
                    addView(RunUi.text(ctx, "Clear", 0xFFE5484D.toInt(), 13f, true).apply {
                        setPadding(0, dp(8), 0, 0)
                        setOnClickListener { TrailSync.choose(ctx, null, false, "phone"); render() }
                    })
                })
            }
            val list = trails
            if (list == null || status.isNotBlank()) col.addView(RunUi.text(this, status, RunUi.MUTED, 14f).apply { setPadding(dp(4), dp(16), 0, 0) })
            list?.forEach { tr ->
                col.addView(RunUi.card(this).apply {
                    addView(RunUi.text(ctx, tr.name, Color.WHITE, 17f, true))
                    addView(RunUi.text(ctx, String.format(Locale.US, "%.1f mi %s · %s away%s", tr.lengthM / 1609.344,
                        if (tr.loop) "loop" else "one way", distText(tr.distToStartM),
                        if (tr.surface.isNotBlank()) " · ${tr.surface}" else ""), RunUi.MUTED, 13f))
                    setOnClickListener { open = tr; render() }
                })
            }
            if (list != null) col.addView(RunUi.button(this, "Search again", RunUi.CARD, Color.WHITE) {
                trails = null; status = "Finding your location…"; render(); locate()
            }.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) } })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(RunUi.BG); addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
    }

    private fun renderTrail(col: LinearLayout, t: Trail) {
        val ctx = this
        fun dp(v: Int) = RunUi.dp(ctx, v)
        val mi = t.lengthM / 1609.344
        col.addView(RunUi.text(this, "‹ Trails", RunUi.MUTED, 14f, true).apply { setOnClickListener { open = null; render() } })
        col.addView(RunUi.text(this, t.name, Color.WHITE, 26f, true).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(RunUi.card(this).apply {
            addView(RunUi.statGrid(ctx, listOfNotNull(
                Triple(String.format(Locale.US, "%.2f", mi), if (t.loop) "mi loop" else "mi one way", RunUi.ACCENT),
                t.elevations?.let { Triple("${(t.climbM * 3.28084).toInt()}", "ft climb", Color.WHITE) },
                Triple(t.difficulty, "difficulty", Color.WHITE),
                Triple(distText(t.distToStartM), "away", Color.WHITE),
                t.surface.takeIf { it.isNotBlank() }?.let { Triple(it, "surface", Color.WHITE) },
            )))
        })
        // Elevation profile.
        col.addView(RunUi.section(this, "ELEVATION"))
        val e = t.elevations
        if (e == null) {
            col.addView(RunUi.text(this, "Loading…", RunUi.DIM, 13f))
            Thread {
                val ok = try { Trails.fetchElevation(t); true } catch (ex: Exception) { false }
                runOnUiThread { if (open === t) { if (ok) render() else Toast.makeText(ctx, "Elevation unavailable", Toast.LENGTH_SHORT).show() } }
            }.start()
        } else col.addView(RunUi.card(this).apply { addView(ImageView(ctx).apply { setImageBitmap(profile(e)); adjustViewBounds = true }) })
        // Route outline.
        col.addView(RunUi.section(this, "ROUTE"))
        col.addView(RunUi.card(this).apply {
            addView(RunUi.route(ctx, JSONArray(t.points.map { JSONArray(listOf(it[0], it[1])) })))
        })
        // Send to the watch.
        fun send(oab: Boolean) {
            TrailSync.choose(ctx, t, oab, "phone")
            Toast.makeText(ctx, "Sent to your watch — press Start there", Toast.LENGTH_LONG).show()
            open = null; render()
        }
        if (t.loop) col.addView(RunUi.button(this, String.format(Locale.US, "Run this loop (%.2f mi) — send to watch", mi), RunUi.ACCENT) { send(false) }
            .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) } })
        else {
            col.addView(RunUi.button(this, String.format(Locale.US, "Out & back (%.2f mi) — send to watch", mi * 2), RunUi.ACCENT) { send(true) }
                .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) } })
            col.addView(RunUi.button(this, String.format(Locale.US, "One way (%.2f mi) — send to watch", mi), RunUi.CARD, Color.WHITE) { send(false) }
                .apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) } })
        }
        col.addView(RunUi.text(this, "Open in Maps for directions to the trailhead ›", RunUi.ACCENT, 14f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
            setOnClickListener {
                val s = t.points.first()
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("geo:${s[0]},${s[1]}?q=${s[0]},${s[1]}(${android.net.Uri.encode(t.name)})")))
            }
        })
    }

    private fun profile(e: DoubleArray): Bitmap {
        val w = resources.displayMetrics.widthPixels - RunUi.dp(this, 64); val h = RunUi.dp(this, 120)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val lo = e.min(); val hi = e.max().coerceAtLeast(lo + 10)
        fun y(v: Double) = (h - 6 - (v - lo) / (hi - lo) * (h - 24)).toFloat()
        val fillPath = Path().apply { moveTo(0f, h.toFloat()); e.forEachIndexed { i, v -> lineTo(w * i / (e.size - 1f), y(v)) }; lineTo(w.toFloat(), h.toFloat()); close() }
        c.drawPath(fillPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5546C46E })
        c.drawPath(Path().apply { e.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(w * i / (e.size - 1f), y(v)) } },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = RunUi.dp(this@TrailsActivity, 2).toFloat(); color = 0xFF46C46E.toInt() })
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RunUi.MUTED; textSize = RunUi.dp(this@TrailsActivity, 11).toFloat() }
        c.drawText("${(hi * 3.28084).toInt()} ft", 4f, txt.textSize + 2, txt)
        c.drawText("${(lo * 3.28084).toInt()} ft", 4f, h - 8f, txt)
        return bmp
    }
}
