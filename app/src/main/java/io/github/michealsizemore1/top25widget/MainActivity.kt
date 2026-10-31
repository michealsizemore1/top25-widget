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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "Add a Top 25, NFL, or WNBA widget, then long-press it and drag the edges to resize. Tap a game for details. " +
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
        button("Refresh scores now") {
            League.entries.forEach { Scheduler.refreshNow(this, it) }
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        }
        if (Config.SITE_URL.isNotBlank()) button("Open Top 25 site") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.SITE_URL)))
        }

        root.addView(TextView(this).apply {
            text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7370"))
            setPadding(0, px(16), 0, 0)
        })

        setContentView(root)
    }
}
