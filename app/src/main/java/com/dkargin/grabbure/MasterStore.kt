package com.dkargin.grabbure

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Manual masters and the favorite master, kept across launches. */
class MasterStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Saved(val uri: String, val label: String, val manual: Boolean)

    fun saved(): List<Saved> {
        val raw = prefs.getString(KEY_MASTERS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                add(Saved(uri, item.optString("label"), item.optBoolean("manual")))
            }
        }
    }

    fun favoriteUri(): String? = prefs.getString(KEY_FAVORITE, null)?.ifBlank { null }

    fun isSaved(uri: String): Boolean = saved().any { it.uri == uri }

    fun addManual(uri: String, label: String) {
        val next = saved().filter { it.uri != uri } + Saved(uri, label, true)
        write(next)
    }

    /** Remembers this master and lists it first. A previous non-manual favorite is dropped. */
    fun setFavorite(uri: String, label: String) {
        val kept = saved().filter { it.manual || it.uri == uri }
        val next = if (kept.any { it.uri == uri }) {
            kept.map { saved ->
                if (saved.uri == uri && label.isNotBlank()) saved.copy(label = label) else saved
            }
        } else {
            kept + Saved(uri, label, false)
        }
        write(next)
        prefs.edit().putString(KEY_FAVORITE, uri).apply()
    }

    fun clearFavorite() {
        val uri = favoriteUri()
        prefs.edit().remove(KEY_FAVORITE).apply()
        if (uri != null) write(saved().filter { it.manual || it.uri != uri })
    }

    fun remove(uri: String) {
        write(saved().filter { it.uri != uri })
        if (favoriteUri() == uri) prefs.edit().remove(KEY_FAVORITE).apply()
    }

    /** Written with `commit` so a process restart can read it. */
    fun setPending(local: Boolean, uri: String, label: String, phone: String) {
        prefs.edit()
            .putBoolean(KEY_PENDING, true)
            .putBoolean(KEY_PENDING_LOCAL, local)
            .putString(KEY_PENDING_URI, uri)
            .putString(KEY_PENDING_LABEL, label)
            .putString(KEY_PENDING_PHONE, phone)
            .commit()
    }

    fun takePending(): Pending? {
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val pending = Pending(
            local = prefs.getBoolean(KEY_PENDING_LOCAL, false),
            uri = prefs.getString(KEY_PENDING_URI, "").orEmpty(),
            label = prefs.getString(KEY_PENDING_LABEL, "").orEmpty(),
            phone = prefs.getString(KEY_PENDING_PHONE, "").orEmpty(),
        )
        prefs.edit()
            .remove(KEY_PENDING)
            .remove(KEY_PENDING_LOCAL)
            .remove(KEY_PENDING_URI)
            .remove(KEY_PENDING_LABEL)
            .remove(KEY_PENDING_PHONE)
            .commit()
        return pending
    }

    private fun write(items: List<Saved>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("label", item.label)
                    .put("manual", item.manual),
            )
        }
        prefs.edit().putString(KEY_MASTERS, array.toString()).apply()
    }

    data class Pending(val local: Boolean, val uri: String, val label: String, val phone: String)

    companion object {
        private const val PREFS = "grabbure_masters"
        private const val KEY_MASTERS = "masters"
        private const val KEY_FAVORITE = "favorite"
        private const val KEY_PENDING = "pending"
        private const val KEY_PENDING_LOCAL = "pending_local"
        private const val KEY_PENDING_URI = "pending_uri"
        private const val KEY_PENDING_LABEL = "pending_label"
        private const val KEY_PENDING_PHONE = "pending_phone"
    }
}
