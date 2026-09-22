package com.androidvirtualcam.scene

import org.junit.Assert.*
import org.junit.Test

class SceneTest {

    @Test
    fun testDefaultSceneHasLayers() {
        val scene = Scene.defaultScene()
        assertTrue(scene.layers.isNotEmpty())
        assertTrue(scene.layers.any { it is Layer.CameraLayer })
        assertTrue(scene.layers.any { it is Layer.ColorLayer })
    }

    @Test
    fun testSecondSceneHasPiP() {
        val scene = Scene.secondScene()
        val camera = scene.layers.filterIsInstance<Layer.CameraLayer>().firstOrNull()
        assertNotNull(camera)
        assertTrue(camera!!.transform.scaleX < 1f)
        assertTrue(camera.transform.x > 0.5f)
    }

    @Test
    fun testManyCamPresets() {
        val presets = Scene.manyCamPresets()
        assertTrue(presets.size >= 6)
        assertTrue(presets.any { it.category == SceneCategory.VIRTUAL_BACKGROUND })
        assertTrue(presets.any { it.category == SceneCategory.INTERVIEW })
        assertTrue(presets.any { it.category == SceneCategory.GAMING })
    }

    @Test
    fun testChromaKeyScene() {
        val scene = Scene.chromaKeyScene()
        val cam = scene.layers.filterIsInstance<Layer.CameraLayer>().firstOrNull()
        assertNotNull(cam)
        assertEquals(FilterType.CHROMA_KEY, cam!!.filter)
        assertNotNull(cam.chromaKey)
        assertTrue(cam.chromaKey!!.isEnabled)
    }

    @Test
    fun testAddLayerAssignsZIndex() {
        val scene = Scene.defaultScene()
        val initialMaxZ = scene.layers.maxOf { it.zIndex }
        val newLayer = Layer.TextLayer(name = "New", text = "Test")
        val updated = scene.withLayerAdded(newLayer)
        assertEquals(scene.layers.size + 1, updated.layers.size)
        val added = updated.layers.find { it.name == "New" }
        assertNotNull(added)
        assertTrue(added!!.zIndex >= initialMaxZ)
    }

    @Test
    fun testRemoveLayer() {
        val scene = Scene.defaultScene()
        val firstId = scene.layers.first().id
        val updated = scene.withLayerRemoved(firstId)
        assertEquals(scene.layers.size - 1, updated.layers.size)
        assertNull(updated.findLayer(firstId))
    }

    @Test
    fun testUpdateTransform() {
        val scene = Scene.defaultScene()
        val layer = scene.layers.first()
        val newTransform = Transform(x = 0.2f, y = 0.8f, scaleX = 0.5f)
        val updated = scene.withLayerTransform(layer.id, newTransform)
        val found = updated.findLayer(layer.id)
        assertNotNull(found)
        assertEquals(0.2f, found!!.transform.x, 0.001f)
        assertEquals(0.8f, found.transform.y, 0.001f)
    }

    @Test
    fun testToggleEnabled() {
        val scene = Scene.defaultScene()
        val layer = scene.layers.first()
        val disabled = scene.withLayerEnabled(layer.id, false)
        assertFalse(disabled.findLayer(layer.id)!!.isEnabled)
        assertFalse(disabled.sortedLayers().any { it.id == layer.id })

        val enabled = disabled.withLayerEnabled(layer.id, true)
        assertTrue(enabled.findLayer(layer.id)!!.isEnabled)
    }

    @Test
    fun testReorder() {
        val scene = Scene.defaultScene()
        val layer = scene.layers.first()
        val reordered = scene.withReordered(layer.id, 10)
        val indices = reordered.layers.map { it.zIndex }.sorted()
        for (i in indices.indices) {
            assertEquals(i, indices[i])
        }
    }

    @Test
    fun testTransformConstraints() {
        val t = Transform(alpha = 0.5f, scaleX = 1f)
        assertEquals(0.5f, t.alpha, 0.001f)

        val t2 = t.withAlpha(2f)
        assertEquals(1f, t2.alpha, 0.001f)
        val t3 = t.withAlpha(-1f)
        assertEquals(0f, t3.alpha, 0.001f)

        val moved = t.moved(10f, 10f)
        assertTrue(moved.x <= 1f && moved.y <= 1f)
        assertTrue(moved.x >= 0f && moved.y >= 0f)

        val scaled = t.scaled(100f)
        assertTrue(scaled.scaleX <= 5f)
        val scaledSmall = t.scaled(0.01f)
        assertTrue(scaledSmall.scaleX >= 0.1f)
    }

    @Test
    fun testSceneCollectionActive() {
        val col = SceneCollection()
        assertTrue(col.scenes.size >= 6) // ManyCam presets
        assertNotNull(col.activeScene())
        val secondId = col.scenes[1].id
        val switched = col.withActiveScene(secondId)
        assertEquals(secondId, switched.activeSceneId)
        assertEquals(secondId, switched.activeScene()?.id)
    }

    @Test
    fun testSceneCollectionRemoveKeepsOne() {
        var col = SceneCollection()
        val firstId = col.scenes.first().id
        col = col.withSceneRemoved(firstId)
        assertTrue(col.scenes.size >= 1)
        // Try to remove all but one
        while (col.scenes.size > 1) {
            col = col.withSceneRemoved(col.scenes.first().id)
        }
        assertEquals(1, col.scenes.size)
        col = col.withSceneRemoved(col.scenes.first().id)
        assertEquals(1, col.scenes.size)
    }

    @Test
    fun testSerialization() {
        val col = SceneCollection()
        val json = SceneRepository.encode(col)
        assertTrue(json.contains("Scene"))
        val decoded = SceneRepository.decode(json)
        assertEquals(col.scenes.size, decoded.scenes.size)
        assertEquals(col.activeSceneId, decoded.activeSceneId)
        assertEquals(col.activeScene()?.layers?.size, decoded.activeScene()?.layers?.size)
    }

    @Test
    fun testInMemoryRepository() {
        val repo = InMemorySceneRepository()
        var observed: SceneCollection? = null
        repo.observe { observed = it }
        assertNotNull(observed)
        val initialSize = repo.get().scenes.size
        repo.update { it.withSceneAdded(Scene.defaultScene("Test")) }
        assertEquals(initialSize + 1, repo.get().scenes.size)
        assertEquals(initialSize + 1, observed?.scenes?.size)
    }

    @Test
    fun testLayerCopyWith() {
        val layer = Layer.TextLayer(text = "Hello")
        val newTransform = Transform(x = 0.1f)
        val copied = layer.copyWithTransform(newTransform)
        assertEquals(0.1f, copied.transform.x, 0.001f)
        assertEquals("Hello", (copied as Layer.TextLayer).text)

        val disabled = layer.copyWithEnabled(false)
        assertFalse(disabled.isEnabled)

        val reZ = layer.copyWithZIndex(5)
        assertEquals(5, reZ.zIndex)

        val newEffects = LayerEffects(blurRadius = 10f)
        val withEffects = layer.copyWithEffects(newEffects)
        assertEquals(10f, withEffects.effects.blurRadius, 0.001f)
    }

    @Test
    fun testVideoLayer() {
        val layer = Layer.VideoLayer(name = "Video", videoUri = "file://test.mp4", isLooping = true)
        assertEquals("file://test.mp4", layer.videoUri)
        assertTrue(layer.isLooping)
    }

    @Test
    fun testVoiceChangerPresets() {
        val presets = com.androidvirtualcam.voice.VoiceChanger.VoiceProfile.presets()
        assertTrue(presets.size >= 5)
        assertTrue(presets.any { it.effect == "chipmunk" })
        assertTrue(presets.any { it.effect == "robot" })
    }

    @Test
    fun testChromaKeyConfig() {
        val config = ChromaKeyConfig(keyColor = 0xFF00FF00, threshold = 0.4f, slope = 0.1f, isEnabled = true)
        assertEquals(0xFF00FF00, config.keyColor)
        assertTrue(config.isEnabled)
    }

    @Test
    fun testLayerEffects() {
        val effects = LayerEffects(opacity = 0.8f, brightness = 0.2f, contrast = 1.2f, blurRadius = 5f)
        assertEquals(0.8f, effects.opacity, 0.001f)
        assertEquals(5f, effects.blurRadius, 0.001f)
    }
}
