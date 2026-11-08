package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.iris.gallery.data.CleanupGroup
import com.iris.gallery.data.CleanupKind
import com.iris.gallery.data.MediaImage
import com.iris.gallery.data.TripGroup
import java.text.DateFormat
import java.util.Date

private fun shortDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

/** Trips list: each card opens its photos in the viewer. Nothing is changed here. */
@Composable
fun TripsScreen(
    trips: List<TripGroup>,
    padding: PaddingValues,
    onOpen: (TripGroup) -> Unit,
) {
    val layoutDir = LocalLayoutDirection.current
    LazyColumn(
        Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(layoutDir), top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDir),
        ),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 12.dp + padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (trips.isEmpty()) {
            item {
                Text("No trips found yet. A trip needs at least 8 photos taken close in time and place.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        }
        items(trips, key = { it.start }) { trip ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(trip) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (shortDate(trip.start) == shortDate(trip.end)) shortDate(trip.start)
                        else "${shortDate(trip.start)} – ${shortDate(trip.end)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    Text("${trip.items.size} photos and videos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(trip.items.take(12), key = { it.id }) { media ->
                            MediaThumbnail(media, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cleanup SUGGESTIONS. Nothing is preselected. Items the user selects go to the trash
 * through [onTrash], the same confirmed, restorable flow used everywhere else.
 */
@Composable
fun CleanupScreen(
    groups: List<CleanupGroup>,
    padding: PaddingValues,
    onOpen: (CleanupGroup, MediaImage) -> Unit,
    onTrash: (List<MediaImage>) -> Unit,
) {
    var selectedIds by remember(groups) { mutableStateOf(emptySet<Long>()) }
    val layoutDir = LocalLayoutDirection.current
    val all = groups.flatMap { it.items }
    val selected = all.filter { it.id in selectedIds }
    LazyColumn(
        Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(layoutDir), top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDir),
        ),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 12.dp + padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cleanup suggestions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Nothing is removed automatically. Select what you want, then move it to Trash. You can restore it from Trash.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${selected.size} selected · ${sizeLabel(selected.sumOf { it.sizeBytes })}",
                            modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Button(enabled = selected.isNotEmpty(), onClick = { onTrash(selected) }) { Text("Move to Trash") }
                    }
                }
            }
        }
        if (groups.isEmpty()) {
            item { Text("Nothing to clean up right now.", modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleMedium) }
        }
        items(groups, key = { it.kind }) { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (group.kind == CleanupKind.SCREENSHOTS) "Screenshots" else "Large videos",
                            fontWeight = FontWeight.SemiBold)
                        Text("${group.items.size} · ${sizeLabel(group.items.sumOf { it.sizeBytes })}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = {
                        val ids = group.items.map { it.id }
                        selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids.toSet() else selectedIds + ids
                    }, modifier = Modifier.padding(horizontal = 6.dp)) {
                        Text(if (group.items.all { it.id in selectedIds }) "Clear this group" else "Select this group")
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(group.items, key = { it.id }) { media ->
                            val isSelected = media.id in selectedIds
                            Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).clickable {
                                selectedIds = if (isSelected) selectedIds - media.id else selectedIds + media.id
                            }) {
                                MediaThumbnail(media, Modifier.fillMaxSize())
                                Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(50),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
                                    Icon(Icons.Filled.CheckCircle, null, Modifier.padding(5.dp).size(18.dp),
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                                TextButton(onClick = { onOpen(group, media) },
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .82f))) { Text("Preview") }
                            }
                        }
                    }
                }
            }
        }
    }
}
