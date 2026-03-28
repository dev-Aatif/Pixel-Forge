package com.pixelforge.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Before/After comparison slider for image upscaling results.
 * Draggable divider reveals the before (left) and after (right) images.
 */
@Composable
fun BeforeAfterSlider(
    beforeImage: ImageBitmap,
    afterImage: ImageBitmap,
    modifier: Modifier = Modifier,
    dividerColor: Color = MaterialTheme.colorScheme.primary,
    dividerWidth: Float = 3f
) {
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var dividerPosition by remember { mutableFloatStateOf(0.5f) } // 0.0 to 1.0

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidth = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val newPos = change.position.x / containerWidth
                    dividerPosition = newPos.coerceIn(0.05f, 0.95f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val splitX = canvasWidth * dividerPosition

            // Draw "before" image on the left side
            clipRect(right = splitX) {
                drawImage(
                    image = beforeImage,
                    dstSize = IntSize(canvasWidth.toInt(), canvasHeight.toInt())
                )
            }

            // Draw "after" image on the right side
            clipRect(left = splitX) {
                drawImage(
                    image = afterImage,
                    dstSize = IntSize(canvasWidth.toInt(), canvasHeight.toInt())
                )
            }

            // Draw divider line
            drawLine(
                color = dividerColor,
                start = Offset(splitX, 0f),
                end = Offset(splitX, canvasHeight),
                strokeWidth = dividerWidth
            )

            // Draw divider handle (circle)
            drawCircle(
                color = dividerColor,
                radius = 16f,
                center = Offset(splitX, canvasHeight / 2)
            )
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(splitX, canvasHeight / 2)
            )
            drawCircle(
                color = dividerColor,
                radius = 6f,
                center = Offset(splitX, canvasHeight / 2)
            )
        }
    }
}
