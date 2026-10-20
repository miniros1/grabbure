package com.miniros1.grabbure

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** Directory for rosbag files. The bag writer needs a normal filesystem path. */
object BagFolder {
    private const val PREFS = "grabbure_bags"
    private const val KEY_DIR = "dir"

    fun directory(context: Context): File {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)
        if (!saved.isNullOrBlank()) {
            val chosen = File(saved)
            if (chosen.isDirectory || chosen.mkdirs()) {
                if (chosen.canWrite()) return chosen
            }
        }
        val fallback = File(context.getExternalFilesDir(null), "bags")
        fallback.mkdirs()
        return fallback
    }

    fun save(context: Context, directory: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DIR, directory.absolutePath)
            .apply()
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
