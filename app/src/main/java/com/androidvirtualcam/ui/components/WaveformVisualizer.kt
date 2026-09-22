package com.androidvirtualcam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun WaveformVisualizer(
    isActive: Boolean,
    waveformData: List<Float> = emptyList(),
    modifier: Modifier = Modifier
) {
    var phase by remember { mutableStateOf(0f) }

    LaunchedEffect(isActive) {
        while (isActive) {
            phase += 0.15f
            delay(16) // 60fps
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF121212))
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        val path = Path()

        if (waveformData.isNotEmpty()) {
            val step = width / waveformData.size
            waveformData.forEachIndexed { index, amplitude ->
                val x = index * step
                val y = centerY + amplitude * centerY * 0.8f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        } else {
            // Generate procedural waveform when no real data, but using phase for 60fps animation
            val points = 100
            val step = width / points
            for (i in 0..points) {
                val x = i * step
                val base = sin((i * 0.2f) + phase) * 0.5f + sin((i * 0.1f) + phase * 1.5f) * 0.3f
                val noise = if (isActive) Random.nextFloat() * 0.1f else 0f
                val y = centerY + (base + noise) * centerY * 0.6f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = Color(0xFF3DDC84),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Mirror
        val mirrorPath = Path()
        if (waveformData.isNotEmpty()) {
            val step = width / waveformData.size
            waveformData.forEachIndexed { index, amplitude ->
                val x = index * step
                val y = centerY - amplitude * centerY * 0.8f
                if (index == 0) mirrorPath.moveTo(x, y) else mirrorPath.lineTo(x, y)
            }
        } else {
            val points = 100
            val step = width / points
            for (i in 0..points) {
                val x = i * step
                val base = sin((i * 0.2f) + phase) * 0.5f + sin((i * 0.1f) + phase * 1.5f) * 0.3f
                val noise = if (isActive) Random.nextFloat() * 0.1f else 0f
                val y = centerY - (base + noise) * centerY * 0.6f
                if (i == 0) mirrorPath.moveTo(x, y) else mirrorPath.lineTo(x, y)
            }
        }

        drawPath(
            path = mirrorPath,
            color = Color(0xFF3DDC84).copy(alpha = 0.5f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Center line
        drawLine(
            color = Color.White.copy(alpha = 0.1f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun VUMeter(
    level: Float, // 0..1
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .width(24.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A1A))
    ) {
        val width = size.width
        val height = size.height

        // Background
        drawRect(Color(0xFF2D2D2D))

        // Level
        val levelHeight = height * level.coerceIn(0f, 1f)
        val levelColor = when {
            level > 0.9f -> Color.Red
            level > 0.7f -> Color.Yellow
            else -> Color(0xFF3DDC84)
        }

        drawRect(
            color = levelColor,
            topLeft = Offset(0f, height - levelHeight),
            size = androidx.compose.ui.geometry.Size(width, levelHeight)
        )

        // Segments
        for (i in 0..10) {
            val y = height * (i / 10f)
            drawLine(
                color = Color.Black.copy(alpha = 0.3f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}
