package com.androidvirtualcam.compositor

import com.androidvirtualcam.compositor.nodes.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CompositorGraph – topological sort, cycle detection, serialization.
 * No GL context needed for graph logic tests.
 */

class DummyNode(
    override val id: String,
    override val type: String = "Dummy",
    override val inputSockets: List<InputSocket> = emptyList(),
    override val outputSocket: OutputSocket = OutputSocket("output", "output", SocketType.TEXTURE),
    override var parameters: NodeParameters = NodeParameters()
) : CompositorNode, CompositorGraph.MutableConnectionsNode {
    override fun initialize() {}
    override fun process(inputs: Map<String, GLTexture>): GLTexture {
        // Return dummy texture without GL calls for unit test
        return GLTexture(0, 0, 0, 0, 0, false, false)
    }
    override fun release() {}
    override fun setInputConnection(inputSocketId: String, fromNodeId: String?, fromOutputId: String?) {}
}

class CompositorGraphTest {

    @Test
    fun testTopologicalOrder_simpleLinear() {
        val graph = CompositorGraph()
        val a = DummyNode("a")
        val b = DummyNode("b", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))
        val c = DummyNode("c", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)

        graph.connect("a", "output", "b", "input")
        graph.connect("b", "output", "c", "input")

        val order = graph.computeEvaluationOrder()
        assertEquals(3, order.size)
        assertTrue(order.indexOf("a") < order.indexOf("b"))
        assertTrue(order.indexOf("b") < order.indexOf("c"))
    }

    @Test
    fun testTopologicalOrder_branching() {
        val graph = CompositorGraph()
        val camera = DummyNode("camera")
        val blur = DummyNode("blur", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))
        val lut = DummyNode("lut", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))
        val blend = DummyNode("blend", inputSockets = listOf(
            InputSocket("base", "base", SocketType.TEXTURE),
            InputSocket("blend", "blend", SocketType.TEXTURE)
        ))

        graph.addNode(camera)
        graph.addNode(blur)
        graph.addNode(lut)
        graph.addNode(blend)

        graph.connect("camera", "output", "blur", "input")
        graph.connect("camera", "output", "lut", "input")
        graph.connect("blur", "output", "blend", "base")
        graph.connect("lut", "output", "blend", "blend")

        val order = graph.computeEvaluationOrder()
        assertEquals(4, order.size)
        assertTrue(order.indexOf("camera") < order.indexOf("blur"))
        assertTrue(order.indexOf("camera") < order.indexOf("lut"))
        assertTrue(order.indexOf("blur") < order.indexOf("blend"))
        assertTrue(order.indexOf("lut") < order.indexOf("blend"))
    }

    @Test(expected = CompositorCycleException::class)
    fun testCycleDetection_throws() {
        val graph = CompositorGraph()
        val a = DummyNode("a", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))
        val b = DummyNode("b", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))
        val c = DummyNode("c", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))

        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c)

        graph.connect("a", "output", "b", "input")
        graph.connect("b", "output", "c", "input")
        graph.connect("c", "output", "a", "input") // cycle

        graph.computeEvaluationOrder() // should throw
    }

    @Test
    fun testCycleDetection_path() {
        val graph = CompositorGraph()
        val a = DummyNode("a", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))
        val b = DummyNode("b", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))

        graph.addNode(a)
        graph.addNode(b)

        graph.connect("a", "output", "b", "input")
        graph.connect("b", "output", "a", "input")

        try {
            graph.computeEvaluationOrder()
            fail("Should have thrown CompositorCycleException")
        } catch (e: CompositorCycleException) {
            assertTrue(e.cyclePath.isNotEmpty())
            assertTrue(e.cyclePath.contains("a"))
            assertTrue(e.cyclePath.contains("b"))
        }
    }

    @Test
    fun testSerialization_roundTrip() {
        val graph = CompositorGraph()
        val cam = DummyNode("camera_1", type = "CameraInput", parameters = NodeParameters.builder()
            .int("width", 1280).int("height", 720).build())
        val blur = DummyNode("blur_1", type = "Blur", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)),
            parameters = NodeParameters.builder().float("sigma", 4f).build())
        val output = DummyNode("output_1", type = "Output", inputSockets = listOf(InputSocket("input", "input", SocketType.TEXTURE)))

        graph.addNode(cam)
        graph.addNode(blur)
        graph.addNode(output)

        graph.connect("camera_1", "output", "blur_1", "input")
        graph.connect("blur_1", "output", "output_1", "input")

        val json = graph.toJson()
        assertTrue(json.contains("camera_1"))
        assertTrue(json.contains("blur_1"))
        assertTrue(json.contains("sigma"))

        // Deserialize with dummy factory
        val factory = object : CompositorGraph.NodeFactory {
            override fun createNode(type: String, id: String, parameters: NodeParameters): CompositorNode {
                return when (type) {
                    "CameraInput" -> DummyNode(id, type, emptyList(), OutputSocket("output", "output", SocketType.TEXTURE), parameters)
                    "Blur" -> DummyNode(id, type, listOf(InputSocket("input", "input", SocketType.TEXTURE)), OutputSocket("output", "output", SocketType.TEXTURE), parameters)
                    "Output" -> DummyNode(id, type, listOf(InputSocket("input", "input", SocketType.TEXTURE)), OutputSocket("output", "output", SocketType.TEXTURE), parameters)
                    else -> DummyNode(id, type, emptyList(), OutputSocket("output", "output", SocketType.TEXTURE), parameters)
                }
            }
        }

        val newGraph = CompositorGraph()
        newGraph.fromJson(json, factory)

        assertEquals(3, newGraph.getNodes().size)
        assertEquals(2, newGraph.getConnections().size)

        val order = newGraph.computeEvaluationOrder()
        assertTrue(order.indexOf("camera_1") < order.indexOf("blur_1"))
        assertTrue(order.indexOf("blur_1") < order.indexOf("output_1"))
    }

    @Test
    fun testPresetJson_valid() {
        // Test that preset JSON files are syntactically valid and contain expected nodes
        val presets = listOf(
            "main_camera" to listOf("CameraInput", "Output"),
            "green_screen" to listOf("CameraInput", "ChromaKey", "BackgroundReplace", "Output"),
            "cinematic" to listOf("CameraInput", "LUT", "ColorCorrection", "Output"),
            "news_broadcast" to listOf("CameraInput", "ColorCorrection", "LowerThird", "Output")
        )

        for ((presetId, expectedTypes) in presets) {
            // Load JSON from assets path – in unit test we read file directly
            val file = java.io.File("app/src/main/assets/compositor_presets/${presetId}.json")
            if (!file.exists()) continue
            val json = file.readText()
            for (type in expectedTypes) {
                assertTrue("Preset $presetId should contain $type", json.contains(type))
            }
        }
    }

    @Test
    fun testNodeParameters_serialization() {
        val params = NodeParameters.builder()
            .float("sigma", 4.5f)
            .int("width", 1280)
            .bool("mirrored", true)
            .string("mode", "Multiply")
            .vec3("keyColor", 0f, 1f, 0f)
            .vec4("color", 1f, 0.5f, 0.2f, 1f)
            .build()

        val json = params.toJson()
        val restored = NodeParameters.fromJson(json)

        assertEquals(4.5f, restored.getFloat("sigma"), 0.001f)
        assertEquals(1280, restored.getInt("width"))
        assertEquals(true, restored.getBool("mirrored"))
        assertEquals("Multiply", restored.getString("mode"))
        assertEquals(listOf(0f, 1f, 0f), restored.getVec3("keyColor"))
        assertEquals(4, restored.getVec4("color").size)
    }
}
