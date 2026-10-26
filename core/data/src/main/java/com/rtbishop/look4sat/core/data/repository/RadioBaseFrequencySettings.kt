package com.rtbishop.look4sat.core.data.repository

import android.content.SharedPreferences
import com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies
import org.json.JSONObject

private const val BASE_KEY_PREFIX = "radioBaseFrequencies.v1."

private fun RadioBaseFrequencies.valid() = rxHz in 1..9_999_999_999L &&
    (txHz == null || txHz in 1..9_999_999_999L)

internal fun readRadioBaseFrequencies(preferences: SharedPreferences, uuid: String): RadioBaseFrequencies? {
    if (uuid.isBlank()) return null
    return try {
        val saved = preferences.getString(BASE_KEY_PREFIX + uuid, null) ?: return null
        val json = JSONObject(saved)
        if (json.optInt("version") != 1 || !json.has("txHz")) return null
        val rx = json.get("rxHz")
        val tx = json.get("txHz")
        if (rx !is Long && rx !is Int || tx != JSONObject.NULL && tx !is Long && tx !is Int) return null
        RadioBaseFrequencies((rx as Number).toLong(), (tx as? Number)?.toLong())
            .takeIf { it.valid() }
    } catch (_: Exception) { null }
}

internal fun writeRadioBaseFrequencies(preferences: SharedPreferences, uuid: String, bases: RadioBaseFrequencies): Boolean {
    require(uuid.isNotBlank() && bases.valid())
    val json = JSONObject().put("version", 1).put("rxHz", bases.rxHz)
        .put("txHz", bases.txHz ?: JSONObject.NULL)
    // Edits run on the control worker; complete the disk write before reporting acceptance.
    return preferences.edit().putString(BASE_KEY_PREFIX + uuid, json.toString()).commit()
}
