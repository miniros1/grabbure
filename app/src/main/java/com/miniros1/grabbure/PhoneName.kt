package com.miniros1.grabbure

import android.content.Context

/** Topic and node prefix shared by every sensor on this phone. */
object PhoneName {
    const val DEFAULT = "android"
    private const val PREFS = "grabbure_phone"
    private const val KEY = "name"

    fun sanitize(raw: String): String {
        val cleaned = buildString {
            for (c in raw.trim()) {
                when {
                    c.isLetterOrDigit() || c == '_' -> append(c)
                    c == ' ' || c == '-' -> append('_')
                }
            }
        }.trimStart('_')
        if (cleaned.isEmpty() || !cleaned[0].isLetter()) return DEFAULT
        return cleaned
    }

    fun load(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT)
        return sanitize(saved ?: DEFAULT)
    }

    fun save(context: Context, raw: String): String {
        val name = sanitize(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, name).apply()
        return name
    }
}
