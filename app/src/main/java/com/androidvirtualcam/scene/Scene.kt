package com.androidvirtualcam.scene

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Scene ${id.take(4)}",
    val layers: List<Layer> = emptyList(),
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val frameRate: Int = 30,
    val category: SceneCategory = SceneCategory.CUSTOM
) {
    fun sortedLayers(): List<Layer> = layers.filter { it.isEnabled }.sortedBy { it.zIndex }

    fun findLayer(layerId: String): Layer? = layers.find { it.id == layerId }

    fun withLayerAdded(layer: Layer): Scene {
        val maxZ = layers.maxOfOrNull { it.zIndex } ?: 0
        val layerWithZ = if (layers.any { it.zIndex == layer.zIndex }) {
            layer.copyWithZIndex(maxZ + 1)
        } else layer
        return copy(layers = layers + layerWithZ)
    }

    fun withLayerRemoved(layerId: String): Scene = copy(layers = layers.filterNot { it.id == layerId })

    fun withLayerUpdated(updated: Layer): Scene = copy(layers = layers.map { if (it.id == updated.id) updated else it })

    fun withLayerTransform(layerId: String, newTransform: Transform): Scene {
        return withLayerUpdated(findLayer(layerId)?.copyWithTransform(newTransform) ?: return this)
    }

    fun withLayerEnabled(layerId: String, enabled: Boolean): Scene {
        return withLayerUpdated(findLayer(layerId)?.copyWithEnabled(enabled) ?: return this)
    }

    fun withLayerEffects(layerId: String, effects: LayerEffects): Scene {
        return withLayerUpdated(findLayer(layerId)?.copyWithEffects(effects) ?: return this)
    }

    fun withReordered(layerId: String, newZIndex: Int): Scene {
        val layer = findLayer(layerId) ?: return this
        val mutable = layers.toMutableList()
        mutable.remove(layer)
        mutable.add(layer.copyWithZIndex(newZIndex))
        val sorted = mutable.sortedBy { it.zIndex }
        val reassigned = sorted.mapIndexed { idx, l -> l.copyWithZIndex(idx) }
        return copy(layers = reassigned)
    }

    companion object {
        fun defaultScene(name: String = "Scene 1"): Scene {
            return Scene(
                name = name,
                category = SceneCategory.CAMERA,
                layers = listOf(
                    Layer.ColorLayer(name = "Background", color = 0xFF101010, zIndex = 0),
                    Layer.CameraLayer(name = "Camera", transform = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f), zIndex = 1),
                    Layer.TextLayer(name = "Title", text = "Live Studio - Free", transform = Transform(x = 0.5f, y = 0.15f, scaleX = 1f, scaleY = 1f), zIndex = 2)
                )
            )
        }

        fun secondScene(name: String = "Scene 2 - PiP"): Scene {
            return Scene(
                name = name,
                category = SceneCategory.PIP,
                layers = listOf(
                    Layer.ColorLayer(name = "Background 2", color = 0xFF202030, zIndex = 0),
                    Layer.CameraLayer(name = "Camera PiP", transform = Transform(x = 0.8f, y = 0.8f, scaleX = 0.4f, scaleY = 0.4f), zIndex = 1),
                    Layer.TextLayer(name = "Subtitle", text = "Second Layout", transform = Transform(x = 0.5f, y = 0.85f), zIndex = 2, fontSizeSp = 20f),
                    Layer.ImageLayer(name = "Logo", transform = Transform(x = 0.2f, y = 0.2f, scaleX = 0.5f, scaleY = 0.5f), zIndex = 3)
                )
            )
        }

        // ManyCam-like presets
        fun interviewScene(name: String = "Interview"): Scene {
            return Scene(
                name = name,
                category = SceneCategory.INTERVIEW,
                layers = listOf(
                    Layer.ColorLayer(name = "BG", color = 0xFF1A1A2E, zIndex = 0),
                    Layer.CameraLayer(name = "Main Cam", transform = Transform(x = 0.3f, y = 0.5f, scaleX = 0.6f, scaleY = 0.6f), zIndex = 1),
                    Layer.CameraLayer(name = "Guest Cam", transform = Transform(x = 0.75f, y = 0.5f, scaleX = 0.45f, scaleY = 0.45f), zIndex = 2, facing = CameraFacing.FRONT),
                    Layer.TextLayer(
                        name = "Lower Third",
                        text = "Guest Speaker",
                        transform = Transform(x = 0.3f, y = 0.9f),
                        zIndex = 3,
                        style = TextStyle.LOWER_THIRD,
                        lowerThird = LowerThirdConfig(title = "Guest Speaker", subtitle = "Free ManyCam Alternative")
                    )
                )
            )
        }

        fun gamingScene(name: String = "Gaming Overlay"): Scene {
            return Scene(
                name = name,
                category = SceneCategory.GAMING,
                layers = listOf(
                    Layer.ColorLayer(name = "BG", color = 0xFF0F0F0F, zIndex = 0),
                    Layer.ImageLayer(name = "Game Capture Placeholder", transform = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f), zIndex = 1),
                    Layer.CameraLayer(name = "Face Cam", transform = Transform(x = 0.85f, y = 0.85f, scaleX = 0.3f, scaleY = 0.3f), zIndex = 2),
                    Layer.TextLayer(name = "Stream Title", text = "LIVE - Free Cam", transform = Transform(x = 0.5f, y = 0.08f), zIndex = 3, fontSizeSp = 18f, isBold = true)
                )
            )
        }

        fun presentationScene(name: String = "Presentation"): Scene {
            return Scene(
                name = name,
                category = SceneCategory.PRESENTATION,
                layers = listOf(
                    Layer.ColorLayer(name = "BG", color = 0xFFFFFFFF, zIndex = 0),
                    Layer.ImageLayer(name = "Slides Placeholder", transform = Transform(x = 0.5f, y = 0.45f, scaleX = 0.9f, scaleY = 0.7f), zIndex = 1),
                    Layer.CameraLayer(name = "Presenter", transform = Transform(x = 0.85f, y = 0.85f, scaleX = 0.25f, scaleY = 0.25f), zIndex = 2),
                    Layer.TextLayer(name = "Footer", text = "Android Virtual Cam - 100% Free & Open Source", transform = Transform(x = 0.5f, y = 0.95f), zIndex = 3, fontSizeSp = 14f, textColor = 0xFF000000)
                )
            )
        }

        fun chromaKeyScene(name: String = "Chroma Key"): Scene {
            return Scene(
                name = name,
                category = SceneCategory.VIRTUAL_BACKGROUND,
                layers = listOf(
                    Layer.ImageLayer(name = "Virtual Background", transform = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f), zIndex = 0),
                    Layer.CameraLayer(
                        name = "Camera Chroma",
                        transform = Transform(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f),
                        zIndex = 1,
                        filter = FilterType.CHROMA_KEY,
                        chromaKey = ChromaKeyConfig(keyColor = 0xFF00FF00, threshold = 0.4f, slope = 0.1f, isEnabled = true)
                    ),
                    Layer.TextLayer(name = "Info", text = "Green Screen - Free", transform = Transform(x = 0.5f, y = 0.9f), zIndex = 2)
                )
            )
        }

        fun manyCamPresets(): List<Scene> = listOf(
            defaultScene("Main Cam"),
            secondScene("Picture-in-Picture"),
            interviewScene(),
            gamingScene(),
            presentationScene(),
            chromaKeyScene()
        )
    }
}

@Serializable
enum class SceneCategory {
    CUSTOM,
    CAMERA,
    PIP,
    INTERVIEW,
    GAMING,
    PRESENTATION,
    VIRTUAL_BACKGROUND,
    LOWER_THIRD
}

@Serializable
data class SceneCollection(
    val scenes: List<Scene> = Scene.manyCamPresets(),
    val activeSceneId: String = scenes.firstOrNull()?.id ?: ""
) {
    fun activeScene(): Scene? = scenes.find { it.id == activeSceneId }

    fun withActiveScene(id: String): SceneCollection {
        require(scenes.any { it.id == id }) { "Scene id not found" }
        return copy(activeSceneId = id)
    }

    fun withSceneUpdated(updated: Scene): SceneCollection = copy(scenes = scenes.map { if (it.id == updated.id) updated else it })

    fun withSceneAdded(scene: Scene): SceneCollection = copy(scenes = scenes + scene)

    fun withSceneRemoved(id: String): SceneCollection {
        if (scenes.size <= 1) return this
        val newScenes = scenes.filterNot { it.id == id }
        val newActive = if (activeSceneId == id) newScenes.first().id else activeSceneId
        return copy(scenes = newScenes, activeSceneId = newActive)
    }

    fun withSceneDuplicated(id: String): SceneCollection {
        val scene = scenes.find { it.id == id } ?: return this
        val duplicated = scene.copy(id = java.util.UUID.randomUUID().toString(), name = "${scene.name} Copy")
        return copy(scenes = scenes + duplicated, activeSceneId = duplicated.id)
    }
}
