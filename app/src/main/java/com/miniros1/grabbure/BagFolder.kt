package com.miniros1.grabbure

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Directory for rosbag files. The bag writer needs a normal filesystem path.
 * The place to open afterwards is `Movies/grabbure` on internal storage, next to
 * other recordings. Recording writes there directly when that folder is writable.
 */
object BagFolder {
    private const val PREFS = "grabbure_bags"
    private const val KEY_DIR = "dir"

    fun directory(context: Context): File {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)
        if (!saved.isNullOrBlank()) {
            val chosen = File(saved)
            if ((chosen.isDirectory || chosen.mkdirs()) && chosen.canWrite()) return chosen
        }
        return defaultDirectory(context)
    }

    private fun defaultDirectory(context: Context): File {
        val movies = moviesDirectory()
        if ((movies.isDirectory || movies.mkdirs()) && movies.canWrite()) return movies
        val shared = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/${context.packageName}/bags",
        )
        if ((shared.isDirectory || shared.mkdirs()) && shared.canWrite()) return shared
        val fallback = File(context.getExternalFilesDir(null), "bags")
        fallback.mkdirs()
        return fallback
    }

    private fun moviesDirectory(): File {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "grabbure")
    }

    /** Folder the user should open after a recording. A custom Storage choice is kept. */
    fun publishedDirectory(context: Context): File {
        if (customDirectory(context) != null) return directory(context)
        return moviesDirectory()
    }

    /**
     * Rename [source] into [publishedDirectory]. Same-volume rename does not copy the bytes.
     * Returns the file that exists afterwards.
     */
    fun publish(context: Context, source: File): File {
        if (!source.isFile || customDirectory(context) != null) return source
        val destDir = publishedDirectory(context)
        val dest = File(destDir, source.name)
        if (dest.absolutePath == source.absolutePath) return source
        if ((destDir.isDirectory || destDir.mkdirs()) && destDir.canWrite() && source.renameTo(dest)) {
            return dest
        }
        if (Build.VERSION.SDK_INT >= 29 && moveWithMediaStore(context, source, dest)) {
            return dest
        }
        return source
    }

    fun save(context: Context, directory: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DIR, directory.absolutePath)
            .apply()
    }

    private fun customDirectory(context: Context): File? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)
        if (saved.isNullOrBlank()) return null
        val chosen = File(saved)
        return if (chosen.canWrite()) chosen else null
    }

    /** Ask the media provider to rename the file into Movies. The provider does the rename. */
    private fun moveWithMediaStore(context: Context, source: File, dest: File): Boolean {
        val uri = scan(context, source) ?: return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/grabbure")
        }
        val updated = try {
            context.contentResolver.update(uri, values, null, null)
        } catch (_: SecurityException) {
            0
        } catch (_: IllegalArgumentException) {
            0
        }
        if (updated <= 0 || !dest.isFile) return false
        if (source.isFile && dest.length() == source.length()) source.delete()
        return true
    }

    private fun scan(context: Context, file: File): Uri? {
        val ready = CountDownLatch(1)
        var found: Uri? = null
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("application/octet-stream"),
        ) { _, uri ->
            found = uri
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        return found
    }

    /** Maps a primary or volume tree URI to `/storage/...`. Null when Android will not give a path. */
    fun pathForTree(uri: Uri): File? {
        val document = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val split = document.split(":", limit = 2)
        if (split.size != 2 || split[0].isBlank()) return null
        val root = if (split[0] == "primary") {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/${split[0]}")
        }
        val relative = split[1].trim('/')
        return if (relative.isEmpty()) root else File(root, relative)
    }
}
