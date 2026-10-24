package com.shahaabapps.obscura.ui.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Pure range maths for drag selection, kept apart from gestures so it can be unit-tested. */
object SelectionLogic {
    /**
     * The selection while a drag runs from [anchor] to [current] (indexes into [ordered]).
     * [adding] selects the range on top of [before]; otherwise it is removed from [before].
     * Moving back towards the anchor gives back what the drag had taken, as on iOS.
     */
    fun dragRange(ordered: List<String>, before: Set<String>, anchor: Int, current: Int, adding: Boolean): Set<String> {
        val range = ordered.subList(minOf(anchor, current), maxOf(anchor, current) + 1)
        return if (adding) before + range else before - range.toSet()
    }

    fun toggle(selection: Set<String>, id: String): Set<String> = if (id in selection) selection - id else selection + id
}

/**
 * iOS-style multi-select on a lazy grid of files:
 * - long-press a tile to start selecting it, then keep dragging to select every tile you pass;
 * - while selecting, a sideways swipe that starts on a tile selects the range (or deselects it,
 *   if that first tile was already selected); up and down drags still scroll;
 * - dragging close to the top or bottom edge scrolls the grid along.
 *
 * Grid item keys must be the file ids in [orderedIds]; other items (headers) are skipped.
 */
@Composable
fun Modifier.dragToSelect(
    gridState: LazyGridState,
    selecting: Boolean,
    orderedIds: List<String>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val step = with(LocalDensity.current) { 14.dp.toPx() }
    // Read through State so changes don't restart a gesture that is in progress.
    val selectingNow: State<Boolean> = rememberUpdatedState(selecting)
    val ids by rememberUpdatedState(orderedIds)
    val current by rememberUpdatedState(selection)
    val change by rememberUpdatedState(onSelectionChange)

    return pointerInput(gridState) {
        awaitEachGesture {
            val order = ids
            val indexOf = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, id -> put(id, i) } }
            fun indexAt(p: Offset): Int? {
                val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    p.x >= info.offset.x && p.x < info.offset.x + info.size.width &&
                        p.y >= info.offset.y && p.y < info.offset.y + info.size.height
                } ?: return null
                return indexOf[hit.key as? String]
            }

            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val anchor = indexAt(down.position) ?: return@awaitEachGesture
            val wasSelecting = selectingNow.value
            val start = if (!wasSelecting) {
                // A long press: the finger stays within touch slop for the system's long-press
                // time. Watched in the Initial pass, before the tile's own tap handling.
                val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var gaveUp = false
                    while (!gaveUp) {
                        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        gaveUp = c == null || !c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                    }
                    true
                }
                if (cancelled == null) down else null
            } else {
                // Take over only for a sideways move; vertical moves stay with scrolling and a
                // plain tap stays with the tile.
                var taken: androidx.compose.ui.input.pointer.PointerInputChange? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val d = c.position - down.position
                    if (abs(d.x) > viewConfiguration.touchSlop && abs(d.x) > abs(d.y)) {
                        c.consume()
                        taken = c
                        break
                    }
                    if (abs(d.y) > viewConfiguration.touchSlop) break
                }
                taken
            } ?: return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val before = current
            val adding = !wasSelecting || order[anchor] !in before
            var at = anchor
            var pointer = start.position
            fun apply() = change(SelectionLogic.dragRange(order, before, anchor, at, adding))
            apply()

            var autoScroll: Job? = null
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    pointer = c.position
                    indexAt(pointer)?.let { if (it != at) { at = it; apply() } }
                    val direction = when {
                        pointer.y < edge -> -1f
                        pointer.y > size.height - edge -> 1f
                        else -> 0f
                    }
                    if (direction == 0f) {
                        autoScroll?.cancel()
                    } else if (autoScroll?.isActive != true) {
                        autoScroll = scope.launch {
                            while (isActive) {
                                val dir = when {
                                    pointer.y < edge -> -1f
                                    pointer.y > size.height - edge -> 1f
                                    else -> break
                                }
                                gridState.scrollBy(dir * step)
                                indexAt(pointer)?.let { if (it != at) { at = it; apply() } }
                                delay(16)
                            }
                        }
                    }
                }
            } finally {
                autoScroll?.cancel()
            }
        }
    }
}
