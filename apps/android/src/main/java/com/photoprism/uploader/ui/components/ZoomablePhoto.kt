package com.photoprism.uploader.ui.components

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs

/** A multi-touch gesture can never submit a swipe, including after one finger lifts. */
@Composable
fun ZoomablePhoto(model: Any, description: String, modifier: Modifier = Modifier,
                  enabled: Boolean = true, onSwipe: ((Boolean) -> Unit)? = null) {
    var zoom by remember(model) { mutableFloatStateOf(1f) }
    var pan by remember(model) { mutableStateOf(Offset.Zero) }
    var drag by remember(model) { mutableFloatStateOf(0f) }
    val latestSwipe by rememberUpdatedState(onSwipe)
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        AsyncImage(model, contentDescription = description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().testTag("zoomable-photo")
                .pointerInput(model, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else 3f
                        pan = Offset.Zero
                    })
                }
                .pointerInput(model, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var multipleFingers = false
                        val startedZoomed = zoom > 1.01f
                        var horizontal = 0f
                        var vertical = 0f
                        var cancelled = false
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) cancelled = true
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) multipleFingers = true
                                val movement = event.calculatePan()
                                if (multipleFingers || startedZoomed) {
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                    val limitX = size.width * (zoom - 1) / 2f
                                    val limitY = size.height * (zoom - 1) / 2f
                                    pan = Offset((pan.x + movement.x).coerceIn(-limitX, limitX),
                                        (pan.y + movement.y).coerceIn(-limitY, limitY))
                                    if (event.changes.any { it.positionChanged() }) event.changes.forEach { it.consume() }
                                    drag = 0f
                                } else if (latestSwipe != null && !cancelled) {
                                    horizontal += movement.x
                                    vertical += movement.y
                                    if (abs(horizontal) > viewConfiguration.touchSlop && abs(horizontal) > abs(vertical)) {
                                        drag = horizontal
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            if (!cancelled && !multipleFingers && !startedZoomed && abs(horizontal) > threshold && abs(horizontal) > abs(vertical)) {
                                latestSwipe?.invoke(horizontal > 0)
                            }
                        } finally { drag = 0f }
                    }
                }
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x + drag; translationY = pan.y
                    rotationZ = if (zoom == 1f) drag / 40f else 0f
                })
        if (drag != 0f) Text(if (drag > 0) "UPLOAD →" else "← IGNORE", Modifier.align(Alignment.TopCenter))
        if (zoom > 1.01f) Text("${"%.1f".format(zoom)}× · Double-tap to reset",
            Modifier.align(Alignment.BottomCenter).testTag("zoom-indicator"))
    }
}
