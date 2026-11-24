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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aspect-ratio-preserving Before/After loupe slider with pinch-to-zoom,
 * image-bounded pan (when zoomed), double-tap zoom toggle, TalkBack actions,
 * and darkroom safelight divider.
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

    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val handleHitRadiusPx = remember(density) { with(density) { 36.dp.toPx() } }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .semantics(mergeDescendants = true) {
                val splitPct = (dividerPosition * 100f).roundToInt()
                contentDescription = "Before and after comparison loupe"
                stateDescription = "Split at $splitPct percent, zoom ${"%.1f".format(zoom)}x"
                customActions = listOf(
                    CustomAccessibilityAction("Show more after") {
                        dividerPosition = (dividerPosition - 0.1f).coerceIn(0.03f, 0.97f)
                        true
                    },
                    CustomAccessibilityAction("Show more before") {
                        dividerPosition = (dividerPosition + 0.1f).coerceIn(0.03f, 0.97f)
                        true
                    },
                    CustomAccessibilityAction(if (zoom > 1.1f) "Reset zoom" else "Zoom in") {
                        if (zoom > 1.1f) {
                            zoom = 1f
                            panOffset = Offset.Zero
                        } else {
                            zoom = 2.5f
                        }
                        onZoomChanged(zoom)
                        true
                    }
                )
            }
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
            .pointerInput(handleHitRadiusPx, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cw = containerSize.width.toFloat().coerceAtLeast(1f)
                    val ch = containerSize.height.toFloat().coerceAtLeast(1f)
                    val imgW = afterImage.width.toFloat().coerceAtLeast(1f)
                    val imgH = afterImage.height.toFloat().coerceAtLeast(1f)
                    val fitScale = min(cw / imgW, ch / imgH)
                    val drawW = imgW * fitScale
                    val drawH = imgH * fitScale

                    val splitScreenX = cw * dividerPosition
                    val nearHandle = abs(down.position.x - splitScreenX) <= handleHitRadiusPx
                    var draggingDivider = nearHandle
                    var dragSlopPassed = nearHandle

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
                                val maxPanX = ((drawW * newZoom - cw) * 0.5f).coerceAtLeast(0f)
                                val maxPanY = ((drawH * newZoom - ch) * 0.5f).coerceAtLeast(0f)
                                panOffset = Offset(
                                    x = (panOffset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                                    y = (panOffset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                                )
                            }
                            onZoomChanged(zoom)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressedCount == 1) {
                            val change = event.changes.first()
                            val delta = change.position - change.previousPosition
                            if (!dragSlopPassed && (change.position - down.position).getDistance() > touchSlop) {
                                dragSlopPassed = true
                                if (zoom <= 1.05f) {
                                    draggingDivider = true
                                }
                            }

                            if (draggingDivider && dragSlopPassed) {
                                dividerPosition = if (nearHandle) {
                                    (change.position.x / cw).coerceIn(0.03f, 0.97f)
                                } else {
                                    (dividerPosition + delta.x / cw).coerceIn(0.03f, 0.97f)
                                }
                                if (change.positionChanged()) change.consume()
                            } else if (zoom > 1.01f && dragSlopPassed) {
                                val maxPanX = ((drawW * zoom - cw) * 0.5f).coerceAtLeast(0f)
                                val maxPanY = ((drawH * zoom - ch) * 0.5f).coerceAtLeast(0f)
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
