package com.msp1974.vacompanion.device

import android.content.Context
import androidx.core.content.edit
import com.msp1974.vacompanion.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Tells HA how a silent update (install_update) went, so a failed one is never silent:
 * "downloading", "installing" (handed to Android), "error" (with a reason) and "installed" (sent by
 * the new app once its satellite starts, comparing builds). Sent as the `install_update` part of a
 * status event. A result that can't be sent now (satellite not connected, or the installer's
 * answer arriving in a receiver) is kept and sent when the satellite next starts.
 */
object UpdateReport {
    private const val PREFS = "install_update_report"
    private const val KEY_PENDING = "pending"
    private const val KEY_RESULT = "result"

    /** Sends one report to HA; returns false when no satellite can send it now. Set by Satellite. */
    @Volatile var sender: ((JsonObject) -> Boolean)? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun report(context: Context, state: String, reason: String = "", url: String = "", sha256: String = "") {
        val data = buildJsonObject {
            put("state", state)
            if (reason.isNotEmpty()) put("reason", reason)
            // A secret may ride in the URL fragment: never sent nor stored.
            if (url.isNotEmpty()) put("url", url.substringBefore('#'))
            if (sha256.isNotEmpty()) put("to_sha256", sha256)
            put("from_build", BuildConfig.VERSION_CODE)
        }
        Timber.i("Update report: $data")
        val p = prefs(context)
        when (state) {
            "installing" -> p.edit { putString(KEY_PENDING, data.toString()) }
            "error" -> p.edit { remove(KEY_PENDING) }
        }
        if (sender?.invoke(data) != true) p.edit { putString(KEY_RESULT, data.toString()) }
    }

    /** On satellite start: sends a result kept while disconnected, and confirms a finished install. */
    fun flush(context: Context) {
        val send = sender ?: return
        val p = prefs(context)
        p.getString(KEY_RESULT, null)?.let { stored ->
            runCatching { Json.parseToJsonElement(stored).jsonObject }.getOrNull()?.let {
                if (send(it)) p.edit { remove(KEY_RESULT) }
            } ?: p.edit { remove(KEY_RESULT) }
        }
        p.getString(KEY_PENDING, null)?.let { stored ->
            val pending = runCatching { Json.parseToJsonElement(stored).jsonObject }.getOrNull()
            val from = pending?.get("from_build")?.jsonPrimitive?.intOrNull
            if (pending == null || from == null) {
                p.edit { remove(KEY_PENDING) }
                return
            }
            // Same build still running: the install hasn't happened (yet); keep waiting for it.
            if (from == BuildConfig.VERSION_CODE) return
            val data = buildJsonObject {
                put("state", "installed")
                put("from_build", from)
                put("app_build", BuildConfig.VERSION_CODE)
                pending["to_sha256"]?.let { put("to_sha256", it) }
            }
            if (send(data)) p.edit { remove(KEY_PENDING) }
        }
    }
}
