package com.pixelforge.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aspect-ratio-preserving Before/After loupe slider with pinch-to-zoom,
 * pan (when zoomed), double-tap zoom toggle, and darkroom safelight divider.
 */
@Composable
fun BeforeAfterSlider(
    beforeImage: ImageBitmap,
    afterImage: ImageBitmap,
    modifier: Modifier = Modifier,
    dividerColor: Color = MaterialTheme.colorScheme.primary,
    handleBackingColor: Color = MaterialTheme.colorScheme.background,
    dividerWidth: Float = 3.5f,
    onZoomChanged: (Float) -> Unit = {}
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dividerPosition by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoom > 1.1f) {
                            zoom = 1f
                            panOffset = Offset.Zero
                        } else {
                            zoom = 2.5f
                        }
                        onZoomChanged(zoom)
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cw = containerSize.width.toFloat().coerceAtLeast(1f)
                    val ch = containerSize.height.toFloat().coerceAtLeast(1f)
                    val splitScreenX = cw * dividerPosition
                    var draggingDivider = zoom <= 1.05f || abs(down.position.x - splitScreenX) <= 72f

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            draggingDivider = false
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            zoom = newZoom
                            if (newZoom <= 1.01f) {
                                zoom = 1f
                                panOffset = Offset.Zero
                            } else {
                                val maxPanX = (cw * (newZoom - 1f)) * 0.5f
                                val maxPanY = (ch * (newZoom - 1f)) * 0.5f
                                panOffset = Offset(
                                    x = (panOffset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                                    y = (panOffset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                                )
                            }
                            onZoomChanged(zoom)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressedCount == 1) {
                            val change = event.changes.first()
                            if (draggingDivider) {
                                dividerPosition = (change.position.x / cw).coerceIn(0.03f, 0.97f)
                                if (change.positionChanged()) change.consume()
                            } else if (zoom > 1.01f) {
                                val delta = change.position - change.previousPosition
                                val maxPanX = (cw * (zoom - 1f)) * 0.5f
                                val maxPanY = (ch * (zoom - 1f)) * 0.5f
                                panOffset = Offset(
                                    x = (panOffset.x + delta.x).coerceIn(-maxPanX, maxPanX),
                                    y = (panOffset.y + delta.y).coerceIn(-maxPanY, maxPanY)
                                )
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

            val imgW = afterImage.width.toFloat().coerceAtLeast(1f)
            val imgH = afterImage.height.toFloat().coerceAtLeast(1f)
            val fitScale = min(canvasWidth / imgW, canvasHeight / imgH)
            val drawW = (imgW * fitScale).roundToInt().coerceAtLeast(1)
            val drawH = (imgH * fitScale).roundToInt().coerceAtLeast(1)
            val offsetX = ((canvasWidth - drawW) * 0.5f).roundToInt()
            val offsetY = ((canvasHeight - drawH) * 0.5f).roundToInt()

            val dstOffset = IntOffset(offsetX, offsetY)
            val dstSize = IntSize(drawW, drawH)
            val splitX = canvasWidth * dividerPosition

            withTransform({
                translate(left = panOffset.x, top = panOffset.y)
                scale(
                    scaleX = zoom,
                    scaleY = zoom,
                    pivot = Offset(canvasWidth * 0.5f, canvasHeight * 0.5f)
                )
            }) {
                drawImage(
                    image = afterImage,
                    dstOffset = dstOffset,
                    dstSize = dstSize
                )
            }

            clipRect(right = splitX) {
                withTransform({
                    translate(left = panOffset.x, top = panOffset.y)
                    scale(
                        scaleX = zoom,
                        scaleY = zoom,
                        pivot = Offset(canvasWidth * 0.5f, canvasHeight * 0.5f)
                    )
                }) {
                    drawImage(
                        image = beforeImage,
                        dstOffset = dstOffset,
                        dstSize = dstSize
                    )
                }
            }

            // Hairline split & high-contrast loupe grip
            drawLine(
                color = dividerColor,
                start = Offset(splitX, 0f),
                end = Offset(splitX, canvasHeight),
                strokeWidth = dividerWidth
            )

            val center = Offset(splitX, canvasHeight * 0.5f)
            drawCircle(
                color = handleBackingColor.copy(alpha = 0.92f),
                radius = 28f,
                center = center
            )
            drawCircle(
                color = dividerColor,
                radius = 28f,
                center = center,
                style = Stroke(width = 3.5f)
            )
            // Left / right directional grip ticks
            drawLine(
                color = dividerColor,
                start = Offset(center.x - 9f, center.y - 7f),
                end = Offset(center.x - 14f, center.y),
                strokeWidth = 2.8f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = dividerColor,
                start = Offset(center.x - 14f, center.y),
                end = Offset(center.x - 9f, center.y + 7f),
                strokeWidth = 2.8f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = dividerColor,
                start = Offset(center.x + 9f, center.y - 7f),
                end = Offset(center.x + 14f, center.y),
                strokeWidth = 2.8f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = dividerColor,
                start = Offset(center.x + 14f, center.y),
                end = Offset(center.x + 9f, center.y + 7f),
                strokeWidth = 2.8f,
                cap = StrokeCap.Round
            )
        }
    }
}
