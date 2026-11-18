package com.iris.gallery.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "City, district" for a day's header, only when the photos really carry GPS coordinates in EXIF.
 * Read-only: no file or MediaStore metadata is ever written. Resolved lazily for the headers that are on
 * screen, serialised (one geocoder call at a time), cached per ~1 km cell and per day. When there is no GPS,
 * no geocoder backend or no network the header simply shows the date alone; nothing is guessed.
 */
object LocationLabels {
    private const val NONE = ""
    private val perDay = ConcurrentHashMap<String, String>()
    private val perCell = ConcurrentHashMap<String, String>()
    private val failedCells = ConcurrentHashMap.newKeySet<String>()
    private val gate = Mutex()

    /** [dayKey] identifies the group (the stable key of its first photo). */
    suspend fun forDay(context: Context, dayKey: String, items: List<MediaImage>): String? {
        perDay[dayKey]?.let { return it.ifEmpty { null } }
        val result = withContext(Dispatchers.IO) { gate.withLock { resolve(context.applicationContext, items) } }
        // a failed lookup is remembered only for this session so a later network/geocoder recovery can fill it in
        perDay[dayKey] = result ?: NONE
        return result
    }

    private suspend fun resolve(context: Context, items: List<MediaImage>): String? {
        if (!Geocoder.isPresent()) return null
        var tried = 0
        for (img in items) {
            if (img.isVideo) continue
            if (tried++ >= 4) break
            val ll = readLatLong(context, img) ?: continue
            val cell = "%.2f,%.2f".format(Locale.US, ll.first, ll.second)
            perCell[cell]?.let { return it }
            if (cell in failedCells) return null
            val name = geocode(context, ll.first, ll.second)
            if (name != null) { perCell[cell] = name; return name }
            failedCells.add(cell)
            return null
        }
        return null
    }

    private fun readLatLong(context: Context, img: MediaImage): Pair<Double, Double>? = runCatching {
        val file = if (img.path.isNotBlank()) File(img.path) else null
        val exif = if (file != null && file.canRead()) {
            ExifInterface(file)
        } else {
            val uri: Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(img.uri) else img.uri
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return null
        }
        val ll = exif.latLong ?: return null
        // 0,0 is what some cameras write when they have no fix
        if (ll[0] == 0.0 && ll[1] == 0.0) null else ll[0] to ll[1]
    }.getOrNull()

    private suspend fun geocode(context: Context, lat: Double, lng: Double): String? {
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = withTimeoutOrNull(6_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
            }
        }
        return address?.let(::label)
    }

    internal fun label(a: Address): String? {
        val city = a.locality ?: a.subAdminArea ?: a.adminArea
        val district = a.subLocality ?: a.subAdminArea?.takeIf { it != city }
        return listOfNotNull(city, district).distinct().joinToString(", ").ifBlank { a.countryName }?.ifBlank { null }
    }
}
