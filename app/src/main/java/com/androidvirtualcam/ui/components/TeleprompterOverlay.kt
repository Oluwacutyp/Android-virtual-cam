package com.androidvirtualcam.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidvirtualcam.ui.viewmodels.TeleprompterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * TeleprompterOverlay – private preview-only scrolling text.
 *
 * This is a Compose overlay rendered ON TOP of PreviewView preview (pure CameraX, ZERO GL).
 * It is NOT part of compositor graph, so it never appears in:
 * - OutputNode -> FrameBus -> Virtual Camera (WhatsApp, Zoom, etc.)
 * - RecordingManager (MP4)
 * - StreamingManager (RTMP/SRT/RTSP)
 *
 * Only YOU see it in local preview. Perfect for reading scripts while maintaining eye contact.
 *
 * Features:
 * - Vertical teleprompter (bottom→top) or horizontal ticker (right→left)
 * - Speed control (pixels per frame)
 * - Font size control
 * - Mirror mode (scaleX = -1 for beam-splitter glass)
 * - Auto-scroll with loop
 * - Background dim
 */
@Composable
fun TeleprompterOverlay(
    state: TeleprompterViewModel.TeleprompterState,
    modifier: Modifier = Modifier
) {
    if (!state.isEnabled) return

    val density = LocalDensity.current

    // Track container height for vertical mode
    var containerHeightPx by remember { mutableStateOf(0) }
    var containerWidthPx by remember { mutableStateOf(0) }
    var textHeightPx by remember { mutableStateOf(0) }
    var textWidthPx by remember { mutableStateOf(0) }

    // Scroll offset animation – driven by speed
    var offsetPx by remember { mutableStateOf(0f) }

    // Auto-scroll logic
    LaunchedEffect(state.isEnabled, state.speed, state.autoScroll, state.text, state.verticalMode, containerHeightPx, containerWidthPx, textHeightPx, textWidthPx) {
        if (!state.isEnabled || !state.autoScroll) return@LaunchedEffect

        // Reset offset when text changes
        offsetPx = if (state.verticalMode) containerHeightPx.toFloat() else containerWidthPx.toFloat()

        while (isActive && state.isEnabled) {
            delay(16) // ~60fps

            val speedPxPerFrame = state.speed * 2.5f // 1x = 2.5px per frame ~ 150px/s at 60fps

            if (state.verticalMode) {
                offsetPx -= speedPxPerFrame

                // Loop handling
                if (state.loop) {
                    if (offsetPx < -textHeightPx) {
                        offsetPx = containerHeightPx.toFloat()
                    }
                } else {
                    // Stop at end
                    if (offsetPx < -textHeightPx) {
                        offsetPx = -textHeightPx.toFloat()
                    }
                }
            } else {
                // Horizontal ticker
                offsetPx -= speedPxPerFrame * 1.5f

                if (state.loop) {
                    if (offsetPx < -textWidthPx) {
                        offsetPx = containerWidthPx.toFloat()
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerHeightPx = coords.size.height
                containerWidthPx = coords.size.width
            }
            .graphicsLayer {
                // Mirror mode for beam-splitter teleprompter
                scaleX = if (state.mirrorMode) -1f else 1f
            }
    ) {
        // Semi-transparent background for readability (optional)
        if (state.showBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = state.backgroundOpacity * 0.5f))
            )
        }

        // Private indicator badge
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF9C27B0).copy(alpha = 0.9f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(
                    "PRIVATE – ONLY YOU SEE THIS",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                if (state.mirrorMode) {
                    Icon(Icons.Default.Flip, contentDescription = "Mirror", tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }

        if (state.verticalMode) {
            // Vertical teleprompter – bottom to top
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 40.dp)
            ) {
                Text(
                    text = state.text,
                    color = Color.White.copy(alpha = state.opacity),
                    fontSize = state.fontSize.sp,
                    lineHeight = (state.fontSize * 1.4f).sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, offsetPx.roundToInt()) }
                        .onGloballyPositioned { coords ->
                            textHeightPx = coords.size.height
                        }
                        .background(
                            if (state.showBackground) Color.Black.copy(alpha = state.backgroundOpacity) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                )
            }

            // Top fade gradient indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Color.Black.copy(alpha = 0.3f))
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Center eye line – where to read
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF3DDC84).copy(alpha = 0.3f))
            )

        } else {
            // Horizontal ticker – right to left
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .background(
                        if (state.showBackground) Color.Black.copy(alpha = state.backgroundOpacity) else Color.Transparent
                    )
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = state.text.replace("\n", " • "),
                    color = Color.White.copy(alpha = state.opacity),
                    fontSize = state.fontSize.sp,
                    textAlign = TextAlign.Left,
                    maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset(offsetPx.roundToInt(), 0) }
                        .onGloballyPositioned { coords ->
                            textWidthPx = coords.size.width
                        }
                        .padding(horizontal = 16.dp)
                )
            }
        }

        // Speed & mirror indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(10.dp))
            Text("${state.speed}x", color = Color.Gray, fontSize = 10.sp)
            Text("•", color = Color.Gray, fontSize = 10.sp)
            Text("${state.fontSize}sp", color = Color.Gray, fontSize = 10.sp)
            if (state.mirrorMode) {
                Text("• MIRROR", color = Color(0xFF3DDC84), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("• ${if (state.verticalMode) "VERT" else "TICKER"}", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

/**
 * Control card for teleprompter settings – appears in MainScreen
 */
@Composable
fun TeleprompterControlCard(
    state: TeleprompterViewModel.TeleprompterState,
    onToggle: () -> Unit,
    onTextChange: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onMirrorToggle: () -> Unit,
    onVerticalToggle: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBackgroundOpacityChange: (Float) -> Unit,
    onShowBackgroundChange: (Boolean) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTextEditor by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isEnabled) Color(0xFF2A1A3D) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Subtitles,
                        contentDescription = null,
                        tint = if (state.isEnabled) Color(0xFF9C27B0) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text("Private Teleprompter", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (state.isEnabled) "ONLY YOU see in preview – NOT in output" else "Preview-only overlay – hidden from stream/record/VCam",
                            color = if (state.isEnabled) Color(0xFF9C27B0) else Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF9C27B0),
                        checkedTrackColor = Color(0xFF9C27B0).copy(alpha = 0.5f)
                    )
                )
            }

            if (state.isEnabled) {
                Divider(color = Color.White.copy(alpha = 0.1f))

                // Text preview + edit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Script", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { showTextEditor = !showTextEditor }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showTextEditor) "Hide Editor" else "Edit Text")
                    }
                }

                if (showTextEditor) {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        placeholder = { Text("Enter your script, notes, talking points...") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF9C27B0),
                            unfocusedBorderColor = Color.Gray
                        ),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        state.text.take(120) + if (state.text.length > 120) "..." else "",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3
                    )
                }

                // Mode toggle: Vertical vs Ticker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mode:", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = state.verticalMode,
                        onClick = { onVerticalToggle() },
                        label = { Text("Teleprompter ↕") },
                        leadingIcon = { Icon(Icons.Default.VerticalAlignCenter, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF9C27B0),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = !state.verticalMode,
                        onClick = { onVerticalToggle() },
                        label = { Text("Ticker ↔") },
                        leadingIcon = { Icon(Icons.Default.HorizontalRule, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF03DAC6),
                            selectedLabelColor = Color.Black
                        )
                    )
                }

                // Speed control
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Speed", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text("${state.speed}x", color = Color(0xFF9C27B0), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = state.speed,
                        onValueChange = onSpeedChange,
                        valueRange = 0.1f..10f,
                        steps = 19,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF9C27B0),
                            activeTrackColor = Color(0xFF9C27B0)
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Slow", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("Fast", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Font size control
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Font Size", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text("${state.fontSize}sp", color = Color(0xFF9C27B0), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = state.fontSize.toFloat(),
                        onValueChange = { onFontSizeChange(it.toInt()) },
                        valueRange = 10f..96f,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF9C27B0),
                            activeTrackColor = Color(0xFF9C27B0)
                        )
                    )
                }

                // Mirror mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Flip, contentDescription = null, tint = if (state.mirrorMode) Color(0xFF3DDC84) else Color.Gray, modifier = Modifier.size(20.dp))
                        Column {
                            Text("Mirror Mode", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Text("Flip for beam-splitter glass", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Switch(
                        checked = state.mirrorMode,
                        onCheckedChange = { onMirrorToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF3DDC84),
                            checkedTrackColor = Color(0xFF3DDC84).copy(alpha = 0.5f)
                        )
                    )
                }

                // Additional controls
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.autoScroll,
                        onClick = { onAutoScrollChange(!state.autoScroll) },
                        label = { Text(if (state.autoScroll) "Auto-Scroll ON" else "Auto-Scroll OFF") },
                        leadingIcon = { Icon(if (state.autoScroll) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = state.loop,
                        onClick = { onLoopChange(!state.loop) },
                        label = { Text(if (state.loop) "Loop ON" else "Loop OFF") },
                        leadingIcon = { Icon(Icons.Default.Loop, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = state.showBackground,
                        onClick = { onShowBackgroundChange(!state.showBackground) },
                        label = { Text("BG") }
                    )
                }

                // Opacity sliders
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Text Opacity", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("${(state.opacity * 100).toInt()}%", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                    Slider(
                        value = state.opacity,
                        onValueChange = onOpacityChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.Gray, activeTrackColor = Color.Gray)
                    )
                }

                Text(
                    "PRIVATE: This overlay is Compose UI on top of PreviewView preview (pure CameraX, ZERO GL). Compositor OutputNode → FrameBus → Virtual Camera / Recording / Streaming NEVER includes it. Only you see it locally.",
                    color = Color(0xFF9C27B0).copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
