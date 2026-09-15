package com.photoprism.uploader.ui.grid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Uses the full date index so a drag can jump beyond the currently exposed page. */
@Composable
fun PhotoDateScrubber(
    grid: LazyGridState,
    dates: List<PhotoDatePosition>,
    totalItems: Int,
    onSeek: (Float) -> Int,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val currentSeek by rememberUpdatedState(onSeek)
    val scrollingFraction by remember(grid, totalItems) {
        derivedStateOf {
            if (!grid.canScrollForward && grid.layoutInfo.totalItemsCount == totalItems) 1f
            else grid.firstVisibleItemIndex.toFloat() / (totalItems - 1).coerceAtLeast(1)
        }
    }
    val fraction = if (dragging) dragFraction else scrollingFraction
    val currentIndex = if (dragging) (fraction * (totalItems - 1)).toInt() else grid.firstVisibleItemIndex
    val month = dates.lastOrNull { it.gridIndex <= currentIndex }?.month.orEmpty()
    fun seek(value: Float) {
        dragFraction = value.coerceIn(0f, 1f)
        val index = currentSeek(dragFraction)
        seekJob?.cancel()
        seekJob = scope.launch {
            // Wait for the newly exposed batch to be laid out before asking the grid
            // to jump; otherwise LazyGrid clamps the target to the old page boundary.
            snapshotFlow { grid.layoutInfo.totalItemsCount }.first { it > index }
            grid.scrollToItem(index)
        }
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    val thumb = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxHeight().width(48.dp)) {
        if (dragging) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-44).dp,
                    y = ((maxHeight - 64.dp) * fraction).coerceAtLeast(0.dp))
                    .wrapContentWidth(unbounded = true, align = Alignment.End)
                    .testTag("scroll-date")
            ) {
                Text(month, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1)
            }
        }
        Canvas(Modifier.fillMaxSize().testTag("date-scrubber")
            .semantics {
                contentDescription = "Scroll photos by date"
                stateDescription = month
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value -> seek(value); true }
            }
            .pointerInput(Unit) {
                fun fractionAt(y: Float): Float {
                    val inset = 24.dp.toPx()
                    return ((y - inset) / (size.height - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; seek(fractionAt(it.y)) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ -> change.consume(); seek(fractionAt(change.position.y)) }
                )
            }) {
            val inset = 24.dp.toPx()
            val centerX = size.width - 16.dp.toPx()
            val centerY = inset + fraction * (size.height - 2 * inset).coerceAtLeast(0f)
            drawLine(track, Offset(centerX, inset), Offset(centerX, size.height - inset), 2.dp.toPx())
            drawRoundRect(thumb, Offset(centerX - 5.dp.toPx(), centerY - inset),
                Size(10.dp.toPx(), 48.dp.toPx()), CornerRadius(5.dp.toPx()))
        }
    }
}
