package com.iris.gallery.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Trips" = photos taken close in time AND place. A new trip starts after a 12 h gap
 * or when the location jumps more than 50 km. Only trips with at least [MIN_TRIP_ITEMS] photos are kept.
 * Pure data code: no disk or network access.
 */
data class TripGroup(val start: Long, val end: Long, val items: List<MediaImage>)

private const val TRIP_GAP_MS = 12L * 60 * 60 * 1000
private const val TRIP_JUMP_KM = 50.0
private const val MIN_TRIP_ITEMS = 8

fun buildTrips(images: List<MediaImage>): List<TripGroup> {
    val timed = images.filter { it.sortDate > 0 }.sortedBy { it.sortDate }
    val groups = mutableListOf<MutableList<MediaImage>>()
    var current = mutableListOf<MediaImage>()
    var prev: MediaImage? = null
    for (item in timed) {
        val p = prev
        if (p != null && (item.sortDate - p.sortDate > TRIP_GAP_MS || jumpedFar(p, item))) {
            groups += current
            current = mutableListOf()
        }
        current += item
        prev = item
    }
    if (current.isNotEmpty()) groups += current
    return groups
        .filter { it.size >= MIN_TRIP_ITEMS }
        .map { TripGroup(start = it.first().sortDate, end = it.last().sortDate, items = it) }
        .sortedByDescending { it.start }
}

private fun jumpedFar(a: MediaImage, b: MediaImage): Boolean {
    val la = a.latitude ?: return false
    val lo = a.longitude ?: return false
    val lb = b.latitude ?: return false
    val lob = b.longitude ?: return false
    return distanceKm(la, lo, lb, lob) > TRIP_JUMP_KM
}

fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * Cleanup SUGGESTIONS only. Nothing is removed here: the user selects and confirms,
 * and removal goes through the normal trash flow (restorable).
 */
enum class CleanupKind { SCREENSHOTS, LARGE_VIDEOS }

data class CleanupGroup(val kind: CleanupKind, val items: List<MediaImage>)

const val LARGE_VIDEO_BYTES = 200L * 1024 * 1024

fun buildCleanupGroups(images: List<MediaImage>): List<CleanupGroup> {
    val shots = images.filter { !it.isVideo && it.isScreenshot }.sortedByDescending { it.sortDate }
    val videos = images.filter { it.isVideo && it.sizeBytes >= LARGE_VIDEO_BYTES }.sortedByDescending { it.sizeBytes }
    return buildList {
        if (shots.isNotEmpty()) add(CleanupGroup(CleanupKind.SCREENSHOTS, shots))
        if (videos.isNotEmpty()) add(CleanupGroup(CleanupKind.LARGE_VIDEOS, videos))
    }
}
