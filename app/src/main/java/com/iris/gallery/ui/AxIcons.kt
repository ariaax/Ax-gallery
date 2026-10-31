package com.iris.gallery.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Ax Gallery's own icon set: thin (1.7) rounded outlines on a 24 grid, one consistent style
 * (modern iOS-like line icons). Drawn as vectors, so they tint and scale like any other icon.
 */
object AxIcons {
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, false, cx + r, cy)
        arcTo(r, r, 0f, true, false, cx - r, cy)
        close()
    }

    private fun PathBuilder.box(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    private fun line(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    val Search: ImageVector by lazy {
        line("AxSearch") {
            circle(10.5f, 10.5f, 6.5f)
            moveTo(15.4f, 15.4f); lineTo(20.2f, 20.2f)
        }
    }

    val Settings: ImageVector by lazy {
        line("AxSettings") {
        moveTo(10.23f, 4.92f)
        lineTo(10.57f, 2.41f)
        lineTo(13.43f, 2.41f)
        lineTo(13.77f, 4.92f)
        lineTo(15.76f, 5.74f)
        lineTo(17.77f, 4.2f)
        lineTo(19.8f, 6.23f)
        lineTo(18.26f, 8.24f)
        lineTo(19.08f, 10.23f)
        lineTo(21.59f, 10.57f)
        lineTo(21.59f, 13.43f)
        lineTo(19.08f, 13.77f)
        lineTo(18.26f, 15.76f)
        lineTo(19.8f, 17.77f)
        lineTo(17.77f, 19.8f)
        lineTo(15.76f, 18.26f)
        lineTo(13.77f, 19.08f)
        lineTo(13.43f, 21.59f)
        lineTo(10.57f, 21.59f)
        lineTo(10.23f, 19.08f)
        lineTo(8.24f, 18.26f)
        lineTo(6.23f, 19.8f)
        lineTo(4.2f, 17.77f)
        lineTo(5.74f, 15.76f)
        lineTo(4.92f, 13.77f)
        lineTo(2.41f, 13.43f)
        lineTo(2.41f, 10.57f)
        lineTo(4.92f, 10.23f)
        lineTo(5.74f, 8.24f)
        lineTo(4.2f, 6.23f)
        lineTo(6.23f, 4.2f)
        lineTo(8.24f, 5.74f)
        close()
        circle(12f, 12f, 3.1f)
        }
    }

    /** Six rounded petals: the options menu. */
    val Flower: ImageVector by lazy {
        line("AxFlower") {
        circle(12f, 6.6f, 2.6f)
        circle(16.68f, 9.3f, 2.6f)
        circle(16.68f, 14.7f, 2.6f)
        circle(12f, 17.4f, 2.6f)
        circle(7.32f, 14.7f, 2.6f)
        circle(7.32f, 9.3f, 2.6f)
        }
    }

    /** 2x2 tiles. */
    val GridSmall: ImageVector by lazy {
        line("AxGridSmall") {
            box(3.8f, 3.8f, 6.8f, 6.8f, 2.2f); box(13.4f, 3.8f, 6.8f, 6.8f, 2.2f)
            box(3.8f, 13.4f, 6.8f, 6.8f, 2.2f); box(13.4f, 13.4f, 6.8f, 6.8f, 2.2f)
        }
    }

    /** One big tile and two small ones. */
    val GridBig: ImageVector by lazy {
        line("AxGridBig") {
            box(3.8f, 3.8f, 9.4f, 16.4f, 2.6f)
            box(15.2f, 3.8f, 5f, 7.4f, 2.2f); box(15.2f, 12.8f, 5f, 7.4f, 2.2f)
        }
    }

    val Sort: ImageVector by lazy {
        line("AxSort") {
            moveTo(4.5f, 7f); lineTo(19.5f, 7f)
            moveTo(4.5f, 12f); lineTo(15f, 12f)
            moveTo(4.5f, 17f); lineTo(10.5f, 17f)
        }
    }

    val Photos: ImageVector by lazy {
        line("AxPhotos") {
            box(3.5f, 4.5f, 17f, 15f, 4f)
            circle(8.6f, 9.6f, 1.5f)
            moveTo(4f, 17.2f); lineTo(9.2f, 12.8f); lineTo(13f, 16f); lineTo(15.4f, 14f); lineTo(20f, 18f)
        }
    }

    val Albums: ImageVector by lazy {
        line("AxAlbums") {
            box(3.5f, 8f, 17f, 12.5f, 3.6f)
            moveTo(7f, 4.6f); lineTo(17f, 4.6f)
            circle(9f, 13f, 1.3f)
            moveTo(4f, 18.2f); lineTo(9.6f, 14.4f); lineTo(13.4f, 17f); lineTo(16f, 15f); lineTo(20f, 18f)
        }
    }

    val Heart: ImageVector by lazy {
        line("AxHeart") {
            moveTo(12f, 20.4f)
            curveTo(5f, 15.5f, 3f, 12f, 3f, 8.8f)
            curveTo(3f, 6.2f, 5f, 4.5f, 7.3f, 4.5f)
            curveTo(9.3f, 4.5f, 11f, 5.6f, 12f, 7.3f)
            curveTo(13f, 5.6f, 14.7f, 4.5f, 16.7f, 4.5f)
            curveTo(19f, 4.5f, 21f, 6.2f, 21f, 8.8f)
            curveTo(21f, 12f, 19f, 15.5f, 12f, 20.4f)
            close()
        }
    }

    /** Four-point sparkle: the library. */
    val Sparkle: ImageVector by lazy {
        line("AxSparkle") {
            moveTo(11f, 4f)
            curveTo(11.8f, 9.2f, 14.6f, 12f, 19.5f, 12.8f)
            curveTo(14.6f, 13.6f, 11.8f, 16.4f, 11f, 21.4f)
            curveTo(10.2f, 16.4f, 7.4f, 13.6f, 2.5f, 12.8f)
            curveTo(7.4f, 12f, 10.2f, 9.2f, 11f, 4f)
            close()
            moveTo(19f, 3.4f); lineTo(19f, 7f)
            moveTo(17.2f, 5.2f); lineTo(20.8f, 5.2f)
        }
    }

    val Share: ImageVector by lazy {
        line("AxShare") {
            moveTo(12f, 14.6f); lineTo(12f, 3.8f)
            moveTo(8.2f, 7.4f); lineTo(12f, 3.6f); lineTo(15.8f, 7.4f)
            moveTo(8f, 10f); lineTo(7f, 10f)
            curveTo(5.9f, 10f, 5f, 10.9f, 5f, 12f)
            lineTo(5f, 17.8f)
            curveTo(5f, 19f, 6f, 20f, 7.2f, 20f)
            lineTo(16.8f, 20f)
            curveTo(18f, 20f, 19f, 19f, 19f, 17.8f)
            lineTo(19f, 12f)
            curveTo(19f, 10.9f, 18.1f, 10f, 17f, 10f)
            lineTo(16f, 10f)
        }
    }

    val Edit: ImageVector by lazy {
        line("AxEdit") {
            moveTo(4.5f, 19.5f); lineTo(5.3f, 15.6f); lineTo(15.8f, 5.1f)
            curveTo(16.6f, 4.3f, 17.9f, 4.3f, 18.7f, 5.1f)
            lineTo(18.9f, 5.3f)
            curveTo(19.7f, 6.1f, 19.7f, 7.4f, 18.9f, 8.2f)
            lineTo(8.4f, 18.7f)
            close()
            moveTo(13.5f, 7.4f); lineTo(16.6f, 10.5f)
        }
    }

    val Lock: ImageVector by lazy {
        line("AxLock") {
            box(5f, 10.5f, 14f, 9.8f, 3f)
            moveTo(8f, 10.5f); lineTo(8f, 8f)
            curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
            curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f)
            lineTo(16f, 10.5f)
            moveTo(12f, 14.4f); lineTo(12f, 16.4f)
        }
    }

    val LockOpen: ImageVector by lazy {
        line("AxLockOpen") {
            box(5f, 10.5f, 14f, 9.8f, 3f)
            moveTo(8f, 10.5f); lineTo(8f, 8f)
            curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
            curveTo(13.8f, 4f, 15.3f, 5.1f, 15.8f, 6.7f)
            moveTo(12f, 14.4f); lineTo(12f, 16.4f)
        }
    }

    val Trash: ImageVector by lazy {
        line("AxTrash") {
            moveTo(4.5f, 7f); lineTo(19.5f, 7f)
            moveTo(9f, 7f); lineTo(9f, 5.3f)
            curveTo(9f, 4.6f, 9.6f, 4f, 10.3f, 4f)
            lineTo(13.7f, 4f)
            curveTo(14.4f, 4f, 15f, 4.6f, 15f, 5.3f)
            lineTo(15f, 7f)
            moveTo(6.6f, 7f); lineTo(7.4f, 18.6f)
            curveTo(7.5f, 19.4f, 8.1f, 20f, 8.9f, 20f)
            lineTo(15.1f, 20f)
            curveTo(15.9f, 20f, 16.5f, 19.4f, 16.6f, 18.6f)
            lineTo(17.4f, 7f)
            moveTo(10f, 11f); lineTo(10f, 16f)
            moveTo(14f, 11f); lineTo(14f, 16f)
        }
    }

    val Back: ImageVector by lazy {
        line("AxBack") {
            moveTo(19.5f, 12f); lineTo(5f, 12f)
            moveTo(11f, 5.8f); lineTo(4.8f, 12f); lineTo(11f, 18.2f)
        }
    }

    val Close: ImageVector by lazy {
        line("AxClose") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val Info: ImageVector by lazy {
        line("AxInfo") {
            circle(12f, 12f, 8.6f)
            moveTo(12f, 11f); lineTo(12f, 16.4f)
            moveTo(12f, 7.8f); lineTo(12f, 7.9f)
        }
    }

    val Play: ImageVector by lazy {
        line("AxPlay") {
            moveTo(8.4f, 5.8f); lineTo(18.4f, 12f); lineTo(8.4f, 18.2f); close()
        }
    }

    val Pause: ImageVector by lazy {
        line("AxPause") {
            box(6.6f, 5.6f, 3.8f, 12.8f, 1.6f); box(13.6f, 5.6f, 3.8f, 12.8f, 1.6f)
        }
    }

    private fun PathBuilder.speaker() {
        moveTo(3.8f, 9.6f); lineTo(7.4f, 9.6f); lineTo(11.8f, 5.8f); lineTo(11.8f, 18.2f)
        lineTo(7.4f, 14.4f); lineTo(3.8f, 14.4f); close()
    }

    val Volume: ImageVector by lazy {
        line("AxVolume") {
            speaker()
            moveTo(15.2f, 9.2f); curveTo(16.5f, 10.5f, 16.5f, 13.5f, 15.2f, 14.8f)
            moveTo(17.9f, 6.7f); curveTo(20.6f, 9.6f, 20.6f, 14.4f, 17.9f, 17.3f)
        }
    }

    val VolumeOff: ImageVector by lazy {
        line("AxVolumeOff") {
            speaker()
            moveTo(15.6f, 9.6f); lineTo(20.4f, 14.4f)
            moveTo(20.4f, 9.6f); lineTo(15.6f, 14.4f)
        }
    }

    private fun PathBuilder.loopArrows() {
        moveTo(17f, 3.6f); lineTo(20f, 6.6f); lineTo(17f, 9.6f)
        moveTo(4f, 11.4f); lineTo(4f, 10.6f)
        curveTo(4f, 8.4f, 5.8f, 6.6f, 8f, 6.6f); lineTo(20f, 6.6f)
        moveTo(7f, 20.4f); lineTo(4f, 17.4f); lineTo(7f, 14.4f)
        moveTo(20f, 12.6f); lineTo(20f, 13.4f)
        curveTo(20f, 15.6f, 18.2f, 17.4f, 16f, 17.4f); lineTo(4f, 17.4f)
    }

    val Repeat: ImageVector by lazy { line("AxRepeat") { loopArrows() } }

    val RepeatOne: ImageVector by lazy {
        line("AxRepeatOne") {
            loopArrows()
            moveTo(10.9f, 10.9f); lineTo(12.3f, 9.9f); lineTo(12.3f, 14.2f)
        }
    }

    val Rotate: ImageVector by lazy {
        line("AxRotate") {
            box(8f, 6.5f, 8f, 11f, 2f)
            moveTo(15.5f, 3.4f); curveTo(18.6f, 4.2f, 20.6f, 6.9f, 20.6f, 10f)
            moveTo(18.8f, 8.3f); lineTo(20.6f, 10.2f); lineTo(22.2f, 8.5f)
            moveTo(8.5f, 20.6f); curveTo(5.4f, 19.8f, 3.4f, 17.1f, 3.4f, 14f)
            moveTo(5.2f, 15.7f); lineTo(3.4f, 13.8f); lineTo(1.8f, 15.5f)
        }
    }
}
