package com.shahaabapps.obscura.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/** How many items fit across the gallery: fewer is bigger. */
object ColumnRange {
    const val MIN = 2
    const val MAX = 6

    /** Pinching this far out (or in) moves one step, so a step takes a deliberate gesture. */
    const val STEP_ZOOM = 1.35f

    val choices = (MIN..MAX).toList()

    fun stepped(columns: Int, steps: Int): Int = (columns + steps).coerceIn(MIN, MAX)
}

/**
 * Pinch the grid to change how many items fit across it, as in a phone's own gallery: spread two
 * fingers for fewer, bigger items, pinch them together for more, smaller ones.
 *
 * Only two-finger gestures are taken, so a one-finger scroll or a drag-select still works.
 */
@Composable
fun Modifier.pinchToChangeColumns(columns: Int, onColumns: (Int) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(columns)
    val change by rememberUpdatedState(onColumns)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    pinching = true
                    zoom *= event.calculateZoom()
                    // Spreading apart zooms in, which means fewer columns.
                    val steps = when {
                        zoom >= ColumnRange.STEP_ZOOM -> -1
                        zoom <= 1f / ColumnRange.STEP_ZOOM -> 1
                        else -> 0
                    }
                    if (steps != 0) {
                        val next = ColumnRange.stepped(current, steps)
                        if (next != current) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            change(next)
                        }
                        zoom = 1f
                    }
                }
                // Consume only while two fingers are down, so scrolling is untouched.
                if (pinching) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
