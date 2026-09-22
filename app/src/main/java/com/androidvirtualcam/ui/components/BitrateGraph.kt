package com.androidvirtualcam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun BitrateGraph(
    bitrateHistory: List<Int>, // kbps, last 60s
    currentBitrate: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${currentBitrate / 1000} kbps",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "60s history",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF121212))
        ) {
            if (bitrateHistory.isEmpty()) return@Canvas

            val width = size.width
            val height = size.height
            val maxBitrate = (bitrateHistory.maxOrNull() ?: 6000).coerceAtLeast(1000).toFloat()
            val minBitrate = 0f

            val path = Path()
            val stepX = width / (bitrateHistory.size.coerceAtLeast(1) - 1).coerceAtLeast(1)

            bitrateHistory.forEachIndexed { index, bitrate ->
                val x = index * stepX
                val normalized = (bitrate - minBitrate) / (maxBitrate - minBitrate)
                val y = height - (normalized * height * 0.8f) - height * 0.1f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = Color(0xFF3DDC84),
                style = Stroke(width = 2.dp.toPx())
            )

            // Fill under
            val fillPath = Path().apply {
                addPath(path)
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }
            drawPath(
                path = fillPath,
                color = Color(0xFF3DDC84).copy(alpha = 0.2f)
            )

            // Grid lines
            for (i in 1..3) {
                val y = height * (i / 4f)
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}

@Composable
fun HealthDot(
    status: StreamHealth,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        StreamHealth.HEALTHY -> Color(0xFF4CAF50)
        StreamHealth.WARNING -> Color(0xFFFFC107)
        StreamHealth.ERROR -> Color(0xFFF44336)
        StreamHealth.OFFLINE -> Color.Gray
    }

    Canvas(modifier = modifier.size(12.dp)) {
        drawCircle(color = color)
        drawCircle(color = color.copy(alpha = 0.3f), radius = size.minDimension / 2 * 1.8f)
    }
}

enum class StreamHealth {
    HEALTHY,
    WARNING,
    ERROR,
    OFFLINE
}
