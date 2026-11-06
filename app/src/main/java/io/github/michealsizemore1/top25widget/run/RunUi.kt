package io.github.michealsizemore1.top25widget.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.michealsizemore1.top25widget.RunMath

/** Look and building blocks for the phone's Running screens. */
object RunUi {
    const val BG = 0xFF121A17.toInt()
    const val CARD = 0xFF1C2421.toInt()
    const val ACCENT = 0xFFD4E635.toInt()
    const val MUTED = 0xFF9AA3A0.toInt()
    const val DIM = 0xFF5E6663.toInt()
    const val HR = 0xFFFF6B6B.toInt()
    const val POWER = 0xFFFFB547.toInt()
    const val CC = 0xFF7B61FF.toInt()
    const val STRYD = 0xFF50555E.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, color: Int = Color.WHITE, size: Float = 15f, bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(ctx, 16).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 10) }
    }

    fun button(ctx: Context, label: String, color: Int, textColor: Int = Color.BLACK, onClick: () -> Unit) =
        text(ctx, label, textColor, 14f, true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, dp(ctx, 22).toFloat())
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    /** Section heading: lime with a short bar in front, so it stands apart from card text. */
    fun section(ctx: Context, title: String) = text(ctx, title, ACCENT, 15f, true).apply {
        letterSpacing = 0.08f; setPadding(dp(ctx, 2), dp(ctx, 22), 0, dp(ctx, 2))
        val bar = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(ctx, 2).toFloat(); setSize(dp(ctx, 4), dp(ctx, 16)) }
        bar.setBounds(0, 0, dp(ctx, 4), dp(ctx, 16))
        setCompoundDrawables(bar, null, null, null); compoundDrawablePadding = dp(ctx, 8)
    }

    /** A grid of big numbers with small labels, 3 per row. */
    fun statGrid(ctx: Context, stats: List<Triple<String, String, Int>>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        stats.chunked(3).forEach { rowStats ->
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
                rowStats.forEach { (value, label, color) ->
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(ctx, value, color, 22f, true))
                        addView(text(ctx, label, MUTED, 12f))
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
                repeat(3 - rowStats.size) { addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
    }

    /** Time-in-zone bars. */
    fun zoneBars(ctx: Context, secs: org.json.JSONArray, names: List<String>, ranges: List<String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val total = (0 until secs.length()).sumOf { secs.optInt(it) }.coerceAtLeast(1)
        for (i in 0 until minOf(5, secs.length())) {
            val sec = secs.optInt(i)
            val color = RunMath.ZONE_COLORS[i]
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
                addView(text(ctx, "Z${i + 1}", color, 13f, true), LinearLayout.LayoutParams(dp(ctx, 30), WRAP_CONTENT))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(ctx, "${names[i]}  ${ranges.getOrNull(i).orEmpty()}", MUTED, 11f))
                    addView(LinearLayout(ctx).apply {
                        addView(View(ctx).apply { background = rounded(color, dp(ctx, 4).toFloat()) },
                            LinearLayout.LayoutParams(0, dp(ctx, 10), sec.toFloat().coerceAtLeast(0.001f)))
                        addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, 10), (total - sec).toFloat().coerceAtLeast(0.001f)))
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(ctx, RunMath.clock(sec) + "\n${sec * 100 / total}%", Color.WHITE, 12f).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(dp(ctx, 64), WRAP_CONTENT))
            })
        }
    }

    /**
     * Line chart of one value over the run. [bands] colors the background by zone
     * (lower bounds), so you can see which zone each part of the run was in.
     */
    fun chart(
        ctx: Context, xs: FloatArray, ys: FloatArray, color: Int,
        invert: Boolean = false, bands: IntArray? = null, label: (Float) -> String,
    ): View {
        val w = ctx.resources.displayMetrics.widthPixels - dp(ctx, 64)
        val h = dp(ctx, 140)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pts = xs.indices.filter { ys[it] > 0 }
        if (pts.size < 2) return text(ctx, "No data", DIM, 12f)
        // Ignore the extreme 2% so a spike doesn't flatten the chart.
        val sorted = pts.map { ys[it] }.sorted()
        var lo = sorted[(sorted.size * 0.02).toInt()]; var hi = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        if (hi - lo < 1e-3) { hi += 1; lo -= 1 }
        val pad = (hi - lo) * 0.1f; lo -= pad; hi += pad
        val left = dp(ctx, 40).toFloat(); val top = dp(ctx, 6).toFloat(); val bottom = h - dp(ctx, 6).toFloat()
        val xMax = xs.last().coerceAtLeast(1f)
        fun px(x: Float) = left + (w - left) * x / xMax
        fun py(y: Float): Float { val f = ((y - lo) / (hi - lo)).coerceIn(0f, 1f); return if (invert) top + (bottom - top) * f else bottom - (bottom - top) * f }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        bands?.forEachIndexed { i, b ->
            val next = bands.getOrNull(i + 1)?.toFloat() ?: Float.MAX_VALUE
            val y1 = py(b.toFloat()); val y2 = py(next)
            paint.color = RunMath.ZONE_COLORS[i]; paint.alpha = 40
            c.drawRect(left, minOf(y1, y2), w.toFloat(), maxOf(y1, y2), paint)
        }
        paint.color = DIM; paint.alpha = 255; paint.textSize = dp(ctx, 10).toFloat()
        listOf(hi - pad, (hi + lo) / 2, lo + pad).forEach { v -> c.drawText(label(v), 0f, py(v) + dp(ctx, 4), paint) }

        val path = Path()
        // Light smoothing: 5-point average.
        pts.forEachIndexed { k, i ->
            val win = pts.subList(maxOf(0, k - 2), minOf(pts.size, k + 3)).map { ys[it] }
            val y = py(win.average().toFloat())
            if (k == 0) path.moveTo(px(xs[i]), y) else path.lineTo(px(xs[i]), y)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(ctx, 2).toFloat(); paint.color = color
        c.drawPath(path, paint)
        return ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true }
    }

    /** The GPS route, drawn as a line (no map tiles). */
    /** The route on a real street/trail map (OpenStreetMap), with start (green) and finish (red). */
    @android.annotation.SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun route(ctx: Context, track: org.json.JSONArray): View {
        val n = track.length()
        val stepBy = maxOf(1, n / 800)
        val pts = org.json.JSONArray()
        for (i in 0 until n step stepBy) track.optJSONArray(i)?.let { p -> pts.put(org.json.JSONArray(listOf(p.optDouble(0), p.optDouble(1)))) }
        track.optJSONArray(n - 1)?.let { p -> pts.put(org.json.JSONArray(listOf(p.optDouble(0), p.optDouble(1)))) }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.css">
            <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"></script>
            <style>html,body,#m{margin:0;height:100%;background:#1c2421}</style></head><body><div id="m"></div><script>
            var p=$pts;var m=L.map('m',{zoomControl:true,attributionControl:true,dragging:true,scrollWheelZoom:false});
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(m);
            L.polyline(p,{color:'#111',weight:8,opacity:.55}).addTo(m);
            var l=L.polyline(p,{color:'#d4e635',weight:5}).addTo(m);
            L.circleMarker(p[0],{radius:7,color:'#fff',weight:2,fillColor:'#46c46e',fillOpacity:1}).addTo(m);
            L.circleMarker(p[p.length-1],{radius:7,color:'#fff',weight:2,fillColor:'#e5484d',fillOpacity:1}).addTo(m);
            // The map may get its real size a moment after the page loads — fit the route once it has it.
            function fit(){m.invalidateSize();m.fitBounds(l.getBounds(),{padding:[18,18],maxZoom:17});}
            var fitted=0;function tryFit(){if(document.getElementById('m').clientHeight>50){fit();fitted++;}}
            tryFit();[100,300,700,1500].forEach(function(t){setTimeout(tryFit,t);});
            window.addEventListener('resize',function(){if(fitted<6)tryFit();});</script></body></html>"""
        return try {
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(CARD)
                // Let the map pan/zoom inside the scrolling page.
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                loadDataWithBaseURL("https://michealsizemore1.github.io/", html, "text/html", "utf-8", null)
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 300))
            }
        } catch (e: Exception) { outline(ctx, track) }
    }

    /**
     * Route map with a replay: a marker runs the route (with the part already run highlighted), a panel shows
     * time, distance, pace, heart rate and power at that moment, and each mile has a marker with its split.
     */
    fun replay(ctx: Context, run: org.json.JSONObject, track: org.json.JSONArray): View {
        val samples = run.optJSONArray("samples")
        val n = track.length()
        val moving = run.optInt("movingSeconds").coerceAtLeast(1)
        val hasT = track.optJSONArray(n - 1)?.length() ?: 0 >= 3
        // Samples by moving second (for distance / HR / power at each GPS point).
        val sT = IntArray(samples?.length() ?: 0) { samples!!.optJSONArray(it)?.optInt(0) ?: 0 }
        fun sampleAt(t: Int): org.json.JSONArray? {
            if (sT.isEmpty()) return null
            var lo = 0; var hi = sT.size - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (sT[mid] < t) lo = mid + 1 else hi = mid }
            return samples!!.optJSONArray(lo)
        }
        val stepBy = maxOf(1, n / 1500)
        val frames = org.json.JSONArray()
        var i = 0
        while (i < n) {
            val p = track.optJSONArray(i)
            if (p != null) {
                val t = if (hasT) p.optDouble(2).toInt() else (moving.toLong() * i / maxOf(1, n - 1)).toInt()
                val s = sampleAt(t); val s30 = sampleAt(maxOf(0, t - 30))
                val d = (s?.optInt(1) ?: 0) / 10.0
                val d30 = (s30?.optInt(1) ?: 0) / 10.0
                val dt = (s?.optInt(0) ?: t) - (s30?.optInt(0) ?: 0)
                val pace = if (dt >= 10 && d - d30 > 5) (dt / ((d - d30) / 1609.344)).toInt() else 0
                frames.put(org.json.JSONArray(listOf(
                    Math.round(p.optDouble(0) * 1e6) / 1e6, Math.round(p.optDouble(1) * 1e6) / 1e6, t, Math.round(d),
                    s?.optInt(2) ?: 0, pace, s?.optInt(5) ?: 0)))
            }
            i += if (i + stepBy >= n && i != n - 1) (n - 1 - i).coerceAtLeast(1) else stepBy
        }
        // Mile markers with splits.
        val miles = org.json.JSONArray()
        io.github.michealsizemore1.top25widget.Splits.miles(run).forEach { sp ->
            if (sp.meters >= 1609.344 * 0.95) miles.put(org.json.JSONObject().put("n", sp.n).put("m", sp.n * 1609.344)
                .put("pace", RunMath.paceFor(sp.sec, sp.meters)).put("time", RunMath.clock(sp.sec)).put("hr", sp.hr).put("pw", sp.power))
        }
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.css">
            <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"></script>
            <style>
            html,body{margin:0;height:100%;background:#1c2421;font-family:system-ui,sans-serif;color:#fff}
            #wrap{display:flex;flex-direction:column;height:100%}
            #m{flex:1}
            #panel{display:grid;grid-template-columns:repeat(5,1fr);gap:2px;padding:8px 6px 4px;background:#1c2421}
            .s b{display:block;font-size:17px;font-variant-numeric:tabular-nums}.s span{font-size:10px;color:#9aa3a0;text-transform:uppercase}
            .s{text-align:center}
            #ctl{display:flex;align-items:center;gap:8px;padding:4px 8px 10px}
            button{background:#d4e635;color:#000;border:0;border-radius:18px;font-weight:800;font-size:15px;padding:8px 14px}
            #spd{background:#2a3330;color:#fff}
            input[type=range]{flex:1;accent-color:#d4e635}
            .mi{background:#d4e635;color:#000;border:2px solid #fff;border-radius:50%;width:20px;height:20px;line-height:20px;text-align:center;font:800 11px system-ui}
            .leaflet-popup-content{margin:8px 10px;font:13px system-ui}
            </style></head><body><div id="wrap">
            <div id="panel"><div class="s"><b id="vt">0:00</b><span>time</span></div><div class="s"><b id="vd">0.00</b><span>miles</span></div>
            <div class="s"><b id="vp">–</b><span>pace</span></div><div class="s"><b id="vh" style="color:#ff6b6b">–</b><span>HR</span></div>
            <div class="s"><b id="vw" style="color:#ffb347">–</b><span>watts</span></div></div>
            <div id="m"></div>
            <div id="ctl"><button id="play">▶ Play</button><button id="spd">60×</button><input id="pos" type="range" min="0" max="1000" value="0"></div>
            </div><script>
            var F=$frames, MI=$miles;
            var m=L.map('m',{zoomControl:true,scrollWheelZoom:false});
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(m);
            var all=F.map(function(f){return [f[0],f[1]];});
            L.polyline(all,{color:'#111',weight:8,opacity:.5}).addTo(m);
            var full=L.polyline(all,{color:'#d4e635',weight:4,opacity:.35}).addTo(m);
            var done=L.polyline([],{color:'#d4e635',weight:6}).addTo(m);
            L.circleMarker(all[0],{radius:6,color:'#fff',weight:2,fillColor:'#46c46e',fillOpacity:1}).addTo(m);
            L.circleMarker(all[all.length-1],{radius:6,color:'#fff',weight:2,fillColor:'#e5484d',fillOpacity:1}).addTo(m);
            // Mile markers.
            MI.forEach(function(x){var k=0;while(k<F.length-1&&F[k][3]<x.m)k++;
              L.marker(all[k],{icon:L.divIcon({className:'',html:'<div class="mi">'+x.n+'</div>',iconSize:[24,24],iconAnchor:[12,12]})}).addTo(m)
               .bindPopup('<b>Mile '+x.n+'</b><br>'+x.pace+' /mi · '+x.time+(x.hr?'<br>'+x.hr+' bpm':'')+(x.pw?' · '+x.pw+' W':''));});
            var dot=L.circleMarker(all[0],{radius:9,color:'#fff',weight:3,fillColor:'#4fa3e0',fillOpacity:1}).addTo(m);
            function clock(s){s=Math.round(s);var h=Math.floor(s/3600),mm=Math.floor(s%3600/60),ss=s%60;return (h?h+':'+String(mm).padStart(2,'0'):mm)+':'+String(ss).padStart(2,'0');}
            var T=F[F.length-1][2]||1, t=0, playing=false, speeds=[30,60,120,240], si=1, last=0, idx=0;
            function frameAt(tt){while(idx<F.length-1&&F[idx+1][2]<=tt)idx++;while(idx>0&&F[idx][2]>tt)idx--;return idx;}
            function show(follow){var k=frameAt(t),f=F[k];dot.setLatLng([f[0],f[1]]);done.setLatLngs(all.slice(0,k+1));
              document.getElementById('vt').textContent=clock(t);document.getElementById('vd').textContent=(f[3]/1609.344).toFixed(2);
              document.getElementById('vp').textContent=f[5]?clock(f[5]):'–';document.getElementById('vh').textContent=f[4]||'–';
              document.getElementById('vw').textContent=f[6]||'–';document.getElementById('pos').value=Math.round(t/T*1000);
              if(follow&&!m.getBounds().pad(-0.03).contains(dot.getLatLng()))m.panTo(dot.getLatLng(),{animate:true});}
            function step(now){if(!playing)return;if(last)t+=Math.min(1,(now-last)/1000)*speeds[si];last=now;
              if(t>=T){t=T;playing=false;document.getElementById('play').textContent='↺ Replay';}show(true);if(playing)requestAnimationFrame(step);}
            document.getElementById('play').onclick=function(){if(t>=T){t=0;idx=0;}playing=!playing;last=0;
              this.textContent=playing?'❚❚ Pause':'▶ Play';if(playing)requestAnimationFrame(step);};
            document.getElementById('spd').onclick=function(){si=(si+1)%speeds.length;this.textContent=speeds[si]+'×';};
            document.getElementById('pos').oninput=function(){t=this.value/1000*T;show(true);};
            function fit(){m.invalidateSize();m.fitBounds(full.getBounds(),{padding:[18,18],maxZoom:17});}
            var fitted=0;function tryFit(){if(document.getElementById('m').clientHeight>50){fit();fitted++;}}
            tryFit();[100,300,700,1500].forEach(function(x){setTimeout(tryFit,x);});
            window.addEventListener('resize',function(){if(fitted<6)tryFit();});show(false);
            </script></body></html>"""
        return try {
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(CARD)
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                loadDataWithBaseURL("https://michealsizemore1.github.io/", html, "text/html", "utf-8", null)
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 470))
            }
        } catch (e: Exception) { outline(ctx, track) }
    }

    /** Plain route outline (no map) — used if the map can't be shown. */
    fun outline(ctx: Context, track: org.json.JSONArray): View {
        val w = ctx.resources.displayMetrics.widthPixels - dp(ctx, 64)
        val lats = (0 until track.length()).map { track.getJSONArray(it).optDouble(0) }
        val lons = (0 until track.length()).map { track.getJSONArray(it).optDouble(1) }
        val midLat = Math.toRadians(lats.average())
        val xs = lons.map { it * Math.cos(midLat) }
        val minX = xs.min(); val maxX = xs.max(); val minY = lats.min(); val maxY = lats.max()
        val span = maxOf(maxX - minX, maxY - minY, 1e-6)
        val h = (w * ((maxY - minY) / span).coerceIn(0.4, 1.0)).toInt() + dp(ctx, 20)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = dp(ctx, 10).toFloat()
        val scale = (minOf((w - 2 * m) / (maxX - minX).coerceAtLeast(1e-9), (h - 2 * m) / (maxY - minY).coerceAtLeast(1e-9))).toFloat()
        fun x(i: Int) = m + ((xs[i] - minX) * scale).toFloat()
        fun y(i: Int) = h - m - ((lats[i] - minY) * scale).toFloat()
        val path = Path().apply { moveTo(x(0), y(0)); for (i in 1 until xs.size) lineTo(x(i), y(i)) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(ctx, 3).toFloat(); color = ACCENT; strokeJoin = Paint.Join.ROUND }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF46C46E.toInt(); c.drawCircle(x(0), y(0), dp(ctx, 5).toFloat(), p)
        p.color = 0xFFE5484D.toInt(); c.drawCircle(x(xs.size - 1), y(xs.size - 1), dp(ctx, 5).toFloat(), p)
        return ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true }
    }
}
