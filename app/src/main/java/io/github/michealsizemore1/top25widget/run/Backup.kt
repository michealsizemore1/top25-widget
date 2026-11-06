package io.github.michealsizemore1.top25widget.run

import android.content.Context
import android.net.Uri
import io.github.michealsizemore1.top25widget.ReadinessSync
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything BeyondStride keeps on the phone, as one .zip file the person picks a place for
 * (Google Drive, Downloads, …): runs, workouts & plan, saved courses, settings (zones, CP, LiveTrack…),
 * morning checks, sleep and personal bests. The watch gets its data back from the phone after a restore.
 * The same file will move everything into the stand-alone BeyondStride app.
 */
object Backup {
    const val VERSION = 1
    /** Settings files that belong to BeyondStride (not the Sports Scores widget). */
    private val PREFS = listOf("runsettings", "readiness", "runflags", "trailsel")
    /** Folders and files in the app's storage. */
    private val DIRS = listOf("runs", "courses")
    private val FILES = listOf("workout_library.json", "workout_plan.json")

    private fun meta(ctx: Context) = ctx.getSharedPreferences("backup", 0)
    fun uri(ctx: Context): Uri? = meta(ctx).getString("uri", null)?.let { Uri.parse(it) }
    fun lastAt(ctx: Context) = meta(ctx).getLong("at", 0L)
    fun lastInfo(ctx: Context) = meta(ctx).getString("info", "") ?: ""
    fun auto(ctx: Context) = meta(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, on: Boolean) = meta(ctx).edit().putBoolean("auto", on).apply()
    fun fileName() = "BeyondStride-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

    /** Remember the chosen file so later backups (and automatic ones) update it. */
    fun remember(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { }
        meta(ctx).edit().putString("uri", uri.toString()).apply()
    }

    private fun prefsJson(ctx: Context): JSONObject {
        val out = JSONObject()
        PREFS.forEach { name ->
            val o = JSONObject()
            ctx.getSharedPreferences(name, 0).all.forEach { (k, v) ->
                val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "set"; else -> return@forEach }
                o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.toList()) else v))
            }
            out.put(name, o)
        }
        return out
    }

    /** Writes the backup to [uri]. Returns a short summary ("42 runs · 3 courses"). */
    fun write(ctx: Context, uri: Uri): String {
        var runs = 0; var courses = 0
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
            ZipOutputStream(os.buffered()).use { z ->
                fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
                DIRS.forEach { d ->
                    File(ctx.filesDir, d).listFiles()?.filter { it.isFile }?.forEach { f ->
                        put("$d/${f.name}", f.readBytes()); if (d == "runs") runs++ else courses++
                    }
                }
                FILES.forEach { n -> File(ctx.filesDir, n).takeIf { it.exists() }?.let { put(n, it.readBytes()) } }
                put("prefs.json", prefsJson(ctx).toString().toByteArray())
                put("backup.json", JSONObject().put("app", "BeyondStride").put("version", VERSION)
                    .put("created", System.currentTimeMillis()).put("runs", runs).put("courses", courses).toString(2).toByteArray())
            }
        }
        val info = "$runs runs · $courses courses · ${ReadinessSync.checks(ctx).size} morning checks"
        meta(ctx).edit().putLong("at", System.currentTimeMillis()).putString("info", info).apply()
        return info
    }

    /** After a new run arrives: refresh the saved backup file (if the person turned that on). */
    fun autoUpdate(ctx: Context) {
        val u = uri(ctx) ?: return
        if (!auto(ctx)) return
        try { write(ctx, u) } catch (e: Exception) { meta(ctx).edit().putString("info", "Automatic backup failed — tap Back up now").apply() }
    }

    /** Reads a backup file without changing anything: what's in it. */
    fun peek(ctx: Context, uri: Uri): JSONObject? = try {
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                var e = z.nextEntry; var meta: JSONObject? = null
                while (e != null) { if (e.name == "backup.json") meta = JSONObject(z.readBytes().toString(Charsets.UTF_8)); e = z.nextEntry }
                meta
            }
        }
    } catch (e: Exception) { null }

    /**
     * Restores a backup: runs and courses are added (existing ones with the same name are replaced, others kept),
     * workouts/plan and settings are replaced. Then everything is sent to the watch.
     */
    fun restore(ctx: Context, uri: Uri): String {
        var runs = 0; var courses = 0
        var prefs: JSONObject? = null
        val base = ctx.filesDir.canonicalPath
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val name = e.name
                    val bytes = z.readBytes()
                    when {
                        name == "prefs.json" -> prefs = JSONObject(bytes.toString(Charsets.UTF_8))
                        name == "backup.json" -> { }
                        (DIRS.any { name.startsWith("$it/") } || name in FILES) && !name.contains("..") -> {
                            val f = File(ctx.filesDir, name)
                            if (f.canonicalPath.startsWith(base)) {
                                f.parentFile?.mkdirs(); f.writeBytes(bytes)
                                if (name.startsWith("runs/")) runs++ else if (name.startsWith("courses/")) courses++
                            }
                        }
                    }
                    e = z.nextEntry
                }
            }
        }
        prefs?.let { all ->
            PREFS.forEach { name ->
                val o = all.optJSONObject(name) ?: return@forEach
                val ed = ctx.getSharedPreferences(name, 0).edit()
                o.keys().forEach { k ->
                    // Sync bookkeeping is left out, so the restored settings count as new and reach the watch.
                    if (k.startsWith("at_") || k.startsWith("syn_")) return@forEach
                    val x = o.getJSONObject(k)
                    when (x.getString("t")) {
                        "b" -> ed.putBoolean(k, x.getBoolean("v"))
                        "i" -> ed.putInt(k, x.getInt("v"))
                        "l" -> ed.putLong(k, x.getLong("v"))
                        "f" -> ed.putFloat(k, x.getDouble("v").toFloat())
                        "s" -> ed.putString(k, x.getString("v"))
                        "set" -> ed.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    }
                }
                if (name == "runsettings") {
                    ctx.getSharedPreferences(name, 0).all.keys.filter { it.startsWith("at_") || it.startsWith("syn_") }.forEach { ed.remove(it) }
                    ed.putLong("settingsAt", System.currentTimeMillis())
                }
                ed.commit()
            }
        }
        // Send it all to the watch.
        RunSettings.pushToWatch(ctx)
        WorkoutStore.pushToWatch(ctx)
        io.github.michealsizemore1.top25widget.Courses.list(ctx).forEach { io.github.michealsizemore1.top25widget.Courses.save(ctx, it, "phone") }
        ReadinessSync.pushAll(ctx)
        return "$runs runs · $courses courses restored"
    }
}
