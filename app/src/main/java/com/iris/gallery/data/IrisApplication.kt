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
    private var hangScheduled = false

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
                    }
                }
                if (!hangScheduled) {
                    hangScheduled = true
                    Handler(Looper.getMainLooper()).postDelayed({
                        val act = topActivity
                        if (act != null && !act.isFinishing) {
                            showReport(act, "THREAD DUMP (25s after launch)", buildThreadDump())
                        }
                    }, 25_000L)
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

    private fun buildThreadDump(): String {
        val sb = StringBuilder()
        val all = Thread.getAllStackTraces()
        for ((thread, frames) in all) {
            val ours = frames.any { it.className.startsWith("com.iris.gallery") }
            if (thread.name == "main" || ours) {
                sb.append("== ").append(thread.name).append(" [").append(thread.state).append("]\n")
                for (f in frames.take(22)) {
                    sb.append("  ").append(f.className.substringAfterLast('.'))
                        .append('.').append(f.methodName)
                        .append(':').append(f.lineNumber).append('\n')
                }
                sb.append('\n')
            }
        }
        return sb.toString().take(12000)
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
            .build()
    }
}
