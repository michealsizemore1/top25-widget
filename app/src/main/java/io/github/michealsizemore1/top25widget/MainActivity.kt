package io.github.michealsizemore1.top25widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Small launcher screen: lets you pin the widget and explains how to resize it. */
class MainActivity : Activity() {

    private var statusView: TextView? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            statusView?.text = "REFRESH STATUS\n" + League.entries.joinToString("\n") { Status.describe(this@MainActivity, it) }
            ticker.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is also a refresh.
        League.entries.forEach { Scheduler.refreshNow(this, it) }
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (io.github.michealsizemore1.top25widget.WrongDevice.check(this, isWatchApp = false)) return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121A17"))
            setPadding(px(24), px(64), px(24), px(24))
        }

        root.addView(TextView(this).apply {
            text = "Sports Scores"
            textSize = 26f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "Add a Top 25, NFL, WNBA, NHL, XC Meets, or Track & Field widget, then long-press it and drag the edges to resize. Tap a game for details. " +
                "It refreshes every 15 minutes, every ${Config.LIVE_REFRESH_MINUTES} minutes " +
                "during live games, and whenever you tap ⟳."
            textSize = 15f
            setTextColor(Color.parseColor("#9AA3A0"))
            setPadding(0, px(12), 0, px(24))
        })

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(12) })
        }

        fun pin(receiver: Class<*>, label: String) {
            val mgr = AppWidgetManager.getInstance(this)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, receiver), null, null)
            } else {
                Toast.makeText(this, "Long-press your home screen → Widgets → $label", Toast.LENGTH_LONG).show()
            }
        }
        button("Add Top 25 widget") { pin(ScoresWidgetReceiver::class.java, "Top 25 Scores") }
        button("Add NFL widget") { pin(NflScoresWidgetReceiver::class.java, "NFL Scores") }
        button("Add WNBA widget") { pin(WnbaScoresWidgetReceiver::class.java, "WNBA Scores") }
        button("Add NHL widget") { pin(NhlScoresWidgetReceiver::class.java, "NHL Scores") }
        button("Add XC Meets widget") { pin(XcWidgetReceiver::class.java, "XC Meets") }
        button("Add Track & Field widget") { pin(TfWidgetReceiver::class.java, "Track & Field") }
        button("Refresh scores now") {
            League.entries.forEach { Scheduler.refreshNow(this, it) }
            XcScheduler.refreshNow(this)
            TfScheduler.refreshNow(this)
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        }
        // ---- Alerts
        root.addView(TextView(this).apply {
            text = "ALERTS"
            textSize = 13f
            letterSpacing = 0.1f
            setTextColor(Color.parseColor("#D4E635"))
            setPadding(0, px(12), 0, px(4))
        })
        fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = android.widget.Switch(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = on
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(8) })
        }
        toggle("Upset alerts (Top 25)", Alerts.upsetsOn(this)) { Alerts.setUpsetsOn(this, it) }
        toggle("Ohio State alerts", Alerts.osuOn(this)) { Alerts.setOsuOn(this, it) }
        button("Send a test alert") {
            if (Alerts.canNotify(this)) Alerts.test(this)
            else if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        button("Open Master App") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.SITE_URL)))
        }

        // ---- Refresh status (helps spot a stuck widget)
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA3A0"))
            setPadding(0, px(16), 0, 0)
        }
        root.addView(statusView)

        root.addView(TextView(this).apply {
            text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName?.substringBefore(" ·")}"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7370"))
            setPadding(0, px(16), 0, 0)
        })

        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121A17"))
            addView(root)
        })
        // Android 13+ asks once before an app may post notifications.
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Alerts.canNotify(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
