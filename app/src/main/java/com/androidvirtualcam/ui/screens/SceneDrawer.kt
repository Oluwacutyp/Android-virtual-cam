package com.androidvirtualcam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidvirtualcam.transition.SlideDirection
import com.androidvirtualcam.transition.TransitionConfig
import com.androidvirtualcam.transition.TransitionEasing
import com.androidvirtualcam.transition.TransitionType
import com.androidvirtualcam.ui.viewmodels.ScenePresetUi
import com.androidvirtualcam.ui.viewmodels.SceneViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneDrawerScreen(
    viewModel: SceneViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presets by viewModel.presets.collectAsState()
    val activePresetId by viewModel.activePresetId.collectAsState()
    val transitionState by viewModel.transitionState.collectAsState()
    val transitionConfig by viewModel.transitionConfig.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scene Presets – Animated Transitions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A)),
                actions = {
                    if (transitionState.isTransitioning) {
                        Badge(containerColor = Color(0xFFFFC107), contentColor = Color.Black) {
                            Text("${(transitionState.progress * 100).toInt()}%")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            )
        },
        containerColor = Color(0xFF0F0F0F)
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Professional Broadcast Scenes", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text("Tap to switch with animated transition – Fade, Slide, Zoom, Stinger", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        if (transitionState.isTransitioning) {
                            LinearProgressIndicator(
                                progress = { transitionState.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF3DDC84)
                            )
                            Text(
                                "Transitioning ${transitionState.fromPresetId} → ${transitionState.toPresetId} • ${transitionConfig.type} ${transitionConfig.durationMs}ms",
                                color = Color(0xFFFFC107),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            item {
                TransitionSelectorCard(
                    config = transitionConfig,
                    state = transitionState,
                    onTypeChange = { type -> viewModel.setTransitionType(type) },
                    onDurationChange = { dur -> viewModel.setTransitionDuration(dur) },
                    onEasingChange = { easing -> viewModel.setTransitionEasing(easing) },
                    onDirectionChange = { dir -> viewModel.setTransitionDirection(dir) }
                )
            }

            item {
                Text("Live Thumbnails – Tap to Switch with Transition", color = Color.White, style = MaterialTheme.typography.titleSmall)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(presets) { preset ->
                        SceneThumbnailCard(
                            preset = preset,
                            isActive = preset.id == activePresetId,
                            onClick = { viewModel.switchPreset(preset.id, withTransition = true) }
                        )
                    }
                }
            }

            item {
                Text("All Presets – Professional Transitions", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }

            items(presets.chunked(2)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { preset ->
                        ScenePresetRow(
                            preset = preset,
                            isActive = preset.id == activePresetId,
                            onClick = { viewModel.switchPreset(preset.id, withTransition = true) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A2A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How Transitions Work", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text("• CUT: instant hard cut (0ms) – no animation", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• FADE: crossfade mix(from,to,progress) with easing", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• SLIDE: push slide – from slides out, to slides in from direction (LEFT/RIGHT/UP/DOWN)", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• ZOOM: from zoom out 1→1.5 + fade, to zoom in 0.5→1 – dynamic professional look", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("• STINGER: video clip transition – circle wipe + white flash + optional video luma matte, cut at 50%", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("Engine: TransitionManager freezes current frame to FBO, loads new graph to secondary, TransitionNode blends with GPU shader at 60fps", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("Shader: single shader handles all types via uType uniform, supports OES+2D, stinger video texture optional", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitionSelectorCard(
    config: TransitionConfig,
    state: com.androidvirtualcam.transition.TransitionState,
    onTypeChange: (TransitionType) -> Unit,
    onDurationChange: (Long) -> Unit,
    onEasingChange: (TransitionEasing) -> Unit,
    onDirectionChange: (SlideDirection) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Animation, contentDescription = null, tint = Color(0xFF3DDC84), modifier = Modifier.size(24.dp))
                    Text("Transition", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                if (state.isTransitioning) {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFFFFC107),
                        strokeWidth = 2.dp
                    )
                }
            }

            Divider(color = Color.White.copy(alpha = 0.1f))

            // Type selector
            Text("Type – Professional Broadcast", color = Color.White, style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TransitionType.values().toList()) { type ->
                    val isSelected = config.type == type.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { onTypeChange(type) },
                        label = { Text(type.displayName) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3DDC84),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            Text(
                TransitionType.fromString(config.type).description,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )

            // Duration slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Duration", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text("${config.durationMs}ms", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = config.durationMs.toFloat(),
                    onValueChange = { onDurationChange(it.toLong()) },
                    valueRange = 0f..2000f,
                    steps = 19,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF3DDC84), activeTrackColor = Color(0xFF3DDC84))
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0ms CUT", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    Text("2000ms Slow", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Easing selector
            Text("Easing", color = Color.White, style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TransitionEasing.values().toList()) { easing ->
                    val isSelected = config.easing == easing.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { onEasingChange(easing) },
                        label = { Text(easing.displayName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF03DAC6),
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            // Direction for slide
            if (config.type.contains("SLIDE")) {
                Text("Slide Direction", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlideDirection.values().forEach { dir ->
                        val isSelected = config.slideDirection == dir.name
                        FilterChip(
                            selected = isSelected,
                            onClick = { onDirectionChange(dir) },
                            label = { Text(dir.name) },
                            leadingIcon = {
                                Icon(
                                    when (dir) {
                                        SlideDirection.LEFT -> Icons.Default.ArrowBack
                                        SlideDirection.RIGHT -> Icons.Default.ArrowForward
                                        SlideDirection.UP -> Icons.Default.ArrowUpward
                                        SlideDirection.DOWN -> Icons.Default.ArrowDownward
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFFC107),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }
            }

            // Stinger config
            if (config.type == TransitionType.STINGER.name) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Stinger – Video Transition Clip", color = Color(0xFFFFC107), style = MaterialTheme.typography.labelMedium)
                        Text("• Video clip plays over transition, cut happens at 50% when video covers screen", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("• Procedural fallback: circle wipe + white flash if no video provided", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("• Supports VideoFileNode – MediaPlayer → OES texture → luma matte", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("Current: ${if (config.stingerVideoPath.isEmpty()) "Procedural (no video)" else config.stingerVideoPath}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Cut point: ${(config.stingerCutPoint * 100).toInt()}% – when hard cut from from to to", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Progress preview
            if (state.isTransitioning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Live Progress", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF3DDC84),
                        trackColor = Color(0xFF2D2D2D)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("From: ${state.fromPresetId}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("To: ${state.toPresetId}", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Eased: ${String.format("%.2f", state.easedProgress)} • Elapsed: ${state.elapsedMs}ms / ${config.durationMs}ms", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SceneThumbnailCard(
    preset: ScenePresetUi,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .height(120.dp)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = if (isActive) Color(0xFF3DDC84) else Color(0xFF3A3A3A),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 8.dp else 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    when (preset.id) {
                        "main_camera" -> Color(0xFF2196F3).copy(alpha = 0.3f)
                        "background_segmentation" -> Color(0xFF9C27B0).copy(alpha = 0.3f)
                        "background_blur" -> Color(0xFFFF9800).copy(alpha = 0.3f)
                        "green_screen" -> Color(0xFF4CAF50).copy(alpha = 0.3f)
                        "pip" -> Color(0xFF00BCD4).copy(alpha = 0.3f)
                        "interview" -> Color(0xFF795548).copy(alpha = 0.3f)
                        "gaming_overlay" -> Color(0xFFFF5722).copy(alpha = 0.3f)
                        "cinematic" -> Color(0xFFE91E63).copy(alpha = 0.3f)
                        "presentation" -> Color(0xFF607D8B).copy(alpha = 0.3f)
                        "news_broadcast" -> Color(0xFF3F51B5).copy(alpha = 0.3f)
                        "screen_share" -> Color(0xFF009688).copy(alpha = 0.4f)
                        "tutorial" -> Color(0xFF673AB7).copy(alpha = 0.4f)
                        "screen_pip" -> Color(0xFF795548).copy(alpha = 0.4f)
                        "gaming_screen" -> Color(0xFFFF5722).copy(alpha = 0.4f)
                        else -> Color(0xFF2D2D2D)
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Replaced iconForPreset(p) with emoji string – fixes compile error where iconForPreset returned ImageVector but Text expected String
                    Text(
                        text = iconForPreset(preset.id),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("LIVE", color = Color(0xFF3DDC84), style = MaterialTheme.typography.labelSmall)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                Column {
                    Text(preset.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(preset.id, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }

            if (isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF3DDC84))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("ACTIVE", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun iconForPreset(id: String): String {
    return when (id) {
        "main_camera" -> "📹"
        "screen_share" -> "🖥️"
        "tutorial" -> "🎓"
        "gaming_screen" -> "🎮"
        "screen_pip" -> "🖼️"
        "background_segmentation" -> "👤"
        "background_blur" -> "🌫️"
        "green_screen" -> "🟩"
        "pip" -> "🖼️"
        "interview" -> "👥"
        "gaming_overlay" -> "🎮"
        "cinematic" -> "🎬"
        "presentation" -> "📊"
        "news_broadcast" -> "📺"
        else -> "🎬"
    }
}

@Composable
private fun ScenePresetRow(
    preset: ScenePresetUi,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = if (isActive) Color(0xFF3DDC84) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2D2D2D)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = iconForPreset(preset.id), style = MaterialTheme.typography.titleLarge)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text(preset.description, color = Color.Gray, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (isActive) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF3DDC84))
            }
        }
    }
}
