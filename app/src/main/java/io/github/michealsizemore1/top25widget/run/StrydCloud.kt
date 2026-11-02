package io.github.michealsizemore1.top25widget.run

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Your Stryd account (PowerCenter). The pod records every run on its own and syncs it
 * through the Stryd app; this finds the matching run and copies the metrics the watch
 * can't read live: ground time, oscillation, leg spring stiffness, elevation, RSS, weather…
 *
 * Stryd has no public API — this uses the same sign-in and calendar the Stryd website uses,
 * so it could stop working if Stryd changes their site.
 */
object StrydCloud {
    private fun p(ctx: Context) = ctx.getSharedPreferences("strydcloud", 0)

    fun connected(ctx: Context) = p(ctx).getString("token", null) != null
    fun email(ctx: Context) = p(ctx).getString("email", "").orEmpty()
    fun autoFetch(ctx: Context) = p(ctx).getBoolean("auto", true)
    fun setAutoFetch(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()
    fun signOut(ctx: Context) = p(ctx).edit().clear().apply()

    /** Signs in and remembers the login (kept only in this app's private storage). */
    fun signIn(ctx: Context, email: String, password: String) {
        val r = request("https://www.stryd.com/b/email/signin", "POST",
            JSONObject().put("email", email.trim()).put("password", password).toString(), null)
        p(ctx).edit().putString("email", email.trim()).putString("password", password)
            .putString("token", r.getString("token")).putString("id", r.optString("id")).apply()
    }

    private fun relogin(ctx: Context) = signIn(ctx, email(ctx), p(ctx).getString("password", "").orEmpty())

    /**
     * Finds the Stryd activity that matches this run (started within 20 minutes) and returns
     * its metrics, or null if Stryd doesn't have it yet (the pod hasn't synced).
     */
    fun fetchFor(ctx: Context, run: JSONObject): JSONObject? {
        val startSec = run.optLong("start") / 1000
        val f = SimpleDateFormat("MM-dd-yyyy", Locale.US)
        val from = f.format(Date((startSec - 86400) * 1000)); val to = f.format(Date((startSec + 2 * 86400) * 1000))
        val url = "https://www.stryd.com/b/api/v1/activities/calendar?srtDate=$from&endDate=$to&sortBy=StartDate"
        val r = try { request(url, "GET", null, p(ctx).getString("token", null)) }
                catch (e: AuthError) { relogin(ctx); request(url, "GET", null, p(ctx).getString("token", null)) }
        val acts = r.optJSONArray("activities") ?: JSONArray()
        var best: JSONObject? = null; var bestGap = Long.MAX_VALUE
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val gap = abs(a.optLong("timestamp") - startSec)
            if (gap < bestGap) { bestGap = gap; best = a }
        }
        if (best == null || bestGap > 20 * 60) return null
        return metrics(best, run)
    }

    /** Pulls out the metrics worth keeping, in plain units. */
    private fun metrics(a: JSONObject, run: JSONObject): JSONObject {
        val out = JSONObject()
        fun num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
            if (a.has(k) && !a.isNull(k)) a.optDouble(k).takeIf { !it.isNaN() && it != 0.0 } else null
        }
        num("id")?.let { out.put("id", it.toLong()) }
        num("average_ground_time")?.let { out.put("groundTime", it) }              // ms
        num("average_oscillation")?.let { out.put("oscillation", it) }             // cm
        num("average_leg_spring")?.let { out.put("legSpring", it) }                // kN/m
        num("average_stride_length")?.let { out.put("stride", it) }               // m
        num("average_vertical_ratio", "vertical_ratio")?.let { out.put("verticalRatio", it) }
        num("average_form_power", "form_power")?.let { out.put("formPower", it) }   // W
        num("average_power")?.let { out.put("power", it) }
        num("stress", "rss")?.let { out.put("rss", it) }
        num("ftp", "cp", "critical_power")?.let { out.put("cp", it) }
        num("total_elevation_gain")?.let { out.put("elevGain", it) }              // m
        num("total_elevation_loss")?.let { out.put("elevLoss", it) }
        num("temperature")?.let { out.put("temp", it) }                            // °C
        num("humidity")?.let { out.put("humidity", if (it <= 1.0) it * 100 else it) }
        num("windSpeed")?.let { out.put("windSpeed", it) }
        // Air Power: Stryd reports it as watts or as a share of total power, depending on the field.
        a.keys().asSequence().firstOrNull { it.contains("air", ignoreCase = true) && it.contains("power", ignoreCase = true) }?.let { k ->
            val v = a.optDouble(k)
            if (!v.isNaN() && v > 0) {
                val total = a.optDouble("average_power").takeIf { !it.isNaN() && it > 0 } ?: run.optDouble("avgPower")
                val pct = when {
                    k.contains("percent", true) || k.contains("pct", true) -> v
                    k.contains("ratio", true) || v < 1.0 -> v * 100
                    total > 0 -> v / total * 100
                    else -> null
                }
                pct?.let { out.put("airPowerPct", Math.round(it * 10) / 10.0) }
                out.put("airPowerRaw", v)
            }
        }
        out.put("fetchedAt", System.currentTimeMillis())
        return out
    }

    private class AuthError : Exception("Stryd sign-in expired")

    private fun request(url: String, method: String, body: String?, token: String?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 40_000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer: $token")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code == 401 || code == 403) { if (token != null) throw AuthError() else throw RuntimeException("Stryd didn't accept that email and password") }
        if (code !in 200..299) throw RuntimeException("Stryd error $code")
        return JSONObject(text)
    }

    /** Copies the Stryd metrics into the saved run. */
    fun attach(ctx: Context, start: Long, metrics: JSONObject) {
        val run = PhoneRuns.read(ctx, start) ?: return
        run.put("stryd", metrics)
        PhoneRuns.save(ctx, run)
        // New details to share: offer it to the Command Center again (resending updates, never duplicates).
        ctx.getSharedPreferences("runflags", 0).edit().remove("cc_$start").apply()
    }
}

/**
 * Looks for the run in Stryd in the background. The pod syncs when the Stryd app connects,
 * which may be a while after the run, so this keeps checking for several hours.
 */
class StrydWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        fun enqueue(ctx: Context, start: Long, delayMinutes: Long = 10) {
            val req = OneTimeWorkRequestBuilder<StrydWorker>()
                .setInputData(workDataOf("start" to start))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("stryd-$start", ExistingWorkPolicy.REPLACE, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val start = inputData.getLong("start", 0)
        val run = PhoneRuns.read(applicationContext, start) ?: return@withContext Result.failure()
        if (run.has("stryd") || !StrydCloud.connected(applicationContext)) return@withContext Result.success()
        try {
            val m = StrydCloud.fetchFor(applicationContext, run)
            if (m != null) {
                StrydCloud.attach(applicationContext, start, m)
                Result.success()
            }
            else if (runAttemptCount < 5) Result.retry() else Result.failure()   // not synced yet
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
