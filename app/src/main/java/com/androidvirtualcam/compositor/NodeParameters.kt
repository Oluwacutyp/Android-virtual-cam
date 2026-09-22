package com.androidvirtualcam.compositor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Serializable parameters for nodes – supports typed values with JSON serialization.
 * Production-grade: type-safe getters, default values, validation.
 */
@Serializable
data class NodeParameters(
    val floats: Map<String, Float> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val bools: Map<String, Boolean> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val vec3s: Map<String, List<Float>> = emptyMap(),
    val vec4s: Map<String, List<Float>> = emptyMap()
) {
    fun getFloat(key: String, default: Float = 0f): Float = floats[key] ?: default
    fun getInt(key: String, default: Int = 0): Int = ints[key] ?: default
    fun getBool(key: String, default: Boolean = false): Boolean = bools[key] ?: default
    fun getString(key: String, default: String = ""): String = strings[key] ?: default
    fun getVec3(key: String, default: List<Float> = listOf(0f, 0f, 0f)): List<Float> = vec3s[key] ?: default
    fun getVec4(key: String, default: List<Float> = listOf(0f, 0f, 0f, 1f)): List<Float> = vec4s[key] ?: default

    // Returns all parameters as Map<String, Param> for UI editing – fixes NodeGraphEditor all() missing
    fun all(): Map<String, Param> {
        val result = mutableMapOf<String, Param>()
        floats.forEach { (k, v) -> result[k] = Param(ParamType.FLOAT, v) }
        ints.forEach { (k, v) -> result[k] = Param(ParamType.INT, v) }
        bools.forEach { (k, v) -> result[k] = Param(ParamType.BOOL, v) }
        strings.forEach { (k, v) -> result[k] = Param(ParamType.STRING, v) }
        vec3s.forEach { (k, v) -> result[k] = Param(ParamType.VEC3, v) }
        vec4s.forEach { (k, v) -> result[k] = Param(ParamType.VEC4, v) }
        return result
    }

    fun withFloat(key: String, value: Float): NodeParameters = copy(floats = floats + (key to value))
    fun withInt(key: String, value: Int): NodeParameters = copy(ints = ints + (key to value))
    fun withBool(key: String, value: Boolean): NodeParameters = copy(bools = bools + (key to value))
    fun withString(key: String, value: String): NodeParameters = copy(strings = strings + (key to value))
    fun withVec3(key: String, value: List<Float>): NodeParameters {
        require(value.size == 3) { "Vec3 must have 3 components" }
        return copy(vec3s = vec3s + (key to value))
    }
    fun withVec4(key: String, value: List<Float>): NodeParameters {
        require(value.size == 4) { "Vec4 must have 4 components" }
        return copy(vec4s = vec4s + (key to value))
    }

    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): NodeParameters = Json.decodeFromString(serializer(), json)

        fun builder(): Builder = Builder()

        class Builder {
            private val floats = mutableMapOf<String, Float>()
            private val ints = mutableMapOf<String, Int>()
            private val bools = mutableMapOf<String, Boolean>()
            private val strings = mutableMapOf<String, String>()
            private val vec3s = mutableMapOf<String, List<Float>>()
            private val vec4s = mutableMapOf<String, List<Float>>()

            fun float(key: String, value: Float) = apply { floats[key] = value }
            fun int(key: String, value: Int) = apply { ints[key] = value }
            fun bool(key: String, value: Boolean) = apply { bools[key] = value }
            fun string(key: String, value: String) = apply { strings[key] = value }
            fun vec3(key: String, x: Float, y: Float, z: Float) = apply { vec3s[key] = listOf(x, y, z) }
            fun vec4(key: String, x: Float, y: Float, z: Float, w: Float) = apply { vec4s[key] = listOf(x, y, z, w) }

            fun build(): NodeParameters = NodeParameters(
                floats = floats.toMap(),
                ints = ints.toMap(),
                bools = bools.toMap(),
                strings = strings.toMap(),
                vec3s = vec3s.toMap(),
                vec4s = vec4s.toMap()
            )
        }
    }
}
