package com.androidvirtualcam.scene

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Sealed hierarchy for all layer types – ManyCam-like.
 * Each layer has unique id, transform, visibility, zIndex.
 *
 * Free & open-source: no watermark, no proprietary lock-in.
 */
@Serializable
sealed class Layer {
    abstract val id: String
    abstract val name: String
    abstract val transform: Transform
    abstract val isEnabled: Boolean
    abstract val zIndex: Int
    abstract val blendMode: BlendMode
    abstract val effects: LayerEffects

    abstract fun copyWithTransform(newTransform: Transform): Layer
    abstract fun copyWithEnabled(enabled: Boolean): Layer
    abstract fun copyWithZIndex(newZ: Int): Layer
    abstract fun copyWithEffects(newEffects: LayerEffects): Layer

    @Serializable
    @SerialName("camera")
    data class CameraLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Camera",
        override val transform: Transform = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = 0,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val facing: CameraFacing = CameraFacing.BACK,
        val filter: FilterType = FilterType.NONE,
        val isMirrored: Boolean = false,
        val chromaKey: ChromaKeyConfig? = null, // for virtual background green screen
        val backgroundBlur: BackgroundBlurConfig? = null // blur background without ML or with ML
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }

    @Serializable
    @SerialName("image")
    data class ImageLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Image",
        override val transform: Transform = Transform(),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = 1,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val imageUri: String? = null, // content:// or file path
        val imageResName: String? = null, // for bundled drawables fallback
        val fitMode: FitMode = FitMode.CONTAIN
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }

    @Serializable
    @SerialName("video")
    data class VideoLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Video",
        override val transform: Transform = Transform(),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = 1,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val videoUri: String? = null,
        val isLooping: Boolean = true,
        val isMuted: Boolean = true,
        val playbackSpeed: Float = 1f
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }

    @Serializable
    @SerialName("slideshow")
    data class SlideshowLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Slideshow",
        override val transform: Transform = Transform(),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = 2,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val imageUris: List<String> = emptyList(),
        val intervalSec: Float = 3f,
        val transition: TransitionType = TransitionType.FADE
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }

    @Serializable
    @SerialName("text")
    data class TextLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Text",
        override val transform: Transform = Transform(),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = 2,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val text: String = "Hello",
        val textColor: Long = 0xFFFFFFFF, // ARGB as Long for serialization
        val backgroundColor: Long = 0x00000000,
        val fontSizeSp: Float = 24f,
        val isBold: Boolean = false,
        val style: TextStyle = TextStyle.NORMAL,
        val lowerThird: LowerThirdConfig? = null
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }

    @Serializable
    @SerialName("color")
    data class ColorLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Background",
        override val transform: Transform = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = -1,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val color: Long = 0xFF121212 // ARGB
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }

    @Serializable
    @SerialName("web")
    data class WebLayer(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Web Source",
        override val transform: Transform = Transform(),
        override val isEnabled: Boolean = true,
        override val zIndex: Int = 3,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: LayerEffects = LayerEffects(),
        val url: String = "https://example.com",
        val width: Int = 1280,
        val height: Int = 720
    ) : Layer() {
        override fun copyWithTransform(newTransform: Transform) = copy(transform = newTransform)
        override fun copyWithEnabled(enabled: Boolean) = copy(isEnabled = enabled)
        override fun copyWithZIndex(newZ: Int) = copy(zIndex = newZ)
        override fun copyWithEffects(newEffects: LayerEffects) = copy(effects = newEffects)
    }
}

@Serializable
enum class CameraFacing { FRONT, BACK }

@Serializable
enum class FilterType {
    NONE,
    GRAYSCALE,
    SEPIA,
    INVERT,
    BRIGHTNESS,
    BLUR,          // ManyCam-like blur
    CHROMA_KEY,    // Green screen
    VIGNETTE,
    PIXELATE,
    EDGE_DETECT,
    BEAUTY,        // simple skin smoothing
    BACKGROUND_BLUR // blur background, keep foreground (needs segmentation, fallback to full blur)
}

@Serializable
enum class BlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    ADD
}

@Serializable
enum class FitMode {
    CONTAIN,
    COVER,
    FILL,
    FIT_WIDTH
}

@Serializable
enum class TransitionType {
    CUT,
    FADE,
    SLIDE,
    ZOOM
}

@Serializable
enum class TextStyle {
    NORMAL,
    LOWER_THIRD,
    TITLE,
    SUBTITLE,
    CHAT_BUBBLE
}

@Serializable
data class LayerEffects(
    val opacity: Float = 1f,
    val brightness: Float = 0f, // -1..1
    val contrast: Float = 1f,   // 0..2
    val saturation: Float = 1f, // 0..2
    val blurRadius: Float = 0f, // 0..25
    val vignetteStrength: Float = 0f,
    val isFlippedH: Boolean = false,
    val isFlippedV: Boolean = false
)

@Serializable
data class ChromaKeyConfig(
    val keyColor: Long = 0xFF00FF00, // green
    val threshold: Float = 0.4f, // 0..1
    val slope: Float = 0.1f, // edge softness
    val isEnabled: Boolean = true
)

@Serializable
data class BackgroundBlurConfig(
    val blurRadius: Float = 15f,
    val isEnabled: Boolean = false,
    val useSegmentation: Boolean = false // if true, would use ML Kit segmentation (future)
)

@Serializable
data class LowerThirdConfig(
    val title: String = "Guest Speaker",
    val subtitle: String = "Android Virtual Cam - Free",
    val accentColor: Long = 0xFF3DDC84,
    val style: LowerThirdStyle = LowerThirdStyle.MODERN
)

@Serializable
enum class LowerThirdStyle {
    MODERN,
    CLASSIC,
    BOLD,
    MINIMAL
}
