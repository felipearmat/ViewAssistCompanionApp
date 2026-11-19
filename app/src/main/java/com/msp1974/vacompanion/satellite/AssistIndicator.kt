package com.msp1974.vacompanion.satellite

import android.os.Handler
import android.os.Looper
import com.msp1974.vacompanion.audio.AudioDSP
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the voice assistant is doing, for the on-screen indicator (assist_indicator): nothing
 * while idle, then listening, thinking or speaking, and a short error flash. The satellite sets
 * it; the UI only reads it. Lives in the app process, shared by the service and the activity.
 */
object AssistIndicator {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR, PROMPT, ACK }

    private const val ERROR_MS = 1_200L
    private const val ACK_MS = 700L
    // A wake word shows for at least this long, even when the pipeline ends at once (as when HA
    // only counts wake words, during calibration).
    private const val MIN_LISTENING_MS = 700L

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Voice level while listening, 0..1 (about -60..-10 dBFS). */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val backToRest = Runnable { _state.value = rest() }

    // HA can ask for a "speak now" prompt (action indicator, state prompt) that the bar returns
    // to instead of idle, until HA clears it or its time runs out.
    @Volatile private var promptUntil = 0L
    private var listeningSince = 0L

    private fun rest(): State = if (System.currentTimeMillis() < promptUntil) State.PROMPT else State.IDLE

    fun set(state: State) {
        handler.removeCallbacks(backToRest)
        if (state != State.LISTENING) _level.value = 0f
        val now = System.currentTimeMillis()
        if (state == State.IDLE && _state.value == State.LISTENING && now - listeningSince < MIN_LISTENING_MS) {
            handler.postDelayed(backToRest, MIN_LISTENING_MS - (now - listeningSince))
            return
        }
        if (state == State.LISTENING && _state.value != State.LISTENING) listeningSince = now
        _state.value = if (state == State.IDLE) rest() else state
        when (state) {
            State.ERROR -> handler.postDelayed(backToRest, ERROR_MS)
            State.ACK -> handler.postDelayed(backToRest, ACK_MS)
            else -> {}
        }
    }

    /** HA's prompt: shows "speak now" between interactions for [seconds] (0 clears it). */
    fun prompt(seconds: Int) {
        promptUntil = if (seconds > 0) System.currentTimeMillis() + seconds * 1000L else 0L
        if (_state.value == State.IDLE || _state.value == State.PROMPT) {
            _state.value = rest()
        }
        if (seconds > 0) handler.postDelayed({ if (_state.value == State.PROMPT) _state.value = rest() }, seconds * 1000L + 50)
    }

    /** State for a pipeline stage. */
    fun forStage(stage: PipelineStage): State = when (stage) {
        PipelineStage.INIT, PipelineStage.STARTING, PipelineStage.STARTED,
        PipelineStage.LISTENING, PipelineStage.VOICE_STARTED -> State.LISTENING
        PipelineStage.VOICE_STOPPED, PipelineStage.AWAITING_RESPONSE,
        PipelineStage.AWAITING_TTS, PipelineStage.STARTING_TTS -> State.THINKING
        PipelineStage.STREAMING_TTS -> State.SPEAKING
        PipelineStage.COMPLETED_TTS, PipelineStage.ENDED -> State.IDLE
    }

    /** [linearLevel] as from [AudioDSP.audioLevel]. */
    fun setLevel(linearLevel: Float) {
        if (_state.value != State.LISTENING) return
        val db = AudioDSP().linearToDbfs(linearLevel)
        _level.value = ((db + 60f) / 50f).coerceIn(0f, 1f)
    }
}
