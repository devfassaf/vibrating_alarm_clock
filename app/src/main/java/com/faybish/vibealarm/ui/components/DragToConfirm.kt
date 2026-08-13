package com.faybish.vibealarm.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.roundToInt

/**
 * The geometry of "drag it, don't tap it", kept separate from the composable so the
 * threshold is a testable number rather than a feel.
 */
internal object DragConfirm {

    /**
     * Most of the way across. Low enough to be an easy half-awake gesture, high enough
     * that a hand brushing the screen cannot reach it.
     */
    const val TRIGGER_FRACTION = 0.6f

    fun travelPx(trackPx: Float, handlePx: Float): Float = (trackPx - handlePx).coerceAtLeast(0f)

    fun progress(offsetPx: Float, trackPx: Float, handlePx: Float): Float {
        val travel = travelPx(trackPx, handlePx)
        return if (travel <= 0f) 0f else (offsetPx / travel).coerceIn(0f, 1f)
    }

    fun shouldTrigger(progress: Float): Boolean = progress >= TRIGGER_FRACTION
}

/**
 * An action that has to be dragged, not tapped.
 *
 * A phone that is being picked up at 6am, or that is under a duvet, produces taps nobody
 * meant — and a tap on the wrong one of two buttons is a missed morning. Dragging cannot
 * happen by accident. A short press does nothing at all, which is the point.
 *
 * Screen readers get the action as a real accessibility action: a gesture nobody can
 * perform is worse than an accidental tap.
 */
@Composable
fun DragToConfirm(
    label: String,
    icon: ImageVector,
    contentColor: Color,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held as a plain float, updated inside the drag callback itself: onDragStopped runs on
    // the gesture's own scope, so an offset published through a launched coroutine can still
    // read as 0 when the finger lifts — and then a full drag does nothing at all.
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var trackPx by remember { mutableFloatStateOf(0f) }
    var handlePx by remember { mutableFloatStateOf(0f) }

    // Drag deltas are raw screen pixels; the handle should travel toward the layout's end,
    // which in Hebrew means leftwards.
    val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .onSizeChanged { trackPx = it.width.toFloat() }
            .background(contentColor.copy(alpha = 0.12f), CircleShape)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label) {
                        onConfirm()
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor.copy(alpha = 0.85f),
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(HANDLE_MARGIN)
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .size(HANDLE_SIZE)
                .onSizeChanged { handlePx = it.width.toFloat() + HANDLE_MARGIN_PX * 2 }
                .background(contentColor.copy(alpha = 0.9f), CircleShape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val travel = DragConfirm.travelPx(trackPx, handlePx)
                        offsetPx = (offsetPx + towardEnd * delta).coerceIn(0f, travel)
                    },
                    onDragStopped = {
                        if (DragConfirm.shouldTrigger(
                                DragConfirm.progress(offsetPx, trackPx, handlePx),
                            )
                        ) {
                            onConfirm()
                        } else {
                            // Springs back, so a half-hearted drag reads as "not yet".
                            animate(initialValue = offsetPx, targetValue = 0f) { value, _ ->
                                offsetPx = value
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (contentColor == Color.White) Color.Black else Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private val TRACK_HEIGHT = 76.dp
private val HANDLE_SIZE = 60.dp
private val HANDLE_MARGIN = 8.dp

/** Approximate; only used to keep the handle inside the track on any density. */
private const val HANDLE_MARGIN_PX = 24f
