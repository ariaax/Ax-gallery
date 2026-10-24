package com.iris.gallery.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds which installed app an album belongs to (Telegram, Pinterest, TikTok ...) so the album can show that app's icon.
 * Order: the app package inside the file path (Android/media/<package>/...), then the folder name, then the camera app.
 */
object AlbumAppIcons {
    // folder-name keywords -> candidate package names (first installed one wins).
    // Keywords of 4 letters or fewer must match the whole folder name; a trailing * means "folder name starts with".
    private val KNOWN: List<Pair<List<String>, List<String>>> = listOf(
        listOf("telegram") to listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram", "tw.nekomimi.nekogram", "nekox.messenger"),
        listOf("pinterest", "pins") to listOf("com.pinterest"),
        listOf("tiktok") to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme"),
        listOf("x", "twitter") to listOf("com.twitter.android"),
        listOf("instagram") to listOf("com.instagram.android"),
        listOf("threads") to listOf("com.instagram.barcelona"),
        listOf("whatsapp") to listOf("com.whatsapp", "com.whatsapp.w4b"),
        listOf("snapchat") to listOf("com.snapchat.android"),
        listOf("facebook") to listOf("com.facebook.katana", "com.facebook.lite"),
        listOf("messenger") to listOf("com.facebook.orca"),
        listOf("reddit") to listOf("com.reddit.frontpage"),
        listOf("discord") to listOf("com.discord"),
        listOf("signal") to listOf("org.thoughtcrime.securesms"),
        listOf("viber") to listOf("com.viber.voip"),
        listOf("wechat") to listOf("com.tencent.mm"),
        listOf("line") to listOf("jp.naver.line.android"),
        listOf("tumblr") to listOf("com.tumblr"),
        listOf("vsco") to listOf("com.vsco.cam"),
        listOf("snapseed") to listOf("com.niksoftware.snapseed"),
        listOf("lightroom") to listOf("com.adobe.lrmobile"),
        listOf("canva") to listOf("com.canva.editor"),
        listOf("capcut") to listOf("com.lemon.lvoverseas"),
        listOf("inshot") to listOf("com.camerasideas.instashot"),
        listOf("picsart") to listOf("com.picsart.studio"),
        listOf("youtube") to listOf("com.google.android.youtube"),
        listOf("chrome") to listOf("com.android.chrome"),
        listOf("bluesky") to listOf("xyz.blueskyweb.app"),
        listOf("mastodon") to listOf("org.joinmastodon.android"),
        listOf("slack") to listOf("com.Slack"),
        listOf("zoom") to listOf("us.zoom.videomeetings"),
        listOf("mpv*") to listOf("is.xyz.mpv", "live.mehiz.mpvkt", "app.marlboroadvance.mpvex"),
        listOf("vlc") to listOf("org.videolan.vlc"),
        listOf("bilibili") to listOf("tv.danmaku.bili"),
        listOf("pixiv") to listOf("jp.pxv.android"),
        listOf("libretorrent") to listOf("org.proninyaroslav.libretorrent"),
    )

    /** Every package name above; the manifest must list these under <queries> so Android lets us see them. */
    val allPackages: List<String> get() = KNOWN.flatMap { it.second }

    private val pathPackage = Regex("/android/(?:media|data)/([a-z0-9_.]+)/", RegexOption.IGNORE_CASE)
    private val resolvedByAlbum = ConcurrentHashMap<Long, String>() // package name, "vec:<kind>" or "" (nothing)
    private val iconByPackage = LruCache<String, ImageBitmap>(64)
    private val GENERIC_NAMES = setOf("movies", "pictures", "dcim", "documents", "media", "images", "video", "videos", "photos", "other", "backup", "data", "files")

    @Volatile private var launcherApps: List<Pair<String, String>>? = null // (label key, package)

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    private fun keyOf(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun launchers(pm: PackageManager): List<Pair<String, String>> {
        launcherApps?.let { return it }
        val list = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
                val label = ri.loadLabel(pm)?.toString().orEmpty()
                val key = keyOf(label)
                if (key.length >= 3) key to ri.activityInfo.packageName else null
            }
        }.getOrDefault(emptyList())
        launcherApps = list
        return list
    }

    private fun resolve(context: Context, album: MediaAlbum): String {
        val pm = context.packageManager
        val samplePath = album.images.firstOrNull { it.path.isNotBlank() }?.path.orEmpty()

        pathPackage.find(samplePath)?.groupValues?.get(1)?.let { if (installed(pm, it)) return it }

        val key = keyOf(album.name)
        if (key.isEmpty()) return ""

        for ((keywords, packages) in KNOWN) {
            val hit = keywords.any { k ->
                when {
                    k.endsWith("*") -> key.startsWith(k.dropLast(1))
                    k.length <= 4 -> key == k
                    else -> key.contains(k)
                }
            }
            if (hit) {
                packages.firstOrNull { installed(pm, it) }?.let { return it }
                // none of the known packages: any installed app whose name starts like this keyword (e.g. other mpv builds)
                val word = keywords.first().removeSuffix("*")
                if (word.length >= 3) launchers(pm).firstOrNull { it.first.startsWith(word) }?.let { return it.second }
            }
        }

        if (key == "camera" || key == "100andro") {
            runCatching {
                pm.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            }.getOrNull()?.let { return it }
            return "vec:camera"
        }

        // The folder is named like an installed app (e.g. "Downloads" -> the Downloads app).
        if (key !in GENERIC_NAMES && key.length >= 4) {
            launchers(pm).firstOrNull { (label, _) -> key == label || key.startsWith(label) || label.startsWith(key) }
                ?.let { return it.second }
        }

        return when {
            key.startsWith("download") -> "vec:download"
            key.startsWith("screenshot") -> "vec:screenshot"
            key.startsWith("screenrecord") || key.startsWith("screenrecorder") -> "vec:video"
            else -> ""
        }
    }

    /** Icon for this album, or null when it has no known app. Safe to call off the main thread. */
    fun badgeFor(context: Context, album: MediaAlbum): AlbumBadge? {
        val app = context.applicationContext
        val res = resolvedByAlbum.getOrPut(album.id) { resolve(app, album) }
        if (res.isEmpty()) return null
        if (res.startsWith("vec:")) {
            val v = when (res.removePrefix("vec:")) {
                "download" -> Icons.Outlined.Download
                "screenshot" -> Icons.Outlined.Smartphone
                "video" -> Icons.Outlined.Videocam
                else -> Icons.Outlined.PhotoCamera
            }
            return AlbumBadge.Vector(v)
        }
        iconByPackage.get(res)?.let { return AlbumBadge.App(it) }
        val bmp = runCatching {
            app.packageManager.getApplicationIcon(res).toBitmap(96, 96).asImageBitmap()
        }.getOrNull() ?: return null
        iconByPackage.put(res, bmp)
        return AlbumBadge.App(bmp)
    }
}

/** What an album shows in its corner: the real app icon, or a simple symbol for system folders. */
sealed interface AlbumBadge {
    data class App(val bitmap: ImageBitmap) : AlbumBadge
    data class Vector(val icon: ImageVector) : AlbumBadge
}

@Composable
fun rememberAlbumBadge(album: MediaAlbum?): AlbumBadge? {
    val context = LocalContext.current
    val badge by produceState<AlbumBadge?>(null, album?.id) {
        value = if (album == null) null else withContext(Dispatchers.Default) { AlbumAppIcons.badgeFor(context, album) }
    }
    return badge
}

/** Small round app icon in the corner of an album cover. Pops in with a springy little spin. */
@Composable
fun AlbumAppBadge(album: MediaAlbum, modifier: Modifier = Modifier) {
    val badge = rememberAlbumBadge(album) ?: return

    val scale = remember(album.id) { Animatable(0f) }
    val spin = remember(album.id) { Animatable(-70f) }
    LaunchedEffect(album.id, badge) {
        delay((album.id % 6L).toInt() * 40L)
        scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(album.id, badge) {
        delay((album.id % 6L).toInt() * 40L)
        spin.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = spin.value
            }
            .shadow(4.dp, CircleShape)
            .size(32.dp)
            .clip(CircleShape)
            .background(if (badge is AlbumBadge.App) Color.White.copy(alpha = 0.92f) else Color(0xFF1B2433).copy(alpha = 0.78f))
            .axGlassBorder(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (badge) {
            is AlbumBadge.App -> Image(bitmap = badge.bitmap, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape))
            is AlbumBadge.Vector -> Icon(badge.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
