package com.msp1974.vacompanion.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.ui.layouts.WebView
import com.msp1974.vacompanion.utils.CustomWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Width of the handle column; the chat keeps this much room on its right (CHAT_RIGHT_ROOM). */
private val HANDLE_WIDTH = 28.dp
// Gap between the folded handle and the screen edge, away from the edge-swipe "back" gesture.
private val EDGE_GAP = 8.dp
private val HANDLE_HEIGHT = 120.dp
/** Room the chat keeps on its right for the folded handle: 36 dp. */
val CHAT_RIGHT_ROOM = HANDLE_WIDTH + EDGE_GAP

/**
 * The assistant's chat (chat_path) fills the screen; Home Assistant sits in a side drawer folded
 * to a handle on the right edge, always visible. Dragging the handle left opens it (the handle
 * then rides on the drawer's left edge); dragging it right, [timeoutS] seconds without a touch on
 * Home Assistant, or the screen going off (this leaves composition) folds it again. The handle
 * is excluded from the system's edge gestures, and the page stays loaded so it reopens where it
 * was left.
 */
@Composable
fun ChatWithDrawer(chat: CustomWebView, homeAssistant: CustomWebView, timeoutS: Int, bottomRoom: Dp = 0.dp) {
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
        val widthPx = with(density) { maxWidth.toPx() }
        val closedPx = widthPx - with(density) { (HANDLE_WIDTH + EDGE_GAP).toPx() }
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

        // Room on the right so the chat isn't covered by the folded handle.
        // ...and at the bottom while the now-playing card is showing, so the last messages stay visible.
        WebView(chat, modifier = Modifier.fillMaxSize().padding(end = CHAT_RIGHT_ROOM, bottom = bottomRoom))

        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
        ) {
            // Handle column: only the pill takes touches, the rest shows the chat beneath.
            // The column is the handle plus the edge gap, so when folded the pill sits a little in
            // from the screen edge and none of Home Assistant shows past it.
            Box(
                modifier = Modifier.width(HANDLE_WIDTH + EDGE_GAP).fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .width(HANDLE_WIDTH)
                        .height(HANDLE_HEIGHT)
                        .systemGestureExclusion()
                        .background(Color(0xCC202124), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                        .draggable(
                            orientation = Orientation.Horizontal,
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
                    // Points the way the handle can go: left to open, right to close.
                    Icon(
                        imageVector = if (isOpen) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                        contentDescription = if (isOpen) "Close Home Assistant" else "Open Home Assistant",
                        tint = Color(0xDDFFFFFF),
                    )
                }
            }
            WebView(homeAssistant, modifier = Modifier.fillMaxSize())
        }
    }
}
