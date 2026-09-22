package com.androidvirtualcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.androidvirtualcam.compositor.CompositorNode

@Composable
fun NodeCard(
    node: CompositorNode,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onDelete: () -> Unit
) {
    val typeColor = when (node.type) {
        "CameraInput" -> Color(0xFF2196F3)
        "BackgroundReplace" -> Color(0xFF4CAF50)
        "SegmentationMask" -> Color(0xFF9C27B0)
        "ChromaKey" -> Color(0xFF4CAF50)
        "Blur" -> Color(0xFFFF9800)
        "LUT" -> Color(0xFFE91E63)
        "Image" -> Color(0xFF00BCD4)
        "VideoFile" -> Color(0xFFFF5722)
        "TextOverlay" -> Color(0xFFFFEB3B)
        "LowerThird" -> Color(0xFFFFC107)
        "Blend" -> Color(0xFF795548)
        "ColorCorrection" -> Color(0xFF607D8B)
        "Output" -> Color(0xFF3DDC84)
        else -> Color.Gray
    }

    val typeIcon = when (node.type) {
        "CameraInput" -> Icons.Default.Videocam
        "BackgroundReplace" -> Icons.Default.Wallpaper
        "SegmentationMask" -> Icons.Default.Person
        "ChromaKey" -> Icons.Default.ColorLens
        "Blur" -> Icons.Default.BlurOn
        "LUT" -> Icons.Default.Palette
        "Image" -> Icons.Default.Image
        "VideoFile" -> Icons.Default.VideoFile
        "TextOverlay" -> Icons.Default.TextFields
        "LowerThird" -> Icons.Default.Subtitles
        "Blend" -> Icons.Default.Layers
        "ColorCorrection" -> Icons.Default.Tune
        "Output" -> Icons.Default.Output
        else -> Icons.Default.Extension
    }

    Card(
        modifier = modifier
            .width(180.dp)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color(0xFF3DDC84) else Color(0xFF3A3A3A),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 8.dp else 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(typeColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(typeIcon, contentDescription = null, tint = typeColor, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(node.type, color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(node.id.take(12), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Input sockets
            node.inputSockets.forEach { socket ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFBB86FC))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        )
                        Text(socket.displayName ?: socket.id, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (node.inputSockets.isNotEmpty() && node.outputSocket.id.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Divider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(4.dp))
            }

            // Output socket
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(node.outputSocket.id, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3DDC84))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                )
            }
        }
    }
}
