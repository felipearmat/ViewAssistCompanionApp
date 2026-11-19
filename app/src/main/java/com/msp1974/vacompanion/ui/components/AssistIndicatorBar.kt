package com.msp1974.vacompanion.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.satellite.AssistIndicator
import com.msp1974.vacompanion.satellite.AssistIndicator.State

private val LISTENING_COLOR = Color(0xFF00C8FF)
private val THINKING_COLOR = Color(0xFF3D7BFF)
private val SPEAKING_COLOR = Color(0xFF2ECC71)
private val ERROR_COLOR = Color(0xFFFF3B30)

/**
 * A bar along the bottom of the screen showing what the voice assistant is doing, in the manner
 * of smart displays: nothing while idle (no burn-in), a cyan bar that grows from the centre with
 * your voice while listening, a slow blue pulse while thinking, a green pulse while speaking and
 * a short red flash on error. It doesn't take touches.
 */
@Composable
fun AssistIndicatorBar() {
    val state by AssistIndicator.state.collectAsState()
    val level by AssistIndicator.level.collectAsState()
    // Keep drawing the last state while the bar fades out.
    var shown by remember { mutableStateOf(State.LISTENING) }
    if (state != State.IDLE) shown = state

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = state != State.IDLE,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(400)),
        ) {
            val pulse = rememberInfiniteTransition(label = "assist-pulse")
            val slow by pulse.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "slow",
            )
            val fast by pulse.animateFloat(
                initialValue = 0.55f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(320, easing = LinearEasing), RepeatMode.Reverse),
                label = "fast",
            )
            val voice by animateFloatAsState(targetValue = level, animationSpec = tween(90), label = "voice")

            Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                when (shown) {
                    State.LISTENING -> {
                        // Dim full-width base, and a bright centre that widens with the voice.
                        drawRect(LISTENING_COLOR.copy(alpha = 0.35f))
                        val width = size.width * (0.25f + 0.75f * voice)
                        val left = (size.width - width) / 2
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, LISTENING_COLOR, LISTENING_COLOR, Color.Transparent),
                                startX = left, endX = left + width,
                            ),
                            topLeft = Offset(left, 0f),
                            size = Size(width, size.height),
                        )
                    }
                    State.THINKING -> drawRect(THINKING_COLOR.copy(alpha = slow))
                    State.SPEAKING -> drawRect(SPEAKING_COLOR.copy(alpha = fast))
                    State.ERROR -> drawRect(ERROR_COLOR)
                    // "Speak now" (HA's prompt, e.g. during calibration): a soft breathing cyan.
                    State.PROMPT -> drawRect(LISTENING_COLOR.copy(alpha = 0.15f + 0.35f * slow))
                    // "Got it": a short solid green.
                    State.ACK -> drawRect(SPEAKING_COLOR)
                    State.IDLE -> Unit
                }
            }
        }
    }
}
