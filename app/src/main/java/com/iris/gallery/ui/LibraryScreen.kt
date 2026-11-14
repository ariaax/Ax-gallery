package com.iris.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import androidx.compose.ui.res.stringResource
import com.iris.gallery.R

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    trashCount: Int,
    lockedCount: Int,
    videoCount: Int = 0,
    onOpen: (String) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = padding.calculateStartPadding(layoutDirection)
    val endPadding = padding.calculateEndPadding(layoutDirection)
    val topPadding = padding.calculateTopPadding()
    val bottomPadding = padding.calculateBottomPadding()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(start = startPadding, top = topPadding, end = endPadding, bottom = 0.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.library_header),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        // Bento grid: tiles of different widths on dark glass, two per row.
        item { BentoRow(
            BentoSpec(AxIcons.Video, stringResource(R.string.section_videos), stringResource(R.string.section_videos_desc, videoCount), 1.45f, hero = true) { onOpen("videos") },
            BentoSpec(AxIcons.Sparkle, stringResource(R.string.library_memories_title), stringResource(R.string.library_memories_subtitle), 1f) { onOpen("memories") },
        ) }
        item { BentoRow(
            BentoSpec(AxIcons.Copy, stringResource(R.string.library_duplicates_title), stringResource(R.string.library_duplicates_subtitle), 1f) { onOpen("duplicates") },
            BentoSpec(AxIcons.Lock, stringResource(R.string.library_locked_title), stringResource(R.string.library_locked_subtitle, lockedCount), 1f) { onOpen("locked") },
        ) }
        // wide tiles on their own row so their titles never wrap letter by letter
        item { BentoRow(
            BentoSpec(AxIcons.Folder, "Trips", "Grouped by time", 1.45f) { onOpen("trips") },
            BentoSpec(AxIcons.Trash, "Cleanup", "Review before removing", 1.45f) { onOpen("cleanup") },
        ) }
        item { BentoRow(
            BentoSpec(AxIcons.Trash, stringResource(R.string.library_trash_title), stringResource(R.string.library_trash_subtitle, trashCount), 1f) { onOpen("trash") },
            BentoSpec(AxIcons.Edit, stringResource(R.string.library_editor_title), stringResource(R.string.library_editor_subtitle), 1.45f, hero = true) { onOpen("editor") },
        ) }
        item { BentoRow(
            BentoSpec(AxIcons.GridSmall, stringResource(R.string.library_smart_title), stringResource(R.string.library_smart_subtitle), 1.45f) { onOpen("smart") },
            BentoSpec(AxIcons.Photos, stringResource(R.string.library_formats_title), stringResource(R.string.library_formats_subtitle), 1f) { onOpen("formats") },
        ) }
        item { BentoRow(
            BentoSpec(AxIcons.Folder, stringResource(R.string.section_folder_view), stringResource(R.string.section_folder_view_desc), 1f) { onOpen("folder_view") },
            BentoSpec(AxIcons.FolderOff, stringResource(R.string.excluded_folders_title), stringResource(R.string.excluded_folders_desc), 1.45f) { onOpen("excluded_folders") },
        ) }
        item { BentoRow(
            BentoSpec(AxIcons.Refresh, stringResource(R.string.rescan_media_title), stringResource(R.string.rescan_media_subtitle), 1f) { onOpen("rescan") },
        ) }
    }
}

private class BentoSpec(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val weight: Float,
    val hero: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun BentoRow(vararg tiles: BentoSpec) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (t in tiles) {
            BentoTile(t, Modifier.weight(t.weight).fillMaxHeight())
        }
    }
}

@Composable
private fun BentoTile(spec: BentoSpec, modifier: Modifier) {
    val shape = com.iris.gallery.ui.AxRound(26.dp)
    val accent = MaterialTheme.colorScheme.primary
    val fill = remember { Color(0xFF14161B).copy(alpha = 0.92f) }
    val rim = remember {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.10f)))
    }
    Column(
        modifier
            .heightIn(min = 124.dp)
            .clip(shape)
            .background(fill)
            .drawBehind {
                // a soft accent glow in the corner; the hero tiles glow stronger
                val k = if (spec.hero) 0.30f else 0.14f
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = k), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.maxDimension * 0.9f,
                    )
                )
            }
            .border(1.dp, rim, shape)
            .clickable(onClick = spec.onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(com.iris.gallery.ui.AxRound(14.dp))
                .background(accent.copy(alpha = if (spec.hero) 0.30f else 0.18f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), com.iris.gallery.ui.AxRound(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(spec.icon, null, tint = if (spec.hero) Color.White else accent)
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2)
            Text(spec.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.62f), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
