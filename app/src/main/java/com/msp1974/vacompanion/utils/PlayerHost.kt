package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Hosts a music player web page (player_url, from HA: e.g. a Spotify Connect speaker built on
 * the Spotify Web Playback SDK) in a WebView of its own, apart from the dashboard and the chat.
 * It lives as long as the app, is never paused, may autoplay and play DRM media (https only),
 * and reloads itself when the page fails or its renderer dies (backing off up to 5 min).
 * The page tells the app what it plays through window.VacaPlayer.estado(json); the app counts
 * that as music (threshold, own playback) and lowers it while the assistant listens via
 * window.VacaPlayerDuck(volume 0..1), if the page defines it.
 *
 * player_url may carry a secret in its fragment (#k=...): never logged; only the part before
 * '#' ever is.
 */
@SuppressLint("StaticFieldLeak")
object PlayerHost {
    enum class State { OFF, LOADING, READY, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private var config: APPConfig? = null
    private var appContext: Context? = null
    var view: WebView? = null
        private set(value) {
            field = value
            viewFlow.value = value
        }
    /** The player WebView, for the UI to keep it attached (invisible) to the screen. */
    val viewFlow = kotlinx.coroutines.flow.MutableStateFlow<WebView?>(null)
    private var url = ""
    private var retryMs = 5_000L
    private val reload = Runnable { load() }

    @Volatile var state = State.OFF
        private set
    @Volatile var lastError: String? = null
        private set

    /** Starts, restarts or stops the player for the current player_url; safe to call often. */
    fun apply(context: Context, config: APPConfig) {
        main.post {
            this.config = config
            appContext = context.applicationContext
            val wanted = config.playerUrl.trim()
            if (wanted == url && (wanted.isEmpty() || view != null)) return@post
            url = wanted
            main.removeCallbacks(reload)
            if (wanted.isEmpty()) {
                destroy()
                setState(State.OFF, null)
                return@post
            }
            if (!wanted.startsWith("https://")) {
                destroy()
                setState(State.ERROR, "player_url must be https")
                return@post
            }
            retryMs = 5_000L
            load()
        }
    }

    private fun safe(u: String) = u.substringBefore('#')

    private fun load() {
        val context = appContext ?: return
        val v = view ?: create(context).also { view = it }
        Timber.i("Player: loading ${safe(url)}")
        setState(State.LOADING, null)
        v.loadUrl(url)
    }

    private fun destroy() {
        view?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        view = null
    }

    private fun scheduleReload(reason: String) {
        setState(State.ERROR, reason)
        main.removeCallbacks(reload)
        Timber.w("Player: $reason; reloading in ${retryMs / 1000} s")
        main.postDelayed(reload, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(300_000L)
    }

    private fun setState(new: State, error: String?) {
        state = new
        lastError = error
        if (new != State.READY && new != State.LOADING) config?.playerPlaying = false
        config?.eventBroadcaster?.notifyEvent(Event("playerState", "", new.name.lowercase()))
    }

    /** Lowers (true) or restores the player's own volume while the assistant listens or speaks. */
    fun duck(on: Boolean, level: Float) {
        main.post {
            val v = view ?: return@post
            val volume = if (on) level.coerceIn(0f, 1f) else 1f
            v.evaluateJavascript("window.VacaPlayerDuck && window.VacaPlayerDuck($volume)", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        isClickable = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(Bridge(), "VacaPlayer")
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // DRM for the music service's player; secure origins only.
                if (request.origin?.scheme == "https" &&
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else {
                    request.deny()
                }
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Timber.tag("WebConsole").i("player ${message.messageLevel()} ${safe(message.sourceId())}:${message.lineNumber()} ${message.message()}")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (state == State.LOADING) {
                    retryMs = 5_000L
                    setState(State.READY, null)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) scheduleReload("page error ${error.errorCode}: ${error.description}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The renderer died (e.g. memory): start over with a fresh WebView.
                if (this@PlayerHost.view === view) {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy()
                    this@PlayerHost.view = null
                }
                scheduleReload("renderer gone")
                return true
            }
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun estado(json: String) {
            runCatching {
                val p = Json.parseToJsonElement(json).jsonObject
                val playing = p["tocando"]?.jsonPrimitive?.booleanOrNull ?: false
                config?.playerPlaying = playing
                config?.playerTitle = listOfNotNull(
                    p["artista"]?.jsonPrimitive?.contentOrNull,
                    p["titulo"]?.jsonPrimitive?.contentOrNull,
                ).joinToString(" - ")
            }.onFailure { Timber.w("Player: bad estado(): ${it.message}") }
        }
    }
}
