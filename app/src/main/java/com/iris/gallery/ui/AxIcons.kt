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

    val Sun: ImageVector by lazy {
        line("AxSun") {
            circle(12f, 12f, 4.2f)
            moveTo(19.4f, 12f); lineTo(21.8f, 12f)
            moveTo(17.23f, 17.23f); lineTo(18.93f, 18.93f)
            moveTo(12f, 19.4f); lineTo(12f, 21.8f)
            moveTo(6.77f, 17.23f); lineTo(5.07f, 18.93f)
            moveTo(4.6f, 12f); lineTo(2.2f, 12f)
            moveTo(6.77f, 6.77f); lineTo(5.07f, 5.07f)
            moveTo(12f, 4.6f); lineTo(12f, 2.2f)
            moveTo(17.23f, 6.77f); lineTo(18.93f, 5.07f)
        }
    }

    val SunDim: ImageVector by lazy {
        line("AxSunDim") {
            circle(12f, 12f, 3f)
            moveTo(16.53f, 16.53f); lineTo(17.8f, 17.8f)
            moveTo(7.47f, 16.53f); lineTo(6.2f, 17.8f)
            moveTo(7.47f, 7.47f); lineTo(6.2f, 6.2f)
            moveTo(16.53f, 7.47f); lineTo(17.8f, 6.2f)
        }
    }

    val SkipBack: ImageVector by lazy {
        line("AxSkipBack") {
            moveTo(12.5f, 6.5f); lineTo(7f, 12f); lineTo(12.5f, 17.5f)
            moveTo(19f, 6.5f); lineTo(13.5f, 12f); lineTo(19f, 17.5f)
        }
    }

    val SkipForward: ImageVector by lazy {
        line("AxSkipForward") {
            moveTo(11.5f, 6.5f); lineTo(17f, 12f); lineTo(11.5f, 17.5f)
            moveTo(5f, 6.5f); lineTo(10.5f, 12f); lineTo(5f, 17.5f)
        }
    }

    val Check: ImageVector by lazy { line("AxCheck") { moveTo(5f, 12.8f); lineTo(9.8f, 17.4f); lineTo(19f, 7.2f) } }

    val Plus: ImageVector by lazy { line("AxPlus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }

    val ChevronRight: ImageVector by lazy { line("AxChevronRight") { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) } }

    val ChevronLeft: ImageVector by lazy { line("AxChevronLeft") { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) } }

    val ArrowUp: ImageVector by lazy { line("AxArrowUp") { moveTo(12f, 19.5f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) } }

    val ArrowDown: ImageVector by lazy { line("AxArrowDown") { moveTo(12f, 4.5f); lineTo(12f, 19f); moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f) } }

    val Copy: ImageVector by lazy {
        line("AxCopy") {
            box(8.5f, 8.5f, 11f, 11f, 2.6f)
            moveTo(15.5f, 8.5f); lineTo(15.5f, 6.6f)
            curveTo(15.5f, 5.4f, 14.6f, 4.5f, 13.4f, 4.5f); lineTo(6.6f, 4.5f)
            curveTo(5.4f, 4.5f, 4.5f, 5.4f, 4.5f, 6.6f); lineTo(4.5f, 13.4f)
            curveTo(4.5f, 14.6f, 5.4f, 15.5f, 6.6f, 15.5f); lineTo(8.5f, 15.5f)
        }
    }

    private fun PathBuilder.folderBody() {
        moveTo(3.5f, 7.5f)
        curveTo(3.5f, 6.1f, 4.6f, 5f, 6f, 5f); lineTo(9.6f, 5f); lineTo(11.6f, 7.3f); lineTo(18f, 7.3f)
        curveTo(19.4f, 7.3f, 20.5f, 8.4f, 20.5f, 9.8f); lineTo(20.5f, 17f)
        curveTo(20.5f, 18.4f, 19.4f, 19.5f, 18f, 19.5f); lineTo(6f, 19.5f)
        curveTo(4.6f, 19.5f, 3.5f, 18.4f, 3.5f, 17f); close()
    }

    val Folder: ImageVector by lazy { line("AxFolder") { folderBody() } }

    val FolderOpen: ImageVector by lazy { line("AxFolderOpen") { folderBody(); moveTo(3.8f, 11f); lineTo(20.2f, 11f) } }

    val FolderOff: ImageVector by lazy { line("AxFolderOff") { folderBody(); moveTo(4f, 4f); lineTo(20f, 20f) } }

    val MoveTo: ImageVector by lazy {
        line("AxMoveTo") {
            folderBody()
            moveTo(9.5f, 13.3f); lineTo(15.5f, 13.3f)
            moveTo(13.3f, 11f); lineTo(15.7f, 13.3f); lineTo(13.3f, 15.6f)
        }
    }

    val Shield: ImageVector by lazy {
        line("AxShield") {
            moveTo(12f, 3.6f)
            curveTo(14.8f, 5.6f, 17.4f, 6.4f, 19.6f, 6.6f); lineTo(19.6f, 12f)
            curveTo(19.6f, 16.2f, 16.6f, 19.2f, 12f, 20.6f)
            curveTo(7.4f, 19.2f, 4.4f, 16.2f, 4.4f, 12f); lineTo(4.4f, 6.6f)
            curveTo(6.6f, 6.4f, 9.2f, 5.6f, 12f, 3.6f); close()
            moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15.2f, 10f)
        }
    }

    val Bell: ImageVector by lazy {
        line("AxBell") {
            moveTo(6.2f, 16.8f); lineTo(6.2f, 11f)
            curveTo(6.2f, 7.9f, 8.7f, 5.4f, 12f, 5.4f); curveTo(15.3f, 5.4f, 17.8f, 7.9f, 17.8f, 11f)
            lineTo(17.8f, 16.8f); lineTo(19.5f, 18.4f); lineTo(4.5f, 18.4f); close()
            moveTo(10.2f, 20.8f); curveTo(10.7f, 21.5f, 11.3f, 21.8f, 12f, 21.8f); curveTo(12.7f, 21.8f, 13.3f, 21.5f, 13.8f, 20.8f)
        }
    }

    val Camera: ImageVector by lazy {
        line("AxCamera") {
            box(3.5f, 7f, 17f, 12.5f, 3.4f)
            circle(12f, 13.2f, 3.3f)
            moveTo(8.6f, 7f); lineTo(9.8f, 4.8f); lineTo(14.2f, 4.8f); lineTo(15.4f, 7f)
        }
    }

    val Video: ImageVector by lazy {
        line("AxVideo") {
            box(3.5f, 6.5f, 12.5f, 11f, 3f)
            moveTo(16f, 10.6f); lineTo(20.5f, 8f); lineTo(20.5f, 16f); lineTo(16f, 13.4f)
        }
    }

    val Sliders: ImageVector by lazy {
        line("AxSliders") {
            moveTo(4.5f, 7f); lineTo(6.6f, 7f); moveTo(11.4f, 7f); lineTo(19.5f, 7f); circle(9f, 7f, 2.4f)
            moveTo(4.5f, 12f); lineTo(12.6f, 12f); moveTo(17.4f, 12f); lineTo(19.5f, 12f); circle(15f, 12f, 2.4f)
            moveTo(4.5f, 17f); lineTo(5.6f, 17f); moveTo(10.4f, 17f); lineTo(19.5f, 17f); circle(8f, 17f, 2.4f)
        }
    }

    val SelectAll: ImageVector by lazy {
        line("AxSelectAll") {
            box(4f, 4f, 16f, 16f, 3.4f)
            moveTo(8.4f, 12.2f); lineTo(11f, 14.8f); lineTo(15.8f, 9.6f)
        }
    }

    val Pin: ImageVector by lazy {
        line("AxPin") {
            moveTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(14.2f, 10f)
            curveTo(16f, 11f, 17f, 12.4f, 17f, 13.8f); lineTo(7f, 13.8f)
            curveTo(7f, 12.4f, 8f, 11f, 9.8f, 10f); close()
            moveTo(12f, 13.8f); lineTo(12f, 20f)
        }
    }

    private fun PathBuilder.dropPin() {
        moveTo(12f, 20.5f)
        curveTo(12f, 20.5f, 5.5f, 14.6f, 5.5f, 10f); curveTo(5.5f, 6.4f, 8.4f, 3.6f, 12f, 3.6f)
        curveTo(15.6f, 3.6f, 18.5f, 6.4f, 18.5f, 10f); curveTo(18.5f, 14.6f, 12f, 20.5f, 12f, 20.5f); close()
        circle(12f, 10f, 2.4f)
    }

    val Location: ImageVector by lazy { line("AxLocation") { dropPin() } }

    val LocationOff: ImageVector by lazy { line("AxLocationOff") { dropPin(); moveTo(4f, 4f); lineTo(20f, 20f) } }

    val Doc: ImageVector by lazy {
        line("AxDoc") {
            moveTo(7f, 3.8f); lineTo(13.4f, 3.8f); lineTo(18.2f, 8.6f); lineTo(18.2f, 18.8f)
            curveTo(18.2f, 19.7f, 17.5f, 20.4f, 16.6f, 20.4f); lineTo(7.4f, 20.4f)
            curveTo(6.5f, 20.4f, 5.8f, 19.7f, 5.8f, 18.8f); lineTo(5.8f, 5f)
            curveTo(5.8f, 4.3f, 6.3f, 3.8f, 7f, 3.8f); close()
            moveTo(13.2f, 3.9f); lineTo(13.2f, 8.8f); lineTo(18.1f, 8.8f)
            moveTo(9f, 13f); lineTo(15f, 13f); moveTo(9f, 16.4f); lineTo(13f, 16.4f)
        }
    }

    val Palette: ImageVector by lazy {
        line("AxPalette") {
            moveTo(12f, 3.6f)
            curveTo(7.2f, 3.6f, 3.6f, 7.2f, 3.6f, 12f); curveTo(3.6f, 16.8f, 7.2f, 20.4f, 11.6f, 20.4f)
            curveTo(13f, 20.4f, 13.6f, 19.4f, 13f, 18.4f); curveTo(12.4f, 17.4f, 12.9f, 16.2f, 14.2f, 16.2f)
            lineTo(16.4f, 16.2f); curveTo(18.6f, 16.2f, 20.4f, 14.6f, 20.4f, 12f); curveTo(20.4f, 7.2f, 16.8f, 3.6f, 12f, 3.6f); close()
            circle(8f, 10.6f, 1f); circle(11.2f, 7.8f, 1f); circle(15.6f, 8.9f, 1f)
        }
    }

    val Globe: ImageVector by lazy {
        line("AxGlobe") {
            circle(12f, 12f, 8.6f)
            moveTo(3.6f, 12f); lineTo(20.4f, 12f)
            moveTo(12f, 3.4f); curveTo(14.6f, 6f, 15.8f, 9f, 15.8f, 12f); curveTo(15.8f, 15f, 14.6f, 18f, 12f, 20.6f)
            curveTo(9.4f, 18f, 8.2f, 15f, 8.2f, 12f); curveTo(8.2f, 9f, 9.4f, 6f, 12f, 3.4f)
        }
    }

    val Refresh: ImageVector by lazy {
        line("AxRefresh") {
            moveTo(19.6f, 12f); arcTo(7.6f, 7.6f, 0f, true, true, 17f, 6.6f)
            moveTo(17.3f, 3.2f); lineTo(17.3f, 6.8f); lineTo(13.7f, 6.8f)
        }
    }

    val Restore: ImageVector by lazy {
        line("AxRestore") {
            moveTo(4.4f, 12f); arcTo(7.6f, 7.6f, 0f, true, false, 7f, 6.6f)
            moveTo(6.7f, 3.2f); lineTo(6.7f, 6.8f); lineTo(10.3f, 6.8f)
            moveTo(12f, 8.6f); lineTo(12f, 12f); lineTo(14.2f, 13.4f)
        }
    }

    val TrashX: ImageVector by lazy {
        line("AxTrashX") {
            moveTo(4.5f, 7f); lineTo(19.5f, 7f)
            moveTo(9f, 7f); lineTo(9f, 5.3f)
            curveTo(9f, 4.6f, 9.6f, 4f, 10.3f, 4f); lineTo(13.7f, 4f)
            curveTo(14.4f, 4f, 15f, 4.6f, 15f, 5.3f); lineTo(15f, 7f)
            moveTo(6.6f, 7f); lineTo(7.4f, 18.6f)
            curveTo(7.5f, 19.4f, 8.1f, 20f, 8.9f, 20f); lineTo(15.1f, 20f)
            curveTo(15.9f, 20f, 16.5f, 19.4f, 16.6f, 18.6f); lineTo(17.4f, 7f)
            moveTo(10f, 11.2f); lineTo(14f, 15.6f); moveTo(14f, 11.2f); lineTo(10f, 15.6f)
        }
    }

    val PlayCircle: ImageVector by lazy {
        line("AxPlayCircle") {
            circle(12f, 12f, 8.6f)
            moveTo(10.2f, 8.8f); lineTo(15.4f, 12f); lineTo(10.2f, 15.2f); close()
        }
    }

    val Code: ImageVector by lazy {
        line("AxCode") {
            moveTo(8.5f, 7.5f); lineTo(4f, 12f); lineTo(8.5f, 16.5f)
            moveTo(15.5f, 7.5f); lineTo(20f, 12f); lineTo(15.5f, 16.5f)
            moveTo(13.4f, 5.5f); lineTo(10.6f, 18.5f)
        }
    }

    val External: ImageVector by lazy {
        line("AxExternal") {
            moveTo(10.5f, 5.5f); lineTo(7.2f, 5.5f)
            curveTo(6.1f, 5.5f, 5.2f, 6.4f, 5.2f, 7.5f); lineTo(5.2f, 16.8f)
            curveTo(5.2f, 17.9f, 6.1f, 18.8f, 7.2f, 18.8f); lineTo(16.5f, 18.8f)
            curveTo(17.6f, 18.8f, 18.5f, 17.9f, 18.5f, 16.8f); lineTo(18.5f, 13.5f)
            moveTo(13.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 10.5f)
            moveTo(19.3f, 4.7f); lineTo(11.5f, 12.5f)
        }
    }

    val Comment: ImageVector by lazy {
        line("AxComment") {
            moveTo(5.5f, 5f); lineTo(18.5f, 5f)
            curveTo(19.6f, 5f, 20.5f, 5.9f, 20.5f, 7f); lineTo(20.5f, 14.5f)
            curveTo(20.5f, 15.6f, 19.6f, 16.5f, 18.5f, 16.5f); lineTo(11f, 16.5f); lineTo(7f, 20f); lineTo(7f, 16.5f); lineTo(5.5f, 16.5f)
            curveTo(4.4f, 16.5f, 3.5f, 15.6f, 3.5f, 14.5f); lineTo(3.5f, 7f)
            curveTo(3.5f, 5.9f, 4.4f, 5f, 5.5f, 5f); close()
            moveTo(8f, 9.5f); lineTo(16f, 9.5f); moveTo(8f, 12.5f); lineTo(13f, 12.5f)
        }
    }

    val List: ImageVector by lazy {
        line("AxList") {
            moveTo(9.5f, 7f); lineTo(20f, 7f); moveTo(9.5f, 12f); lineTo(20f, 12f); moveTo(9.5f, 17f); lineTo(20f, 17f)
            moveTo(4.6f, 7f); lineTo(4.7f, 7f); moveTo(4.6f, 12f); lineTo(4.7f, 12f); moveTo(4.6f, 17f); lineTo(4.7f, 17f)
        }
    }

    val Storage: ImageVector by lazy {
        line("AxStorage") {
            box(4f, 5f, 16f, 5.6f, 2.4f); box(4f, 13.4f, 16f, 5.6f, 2.4f)
            moveTo(7.5f, 7.8f); lineTo(7.6f, 7.8f); moveTo(7.5f, 16.2f); lineTo(7.6f, 16.2f)
        }
    }
}
