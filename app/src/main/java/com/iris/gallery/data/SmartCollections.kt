package com.iris.gallery.data

/**
 * "Trips" = photos taken close in time (a new trip starts after a 12 h gap). Only trips with at least
 * [MIN_TRIP_ITEMS] photos are kept. GPS is not loaded into the library model, so location is not used yet.
 * Pure data code: no disk or network access.
 */
data class TripGroup(val start: Long, val end: Long, val items: List<MediaImage>)

private const val TRIP_GAP_MS = 12L * 60 * 60 * 1000
private const val MIN_TRIP_ITEMS = 8

fun buildTrips(images: List<MediaImage>): List<TripGroup> {
    val timed = images.filter { it.sortDate > 0 }.sortedBy { it.sortDate }
    val groups = mutableListOf<MutableList<MediaImage>>()
    var current = mutableListOf<MediaImage>()
    var prev: MediaImage? = null
    for (item in timed) {
        val p = prev
        if (p != null && item.sortDate - p.sortDate > TRIP_GAP_MS) {
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
