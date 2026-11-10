package com.iris.gallery.data

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Small in-memory diagnostic: when MediaStore tells us a photo/video changed, and when the grid shows it.
 * Shown from About (tap the version number 5 times). Nothing is sent anywhere.
 */
object LatencyLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        lines.addLast("${fmt.format(System.currentTimeMillis())}  $message")
        while (lines.size > 40) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "No events yet. Save a photo, then open this again." else lines.joinToString("\n")

    fun time(millis: Long): String = fmt.format(millis)
}
