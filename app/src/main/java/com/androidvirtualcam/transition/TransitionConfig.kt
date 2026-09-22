package com.androidvirtualcam.transition

import kotlinx.serialization.Serializable

/**
 * Config for a transition – serializable for persistence.
 */
@Serializable
data class TransitionConfig(
    val type: String = TransitionType.FADE.name,
    val durationMs: Long = 500L,
    val easing: String = TransitionEasing.EASE_IN_OUT.name,
    val slideDirection: String = SlideDirection.LEFT.name,
    val stingerVideoPath: String = "", // path to video file for stinger, empty = procedural
    val stingerCutPoint: Float = 0.5f, // 0..1 where cut happens in stinger (usually 0.5)
    val stingerScale: Float = 1f,
    val audioFade: Boolean = true
) {
    val transitionType: TransitionType get() = TransitionType.fromString(type)
    val easingType: TransitionEasing get() = try { TransitionEasing.valueOf(easing) } catch (_: Exception) { TransitionEasing.EASE_IN_OUT }
    val direction: SlideDirection get() = try { SlideDirection.valueOf(slideDirection) } catch (_: Exception) { SlideDirection.LEFT }

    fun withType(newType: TransitionType) = copy(type = newType.name)
    fun withDuration(ms: Long) = copy(durationMs = ms.coerceIn(50L, 5000L))
    fun withEasing(newEasing: TransitionEasing) = copy(easing = newEasing.name)
    fun withDirection(dir: SlideDirection) = copy(slideDirection = dir.name)
    fun withStingerPath(path: String) = copy(stingerVideoPath = path)

    companion object {
        fun default() = TransitionConfig()
        fun fade(duration: Long = 500) = TransitionConfig(type = TransitionType.FADE.name, durationMs = duration)
        fun slideLeft(duration: Long = 400) = TransitionConfig(type = TransitionType.SLIDE_LEFT.name, durationMs = duration, slideDirection = SlideDirection.LEFT.name)
        fun slideRight(duration: Long = 400) = TransitionConfig(type = TransitionType.SLIDE_RIGHT.name, durationMs = duration, slideDirection = SlideDirection.RIGHT.name)
        fun zoom(duration: Long = 600) = TransitionConfig(type = TransitionType.ZOOM.name, durationMs = duration)
        fun stinger(duration: Long = 1000, videoPath: String = "") = TransitionConfig(type = TransitionType.STINGER.name, durationMs = duration, stingerVideoPath = videoPath)

        fun presets(): List<TransitionConfig> = listOf(
            TransitionConfig(type = TransitionType.CUT.name, durationMs = 0),
            fade(300),
            fade(600),
            slideLeft(400),
            TransitionConfig(type = TransitionType.SLIDE_RIGHT.name, durationMs = 400, slideDirection = SlideDirection.RIGHT.name),
            TransitionConfig(type = TransitionType.SLIDE_UP.name, durationMs = 400, slideDirection = SlideDirection.UP.name),
            TransitionConfig(type = TransitionType.SLIDE_DOWN.name, durationMs = 400, slideDirection = SlideDirection.DOWN.name),
            zoom(500),
            TransitionConfig(type = TransitionType.ZOOM_IN.name, durationMs = 500),
            TransitionConfig(type = TransitionType.ZOOM_OUT.name, durationMs = 500),
            stinger(1000)
        )
    }
}

@Serializable
data class TransitionState(
    val isTransitioning: Boolean = false,
    val progress: Float = 0f, // 0..1
    val easedProgress: Float = 0f,
    val fromPresetId: String = "",
    val toPresetId: String = "",
    val config: TransitionConfig = TransitionConfig.default(),
    val elapsedMs: Long = 0L
)
