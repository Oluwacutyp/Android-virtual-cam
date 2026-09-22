package com.androidvirtualcam.compositor

import kotlinx.serialization.Serializable

@Serializable
enum class SocketType {
    TEXTURE,
    FLOAT,
    VEC2,
    VEC3,
    VEC4,
    BOOL,
    INT,
    STRING
}

@Serializable
data class InputSocket(
    val id: String,
    val name: String,
    val type: SocketType,
    val displayName: String = name,
    val isRequired: Boolean = true,
    val connectedNodeId: String? = null,
    val connectedOutputId: String? = null,
    val defaultFloat: Float = 0f,
    val defaultVec3: List<Float> = listOf(0f, 0f, 0f),
    val defaultBool: Boolean = false,
    val defaultString: String = ""
) {
    fun isConnected(): Boolean = connectedNodeId != null && connectedOutputId != null

    fun withConnection(nodeId: String, outputId: String): InputSocket =
        copy(connectedNodeId = nodeId, connectedOutputId = outputId)

    fun withDisconnected(): InputSocket = copy(connectedNodeId = null, connectedOutputId = null)
}

@Serializable
data class OutputSocket(
    val id: String,
    val name: String,
    val type: SocketType,
    val displayName: String = name
)
