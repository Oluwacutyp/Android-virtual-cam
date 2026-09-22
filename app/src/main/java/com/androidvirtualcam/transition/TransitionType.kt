package com.androidvirtualcam.transition

/**
 * Professional broadcast transitions – OBS-like.
 * Fade, Slide (4 directions), Zoom, Stinger (video clip)
 */
enum class TransitionType(val displayName: String, val description: String) {
    CUT("Cut", "Instant hard cut – no animation"),
    FADE("Fade", "Crossfade – smooth opacity blend"),
    SLIDE_LEFT("Slide Left", "New scene slides from right, pushes old left"),
    SLIDE_RIGHT("Slide Right", "New scene slides from left, pushes old right"),
    SLIDE_UP("Slide Up", "New scene slides from bottom, pushes old up"),
    SLIDE_DOWN("Slide Down", "New scene slides from top, pushes old down"),
    ZOOM("Zoom", "Old zooms out + fade, new zooms in – dynamic"),
    ZOOM_IN("Zoom In", "Zoom into new scene from center"),
    ZOOM_OUT("Zoom Out", "Zoom out from old scene to new"),
    STINGER("Stinger", "Video transition clip – professional broadcast stinger");

    companion object {
        fun broadcastTypes() = listOf(FADE, SLIDE_LEFT, SLIDE_RIGHT, ZOOM, STINGER)
        fun allTypes() = values().toList()
        fun fromString(name: String): TransitionType {
            return try {
                valueOf(name)
            } catch (_: Exception) {
                FADE
            }
        }
    }
}

enum class TransitionEasing(val displayName: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
    BOUNCE("Bounce"),
    ELASTIC("Elastic");

    fun apply(t: Float): Float {
        return when (this) {
            LINEAR -> t
            EASE_IN -> t * t // quad in
            EASE_OUT -> t * (2f - t) // quad out
            EASE_IN_OUT -> if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t
            BOUNCE -> {
                // Simple bounce
                if (t < 0.5f) {
                    4f * t * t * t
                } else {
                    val f = 2f * t - 2f
                    0.5f * f * f * f + 1f
                }
            }
            ELASTIC -> {
                if (t == 0f || t == 1f) t else {
                    val p = 0.3f
                    val s = p / 4f
                    val t2 = t - 1f
                    -Math.pow(2.0, (10 * t2).toDouble()).toFloat() * kotlin.math.sin(((t2 - s) * (2 * Math.PI) / p).toFloat())
                }
            }
        }.coerceIn(0f, 1f)
    }
}

enum class SlideDirection {
    LEFT, RIGHT, UP, DOWN
}
