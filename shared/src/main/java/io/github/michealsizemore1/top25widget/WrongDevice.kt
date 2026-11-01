package io.github.michealsizemore1.top25widget

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView

/**
 * The phone and watch apps share one app identity (needed so they can talk to each other),
 * so Android will let either file install on either device. If the wrong one is opened,
 * say so plainly instead of showing a screen built for the other device.
 */
object WrongDevice {
    /** Returns true (and shows a message) if this app is running on the wrong kind of device. */
    fun check(a: Activity, isWatchApp: Boolean): Boolean {
        val onWatch = a.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        if (onWatch == isWatchApp) return false
        a.setContentView(TextView(a).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = if (onWatch) 14f else 18f
            val pad = (24 * a.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            text = if (onWatch)
                "This is the PHONE app.\n\nInstall the WATCH file on this watch:\nWATCH-install-on-watch.apk"
            else
                "This is the WATCH app — it was installed on the phone by mistake.\n\n" +
                "Download and install PHONE-install-on-phone.apk from the latest build on GitHub " +
                "(github.com/michealsizemore1/top25-widget/releases).\n\nYour runs, plan, and settings are kept."
        })
        return true
    }
}
