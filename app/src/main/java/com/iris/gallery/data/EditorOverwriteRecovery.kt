package com.iris.gallery.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Persistent safety copies for in-place editor writes. The recovery marker is committed before the
 * destination is opened for writing; startup restores any operation that did not reach commit.
 */
object EditorOverwriteRecovery {
    val lock = Any()
    private const val TAG = "EditorOverwriteRecovery"

    data class Handle(val uri: Uri, val backup: File, val marker: File)

    private fun directory(context: Context) = File(context.filesDir, "editor_overwrite_recovery").apply { mkdirs() }

    private fun key(stableKey: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(stableKey.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun prepare(context: Context, stableKey: String, uri: Uri, expectedSize: Long, expectedHash: String): Handle? = synchronized(lock) {
        val dir = directory(context)
        val base = key(stableKey)
        val backup = File(dir, "$base.bak")
        val marker = File(dir, "$base.uri")

        // A previous write was interrupted. Restore it before allowing another edit to this row.
        if (marker.exists()) {
            val previous = runCatching { marker.readText(Charsets.UTF_8).lines() }.getOrNull() ?: return null
            val previousUri = previous.firstOrNull()?.let { Uri.parse(it) } ?: return null
            val previousHash = previous.getOrNull(1).orEmpty()
            if (previousHash.isNotBlank() && currentMatches(context, previousUri, previousHash)) {
                if (!marker.delete() && marker.exists()) return null
                backup.delete()
            } else {
                if (!restore(context, previousUri, backup)) return null
                if (!marker.delete() && marker.exists()) return null
                backup.delete()
            }
        } else if (backup.exists()) {
            // No marker means the destination was never touched; this is an orphaned pre-commit copy.
            backup.delete()
        }

        val source = runCatching {
            if (uri.scheme == "file") uri.path?.let(::File)?.takeIf { it.isFile }?.inputStream()
            else context.contentResolver.openInputStream(uri)
        }.getOrNull()
        if (!VaultFiles.copyStreamVerified(source, backup, expectedSize)) return null

        val markerTemp = File(dir, "$base.uri.tmp")
        return try {
            FileOutputStream(markerTemp).use { out ->
                out.write("${uri}\n$expectedHash".toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!markerTemp.renameTo(marker)) {
                backup.delete()
                markerTemp.delete()
                null
            } else Handle(uri, backup, marker)
        } catch (error: Exception) {
            markerTemp.delete()
            backup.delete()
            Log.e(TAG, "Could not commit editor recovery marker", error)
            null
        }
    }

    /** Restores every marker left by a process death. Failed restores keep both marker and backup. */
    fun recover(context: Context) = synchronized(lock) {
        val dir = directory(context)
        dir.listFiles { file -> file.name.endsWith(".uri") }?.forEach { marker ->
            val backup = File(dir, marker.name.removeSuffix(".uri") + ".bak")
            val lines = runCatching { marker.readText(Charsets.UTF_8).lines() }.getOrNull().orEmpty()
            val uri = lines.firstOrNull()?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val expectedHash = lines.getOrNull(1).orEmpty()
            val recovered = uri != null && (
                (expectedHash.isNotBlank() && currentMatches(context, uri, expectedHash)) || restore(context, uri, backup)
            )
            if (recovered) {
                if (marker.delete() || !marker.exists()) backup.delete()
            } else {
                Log.e(TAG, "Preserving editor recovery backup: ${backup.name}")
            }
        }
    }

    fun rollback(context: Context, handle: Handle): Boolean = synchronized(lock) {
        if (!restore(context, handle.uri, handle.backup)) return false
        if (!handle.marker.delete() && handle.marker.exists()) return false
        handle.backup.delete()
        true
    }

    fun commit(handle: Handle) = synchronized(lock) {
        if (handle.marker.delete() || !handle.marker.exists()) handle.backup.delete()
    }

    private fun currentMatches(context: Context, uri: Uri, expectedHash: String): Boolean = runCatching {
        val input = if (uri.scheme == "file") uri.path?.let(::File)?.inputStream()
            else context.contentResolver.openInputStream(uri)
        val digest = MessageDigest.getInstance("SHA-256")
        input?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        } ?: return false
        digest.digest().joinToString("") { "%02x".format(it) } == expectedHash
    }.getOrDefault(false)

    private fun restore(context: Context, uri: Uri, backup: File): Boolean {
        if (!backup.isFile) return false
        return if (uri.scheme == "file") {
            val target = uri.path?.let(::File) ?: return false
            val parent = target.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false
            val temp = File(parent, target.name + ".editor-restore.part")
            val old = File(parent, target.name + ".editor-restore.old")
            runCatching { temp.delete() }
            runCatching { old.delete() }
            if (!VaultFiles.copyStreamVerified(backup.inputStream(), temp, backup.length())) return false
            if (target.exists() && !target.renameTo(old)) { temp.delete(); return false }
            if (!temp.renameTo(target)) {
                if (old.exists()) old.renameTo(target)
                temp.delete()
                return false
            }
            val verified = TrashFiles.streamMatchesFile(target.inputStream(), backup)
            if (verified) old.delete() else if (old.exists()) {
                target.delete()
                old.renameTo(target)
            }
            verified
        } else {
            val output = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: return false
            if (!VaultFiles.copyFileToStream(backup, output)) return false
            TrashFiles.streamMatchesFile(runCatching { context.contentResolver.openInputStream(uri) }.getOrNull(), backup)
        }
    }
}
