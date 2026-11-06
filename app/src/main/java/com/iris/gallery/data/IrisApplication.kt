package com.iris.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import coil3.ImageLoader
import okio.Path.Companion.toOkioPath
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.StaticImageDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import com.iris.gallery.decoder.AvifCoilDecoder

class IrisApplication : Application(), SingletonImageLoader.Factory {
    companion object {
        lateinit var instance: IrisApplication
            private set
    }

    // ---- TEMPORARY DIAGNOSTICS (Ax Gallery) -------------------------------
    // 1) If the app crashed last time, show the stack trace on next launch.
    // 2) 25 seconds after launch, show where the main thread / app threads are
    //    (helps find where loading hangs). Remove once the bug is fixed.
    private var topActivity: Activity? = null
    private var crashShown = false

    private fun installDiagnostics() {
        val prefs = getSharedPreferences("ax_diag", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = "Thread: ${thread.name}\n" + Log.getStackTraceString(error)
                prefs.edit().putString("crash", text.take(12000)).commit()
            } catch (e: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
                if (!crashShown) {
                    val crash = prefs.getString("crash", null)
                    if (!crash.isNullOrBlank()) {
                        crashShown = true
                        prefs.edit().remove("crash").apply()
                        showReport(activity, "CRASH LAST TIME", crash)
                    } else {
                        // a native crash never reaches the handler above, so ask Android what killed us
                        val nativeReport = nativeCrashReport(prefs)
                        if (nativeReport != null) {
                            crashShown = true
                            showReport(activity, "NATIVE CRASH LAST TIME", nativeReport)
                        }
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (topActivity === activity) topActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** What Android recorded about the last native crash (signal, and the readable parts of the tombstone), or null. */
    private fun nativeCrashReport(prefs: android.content.SharedPreferences): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val last = prefs.getLong("last_exit_ts", 0L)
            val info = am.getHistoricalProcessExitReasons(packageName, 0, 6)
                .firstOrNull { it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > last }
                ?: return null
            prefs.edit().putLong("last_exit_ts", info.timestamp).apply()
            val sb = StringBuilder()
            sb.append("Crash: ").append(info.description ?: "native crash").append('\n')
            sb.append("Android ").append(Build.VERSION.RELEASE).append(" / ").append(Build.MODEL).append("\n\n")
            info.traceInputStream?.use { stream ->
                val bytes = stream.readBytes().let { if (it.size > 700_000) it.copyOf(700_000) else it }
                val run = StringBuilder()
                var kept = 0
                fun flush() {
                    if (run.length >= 6 && kept < 90 && run.any { it.isLetter() }) {
                        sb.append(run).append('\n')
                        kept++
                    }
                    run.setLength(0)
                }
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
                }
                flush()
            }
            sb.toString().take(6000)
        } catch (e: Throwable) {
            null
        }
    }

    private fun showReport(activity: Activity, title: String, body: String) {
        try {
            val tv = TextView(activity).apply {
                text = body
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(24, 24, 24, 24)
            }
            val scroll = ScrollView(activity).apply { addView(tv) }
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ax-report", body))
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Throwable) {
        }
    }
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        installDiagnostics()
        // Open the preference files on a background thread so the main thread finds them already cached.
        Thread {
            runCatching {
                for (name in listOf("library_preferences", "iris_gallery_settings")) {
                    getSharedPreferences(name, Context.MODE_PRIVATE).all
                }
            }
        }.apply { name = "prefs-prewarm"; isDaemon = true }.start()
        // TEMPORARY (emulator bug hunt only): log every disk read/write that happens on the main thread.
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectCustomSlowCalls().penaltyLog().build()
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AvifCoilDecoder.Factory())
                add(coil3.svg.SvgDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                    add(StaticImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
