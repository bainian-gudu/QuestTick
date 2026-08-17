package com.questtick.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * Consumes unhandled horizontal drag/fling deltas after an inner horizontal scroller reaches its edge.
 *
 * This prevents remaining horizontal movement on filter chip rows from bubbling to the outer page
 * pager and accidentally switching bottom tabs when the chip list has been dragged to either end.
 */
@Composable
fun Modifier.consumeHorizontalScrollOverflow(): Modifier {
    val connection =
        remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset =
                    if (source == NestedScrollSource.UserInput) {
                        Offset(x = available.x, y = 0f)
                    } else {
                        Offset.Zero
                    }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity = Velocity(x = available.x, y = 0f)
            }
        }
    return nestedScroll(connection)
}

/**
 * Consumes horizontal drags on a full-screen overlay so they cannot reach a parent HorizontalPager.
 *
 * Vertical scrolling inside the overlay still works because the gesture detector only wins when the
 * pointer movement is recognized as horizontal.
 */
fun Modifier.consumeHorizontalPageSwipe(): Modifier =
    pointerInput(Unit) {
        detectHorizontalDragGestures { change, _ ->
            change.consume()
        }
    }
