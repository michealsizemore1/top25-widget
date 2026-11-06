package io.github.michealsizemore1.beyondstride.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.michealsizemore1.beyondstride.Geo
import io.github.michealsizemore1.beyondstride.Trail
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Topographic map under the trail line: USGS National Map "US Topo" tiles (US government, public domain).
 * When a trail is chosen, the tiles around it are downloaded and kept on the watch, so the map works on
 * the trail with no signal.
 */
object TrailTiles {
    private const val URL_FMT = "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/%d/%d/%d"   // z / y / x
    val ZOOMS = 13..16
    private const val MARGIN_M = 350.0          // map around the trail, for wrong turns

    @Volatile var status = ""                   // "Downloading map 45%", "Map ready", ...
    @Volatile var readyFor: String? = null      // trail id with a complete map
    @Volatile private var workingOn: String? = null
    private var lastTryId: String? = null
    private var lastTry = 0L

    private fun dir(ctx: Context) = File(ctx.filesDir, "tiles").apply { mkdirs() }
    fun file(ctx: Context, z: Int, x: Int, y: Int) = File(dir(ctx), "$z/$x/$y.png")

    fun enabled(ctx: Context) = ctx.getSharedPreferences("run", 0).getBoolean("trailMapTiles", true)

    // Web Mercator tile math.
    fun tileX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)
    fun tileY(lat: Double, z: Int): Double { val r = Math.toRadians(lat); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z) }
    fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    fun latOf(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    /** Tiles within MARGIN_M of the trail, for each zoom. */
    fun tilesFor(t: Trail): List<Triple<Int, Int, Int>> {
        val out = LinkedHashSet<Triple<Int, Int, Int>>()
        // Points every ~100 m along the trail.
        val pts = ArrayList<DoubleArray>()
        for (i in 1 until t.points.size) {
            val a = t.points[i - 1]; val b = t.points[i]
            val n = maxOf(1, (Geo.dist(a, b) / 100).toInt())
            for (k in 0 until n) pts += doubleArrayOf(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
        }
        t.points.lastOrNull()?.let { pts += it }
        for (z in ZOOMS) for (p in pts) {
            val dLat = MARGIN_M / 110540.0; val dLon = MARGIN_M / (111320.0 * cos(Math.toRadians(p[0])))
            val x0 = floor(tileX(p[1] - dLon, z)).toInt(); val x1 = floor(tileX(p[1] + dLon, z)).toInt()
            val y0 = floor(tileY(p[0] + dLat, z)).toInt(); val y1 = floor(tileY(p[0] - dLat, z)).toInt()
            for (x in x0..x1) for (y in y0..y1) out += Triple(z, x, y)
        }
        return out.toList()
    }

    /** Download the map for this trail (skips tiles already saved). Safe to call often. */
    /** Is the whole map for this trail saved? */
    fun isSaved(ctx: Context, t: Trail) = readyFor == t.id || tilesFor(t).all { (z, x, y) -> file(ctx, z, x, y).exists() }

    fun ensure(ctx: Context, t: Trail, force: Boolean = false) {
        if (!enabled(ctx) || readyFor == t.id || workingOn == t.id) return
        if (!force && lastTryId == t.id && System.currentTimeMillis() - lastTry < 60_000) return
        lastTryId = t.id; lastTry = System.currentTimeMillis()
        val app = ctx.applicationContext
        val tiles = tilesFor(t)
        if (tiles.all { (z, x, y) -> file(app, z, x, y).exists() }) { readyFor = t.id; status = "Map ready"; return }
        workingOn = t.id
        Thread {
            var done = 0; var failed = 0
            for ((z, x, y) in tiles) {
                if (workingOn != t.id) return@Thread
                val f = file(app, z, x, y)
                if (!f.exists()) {
                    try {
                        val c = (URL(String.format(java.util.Locale.US, URL_FMT, z, y, x)).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 10_000; readTimeout = 15_000
                            setRequestProperty("User-Agent", "BeyondStride/1.0 (personal Wear OS running app)")
                        }
                        if (c.responseCode == 200) {
                            val bytes = c.inputStream.use { it.readBytes() }
                            f.parentFile?.mkdirs(); f.writeBytes(bytes)
                        } else failed++
                        c.disconnect()
                    } catch (e: Exception) { failed++ }
                }
                done++
                status = "Downloading map ${done * 100 / tiles.size}%"
            }
            workingOn = null
            if (failed == 0) { readyFor = t.id; status = "Map ready" }
            else status = if (failed == tiles.size) "Map not downloaded (no connection)" else "Map partly downloaded"
            trim(app)
        }.start()
    }

    /** Keep the saved map under ~80 MB: drop the oldest tiles. */
    private fun trim(ctx: Context) {
        val all = dir(ctx).walkTopDown().filter { it.isFile }.toList()
        var total = all.sumOf { it.length() }
        if (total < 80L * 1024 * 1024) return
        for (f in all.sortedBy { it.lastModified() }) { total -= f.length(); f.delete(); if (total < 60L * 1024 * 1024) break }
    }

    private val mem = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A saved tile, or null. */
    fun bitmap(ctx: Context, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        val f = file(ctx, z, x, y)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.path)?.also { mem.put(key, it) } } catch (e: Exception) { null }
    }

    /** Best zoom for this many meters per screen pixel (among saved zooms). */
    fun zoomFor(metersPerPx: Double, lat: Double): Int {
        // Ground meters per tile pixel at zoom z: 156543 * cos(lat) / 2^z. Pick the coarsest that's at least as sharp as the screen.
        for (z in ZOOMS) { if (156543.03 * cos(Math.toRadians(lat)) / (1 shl z) <= metersPerPx * 1.6) return z }
        return ZOOMS.last
    }
}
