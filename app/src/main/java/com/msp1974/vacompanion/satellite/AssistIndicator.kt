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
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    private const val ERROR_MS = 1_200L

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Voice level while listening, 0..1 (about -60..-10 dBFS). */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val clearError = Runnable { if (_state.value == State.ERROR) _state.value = State.IDLE }

    fun set(state: State) {
        handler.removeCallbacks(clearError)
        if (state != State.LISTENING) _level.value = 0f
        _state.value = state
        if (state == State.ERROR) handler.postDelayed(clearError, ERROR_MS)
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
