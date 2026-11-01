package io.github.michealsizemore1.top25widget.run

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Strava, using your own Strava API app (Client ID + Secret from strava.com/settings/api,
 * with "Authorization Callback Domain" set to  localhost).
 * Runs upload as TCX files with heart rate, cadence, power, laps, and the GPS track when there is one.
 */
object Strava {
    const val REDIRECT = "scoresrun://localhost/strava"
    private fun p(ctx: Context) = ctx.getSharedPreferences("strava", 0)

    fun clientId(ctx: Context) = p(ctx).getString("clientId", "").orEmpty()
    fun clientSecret(ctx: Context) = p(ctx).getString("clientSecret", "").orEmpty()
    fun connected(ctx: Context) = p(ctx).getString("refresh", null) != null
    fun athlete(ctx: Context) = p(ctx).getString("athlete", "").orEmpty()
    fun autoUpload(ctx: Context) = p(ctx).getBoolean("auto", true)
    fun setAutoUpload(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()

    fun saveApp(ctx: Context, id: String, secret: String) =
        p(ctx).edit().putString("clientId", id.trim()).putString("clientSecret", secret.trim()).apply()

    fun disconnect(ctx: Context) = p(ctx).edit().remove("access").remove("refresh").remove("expires").remove("athlete").apply()

    /** Opens Strava's sign-in page (or the Strava app) to approve uploads. */
    fun authorize(ctx: Context) {
        val url = "https://www.strava.com/oauth/mobile/authorize?client_id=${clientId(ctx)}" +
            "&redirect_uri=${URLEncoder.encode(REDIRECT, "UTF-8")}&response_type=code" +
            "&approval_prompt=auto&scope=activity:write,read"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Finishes sign-in with the code Strava sends back. */
    fun exchangeCode(ctx: Context, code: String) {
        val r = postForm("https://www.strava.com/oauth/token", mapOf(
            "client_id" to clientId(ctx), "client_secret" to clientSecret(ctx),
            "code" to code, "grant_type" to "authorization_code"))
        storeTokens(ctx, r)
        r.optJSONObject("athlete")?.let { a ->
            p(ctx).edit().putString("athlete", "${a.optString("firstname")} ${a.optString("lastname")}".trim()).apply()
        }
    }

    private fun storeTokens(ctx: Context, r: JSONObject) {
        p(ctx).edit().putString("access", r.getString("access_token"))
            .putString("refresh", r.getString("refresh_token"))
            .putLong("expires", r.optLong("expires_at")).apply()
    }

    private fun token(ctx: Context): String {
        val exp = p(ctx).getLong("expires", 0)
        if (System.currentTimeMillis() / 1000 > exp - 300) {
            val r = postForm("https://www.strava.com/oauth/token", mapOf(
                "client_id" to clientId(ctx), "client_secret" to clientSecret(ctx),
                "refresh_token" to p(ctx).getString("refresh", "").orEmpty(), "grant_type" to "refresh_token"))
            storeTokens(ctx, r)
        }
        return p(ctx).getString("access", "").orEmpty()
    }

    /**
     * Uploads one run and waits for Strava to process it.
     * Returns the Strava activity id. A run Strava already has counts as uploaded.
     */
    fun upload(ctx: Context, run: JSONObject): Long {
        val start = run.optLong("start")
        val tcx = Tcx.build(run)
        val boundary = "----run${System.currentTimeMillis()}"
        val conn = (URL("https://www.strava.com/api/v3/uploads").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${token(ctx)}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray()); out.writeBytes("\r\n")
            }
            field("data_type", "tcx")
            field("name", PhoneRuns.title(run))
            field("external_id", "watchrun-$start")
            field("description", Summary.notes(ctx, run, forStrava = true))
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"run-$start.tcx\"\r\n")
            out.writeBytes("Content-Type: application/xml\r\n\r\n")
            out.write(tcx.toByteArray()); out.writeBytes("\r\n--$boundary--\r\n")
        }
        var r = readJson(conn)
        val uploadId = r.optLong("id")
        // Strava processes uploads in the background: check until it's done.
        repeat(20) {
            r.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { err ->
                Regex("activities/(\\d+)").find(err)?.let { return it.groupValues[1].toLong() }   // duplicate
                throw RuntimeException(err)
            }
            if (r.optLong("activity_id") > 0) return r.optLong("activity_id")
            Thread.sleep(3000)
            val c = (URL("https://www.strava.com/api/v3/uploads/$uploadId").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer ${token(ctx)}"); connectTimeout = 20_000; readTimeout = 30_000
            }
            r = readJson(c)
        }
        throw RuntimeException("Strava is still processing — check again later")
    }

    private fun postForm(url: String, form: Map<String, String>): JSONObject {
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = try { JSONObject(text).let { j -> j.optString("message") + " " + (j.optJSONArray("errors")?.toString() ?: "") } } catch (e: Exception) { text }
            throw RuntimeException("Strava error $code: ${msg.take(200)}")
        }
        return JSONObject(text)
    }
}

/** Uploads a run to Strava in the background (retries when the phone is offline). */
class StravaWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        fun enqueue(ctx: Context, start: Long) {
            val req = OneTimeWorkRequestBuilder<StravaWorker>()
                .setInputData(workDataOf("start" to start))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("strava-$start", ExistingWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val start = inputData.getLong("start", 0)
        if (PhoneRuns.stravaId(applicationContext, start) > 0) return@withContext Result.success()
        val run = PhoneRuns.read(applicationContext, start) ?: return@withContext Result.failure()
        try {
            PhoneRuns.setStravaId(applicationContext, start, Strava.upload(applicationContext, run))
            Result.success()
        } catch (e: Exception) {
            applicationContext.getSharedPreferences("runflags", 0).edit().putString("strava_err_$start", e.message).apply()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Builds a Garmin TCX file: what Strava expects for runs with heart rate, cadence, and power. */
object Tcx {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun t(ms: Long) = iso.format(Date(ms))

    fun build(run: JSONObject): String {
        val start = run.optLong("start")
        val samples = run.optJSONArray("samples")
        val track = run.optJSONArray("track")
        val laps = run.optJSONArray("laps")
        // Wall-clock time of a sample: elapsed seconds if recorded (newer runs), else moving seconds.
        fun sampleTime(s: org.json.JSONArray) = start + 1000L * (if (s.length() > 6) s.optInt(6) else s.optInt(0))

        // GPS points by moving second, for matching to samples.
        val gps = HashMap<Int, Pair<Double, Double>>()
        if (track != null) for (i in 0 until track.length()) {
            val p = track.getJSONArray(i); gps[p.optDouble(2).toInt()] = p.optDouble(0) to p.optDouble(1)
        }
        var lastGps: Pair<Double, Double>? = null

        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
<Activities><Activity Sport="Running"><Id>${t(start)}</Id>
""")
        // Split the per-second samples into the recorded laps.
        var idx = 0
        val lapCount = laps?.length() ?: 0
        val total = samples?.length() ?: 0
        var lapEndT = 0
        for (li in 0 until maxOf(1, lapCount)) {
            val lap = laps?.optJSONObject(li)
            lapEndT += lap?.optInt("sec") ?: run.optInt("movingSeconds")
            val first = idx
            while (idx < total && (li == lapCount - 1 || samples!!.getJSONArray(idx).optInt(0) <= lapEndT)) idx++
            if (lapCount == 0) idx = total
            val lapStartMs = if (first < total) sampleTime(samples!!.getJSONArray(first)) - 1000 else start
            sb.append("<Lap StartTime=\"${t(lapStartMs)}\">")
            sb.append("<TotalTimeSeconds>${lap?.optInt("sec") ?: run.optInt("movingSeconds")}</TotalTimeSeconds>")
            sb.append("<DistanceMeters>${"%.1f".format(Locale.US, lap?.optDouble("m") ?: run.optDouble("distance"))}</DistanceMeters>")
            sb.append("<Calories>0</Calories>")
            (lap?.optInt("hr") ?: run.optInt("avgHr")).takeIf { it > 0 }?.let { sb.append("<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>") }
            sb.append("<Intensity>Active</Intensity><TriggerMethod>${if (lap?.optBoolean("auto") == true) "Distance" else "Manual"}</TriggerMethod><Track>\n")
            for (i in first until idx) {
                val s = samples!!.getJSONArray(i)
                val moving = s.optInt(0)
                gps[moving]?.let { lastGps = it }
                sb.append("<Trackpoint><Time>${t(sampleTime(s))}</Time>")
                lastGps?.let { (la, lo) -> sb.append("<Position><LatitudeDegrees>$la</LatitudeDegrees><LongitudeDegrees>$lo</LongitudeDegrees></Position>") }
                sb.append("<DistanceMeters>${"%.1f".format(Locale.US, s.optInt(1) / 10.0)}</DistanceMeters>")
                s.optInt(2).takeIf { it > 0 }?.let { sb.append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
                sb.append("<Extensions><ns3:TPX><ns3:Speed>${"%.2f".format(Locale.US, s.optInt(3) / 100.0)}</ns3:Speed>")
                s.optInt(4).takeIf { it > 0 }?.let { sb.append("<ns3:RunCadence>${it / 2}</ns3:RunCadence>") }   // TCX counts one foot
                s.optInt(5).takeIf { it > 0 }?.let { sb.append("<ns3:Watts>$it</ns3:Watts>") }
                sb.append("</ns3:TPX></Extensions></Trackpoint>\n")
            }
            sb.append("</Track></Lap>\n")
        }
        sb.append("<Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Name>Galaxy Watch Run</Name></Creator>")
        sb.append("</Activity></Activities></TrainingCenterDatabase>\n")
        return sb.toString()
    }
}
