package com.msp1974.vacompanion.audio

import com.viewassist.webrtc.WebRtcApm
import timber.log.Timber
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The microphone's automatic gain control and noise suppression: WebRTC's audio processing does the
 * levelling (AGC2), noise suppression and a high-pass filter in one native pass. This is the only
 * app file that touches the `webrtc-apm` module.
 *
 * Its output level is fixed, so the `mic_gain` setting is applied afterwards with [applyGainDb].
 *
 * Echo cancellation (AEC3) is optional (`echo_cancellation`): when on, the music this device
 * plays through Sendspin is fed in as the reference via [EchoReference], so it can be taken out
 * of the microphone. Without a reference it only costs CPU, so it is off by default.
 */
class WebRtcMicProcessor(
    private val processFrame: (samples: ShortArray, offset: Int) -> Int,
    private val closeNative: () -> Unit,
    private val processRender: ((samples: ShortArray, offset: Int) -> Int)? = null,
) : AutoCloseable {

    private var closed = false

    // Far-end frames go into the same native instance, serialised with capture and close.
    private val renderSink = EchoReference.Sink { frame -> render(frame) }

    init {
        if (processRender != null) EchoReference.addSink(renderSink)
    }

    @Synchronized
    private fun render(frame: ShortArray) {
        if (closed) return
        val result = processRender?.invoke(frame, 0) ?: return
        if (result != 0) Timber.w("WebRTC render frame processing returned $result")
    }

    /**
     * Processes [input] and returns the result as a new array. WebRTC works on whole 10 ms frames,
     * so any trailing part of a frame is passed through as it is. The microphone reads in whole
     * frames, so in practice there is none.
     *
     * Serialised with [close]: the native module is not safe to use once released.
     */
    @Synchronized
    fun process(input: ShortArray): ShortArray {
        val out = input.copyOf()
        if (closed) return out
        var offset = 0
        while (offset + FRAME_SAMPLES <= out.size) {
            val result = processFrame(out, offset)
            if (result != 0) {
                Timber.w("WebRTC frame processing returned $result")
                break
            }
            offset += FRAME_SAMPLES
        }
        return out
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (processRender != null) EchoReference.removeSink(renderSink)
        runCatching { closeNative() }
    }

    companion object {
        /** 10 ms at 16 kHz. */
        const val FRAME_SAMPLES = 160

        /** [samples] made [gainDb] louder (or quieter, if negative), without wrapping when it clips. */
        fun applyGainDb(samples: ShortArray, gainDb: Float): ShortArray {
            if (gainDb == 0f) return samples
            val gain = 10f.pow(gainDb / 20f)
            return ShortArray(samples.size) {
                (samples[it] * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        /**
         * Starts WebRTC processing, or returns null - after logging why - if it can't be started,
         * such as on a device the native library doesn't support. The microphone then carries on
         * without gain control or noise suppression.
         */
        fun create(echoCancellation: Boolean = false): WebRtcMicProcessor? = try {
            val config = WebRtcApm.Config().apply {
                aecEnabled = echoCancellation
                nsEnabled = true
                nsLevel = WebRtcApm.NsLevel.MODERATE
                agcEnabled = true
                hpfEnabled = true
                transientSuppressionEnabled = false
                vadEnabled = false
            }
            val apm = WebRtcApm(config)
            Timber.i("WebRTC audio processing started (echo cancellation ${if (echoCancellation) "on" else "off"})")
            WebRtcMicProcessor(
                apm::processCaptureStream,
                apm::close,
                if (echoCancellation) apm::processRenderStream else null,
            )
        } catch (t: Throwable) {
            // Includes UnsatisfiedLinkError, which is an Error, not an Exception.
            Timber.e(t, "WebRTC audio processing is not available on this device")
            null
        }
    }
}
