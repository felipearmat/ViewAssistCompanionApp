package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.layouts.WebView
import com.msp1974.vacompanion.utils.CustomWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HANDLE_HEIGHT = 28.dp

/**
 * The assistant's chat (chat_path) fills the screen; Home Assistant sits in a drawer folded down
 * to a handle at the bottom. Dragging the handle up opens it; dragging it down, [timeoutS]
 * seconds without a touch on it, or the screen going off (this leaves composition) folds it
 * again. The Home Assistant page stays loaded underneath, so it reopens where it was left.
 */
@Composable
fun ChatWithDrawer(chat: CustomWebView, homeAssistant: CustomWebView, timeoutS: Int) {
    val scope = rememberCoroutineScope()
    var lastTouch by remember { mutableLongStateOf(0L) }

    DisposableEffect(chat) {
        chat.onResume()
        onDispose { chat.onPause() }
    }
    DisposableEffect(homeAssistant) {
        homeAssistant.onUserInteraction = { lastTouch = System.currentTimeMillis() }
        onDispose { homeAssistant.onUserInteraction = null }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullPx = with(density) { maxHeight.toPx() }
        val closedPx = fullPx - with(density) { HANDLE_HEIGHT.toPx() }
        val offset = remember(closedPx) { Animatable(closedPx) }
        val isOpen = offset.value < closedPx / 2

        fun settle(open: Boolean) = scope.launch {
            offset.animateTo(if (open) 0f else closedPx, tween(250))
            if (open) lastTouch = System.currentTimeMillis()
        }

        // Fold the drawer after a while without touching Home Assistant.
        LaunchedEffect(isOpen, lastTouch) {
            if (isOpen && timeoutS > 0) {
                delay(timeoutS * 1000L)
                settle(false)
            }
        }

        WebView(chat, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offset.value.roundToInt()) }
        ) {
            // The handle: drag up to open, down to close.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HANDLE_HEIGHT)
                    .background(Color(0xCC202124), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, closedPx)) }
                        },
                        onDragStopped = { velocity ->
                            val open = velocity < -800f || (velocity <= 800f && offset.value < closedPx * 0.6f)
                            settle(open)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(5.dp)
                        .background(Color(0x99FFFFFF), RoundedCornerShape(3.dp))
                )
            }
            WebView(homeAssistant, modifier = Modifier.fillMaxSize())
        }
    }
}
