package com.androidvirtualcam.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Teleprompter / Private Preview Overlay ViewModel.
 *
 * This overlay is ONLY visible in the local preview (Compose UI overlay on top of PreviewView (pure CameraX, ZERO GL)),
 * NOT in the compositor OutputNode -> FrameBus -> Virtual Camera / Recording / Streaming.
 *
 * Why? Because it's rendered as Android Compose overlay above GL preview, not as a compositor node.
 * Compositor output (OutputNode) is what gets published to FrameBus and encoder – it never includes
 * this Compose layer. Perfect for private teleprompter / scrolling notes.
 *
 * Features:
 * - Speed control (0.2x to 5x, 1x = 40px/s)
 * - Font size (12sp to 72sp)
 * - Mirror mode (horizontal flip for beam-splitter teleprompter glass)
 * - Vertical teleprompter mode (bottom→top) and horizontal ticker mode (right→left)
 * - Editable text, opacity, background
 */
class TeleprompterViewModel(
    private val context: Context
) : ViewModel() {

    data class TeleprompterState(
        val isEnabled: Boolean = false,
        val text: String = "Welcome to Virtual Cam Studio!\n\nThis is your private teleprompter – only YOU see this in preview.\n\n• It does NOT appear in recorded video\n• It does NOT appear in streamed output\n• It does NOT appear in virtual camera feed\n\nPerfect for:\n• Reading scripts while maintaining eye contact\n• Tutorial notes\n• Gaming commentary cues\n• Live stream talking points\n\nAdjust speed, font size, and mirror mode below. Mirror mode flips horizontally for beam-splitter glass.\n\nScroll speed controls how fast text moves. Font size makes it readable from distance.\n\nThis is free, no watermark, ManyCam-like but better – private overlay!",
        val speed: Float = 1.0f, // 0.2 to 5.0
        val fontSize: Int = 20, // sp 12..72
        val mirrorMode: Boolean = false,
        val verticalMode: Boolean = true, // true = vertical teleprompter, false = horizontal ticker
        val opacity: Float = 0.9f,
        val backgroundOpacity: Float = 0.6f,
        val showBackground: Boolean = true,
        val loop: Boolean = true,
        val autoScroll: Boolean = true
    )

    private val _state = MutableStateFlow(TeleprompterState())
    val state: StateFlow<TeleprompterState> = _state

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        _state.value = _state.value.copy(isEnabled = enabled)
    }

    fun toggle() {
        setEnabled(!_state.value.isEnabled)
    }

    fun setText(text: String) {
        _state.value = _state.value.copy(text = text)
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed.coerceIn(0.1f, 10f))
    }

    fun setFontSize(size: Int) {
        _state.value = _state.value.copy(fontSize = size.coerceIn(10, 96))
    }

    fun setMirrorMode(mirror: Boolean) {
        _state.value = _state.value.copy(mirrorMode = mirror)
    }

    fun toggleMirror() {
        _state.value = _state.value.copy(mirrorMode = !_state.value.mirrorMode)
    }

    fun setVerticalMode(vertical: Boolean) {
        _state.value = _state.value.copy(verticalMode = vertical)
    }

    fun setOpacity(opacity: Float) {
        _state.value = _state.value.copy(opacity = opacity.coerceIn(0f, 1f))
    }

    fun setBackgroundOpacity(opacity: Float) {
        _state.value = _state.value.copy(backgroundOpacity = opacity.coerceIn(0f, 1f))
    }

    fun setShowBackground(show: Boolean) {
        _state.value = _state.value.copy(showBackground = show)
    }

    fun setLoop(loop: Boolean) {
        _state.value = _state.value.copy(loop = loop)
    }

    fun setAutoScroll(auto: Boolean) {
        _state.value = _state.value.copy(autoScroll = auto)
    }

    fun reset() {
        _state.value = TeleprompterState()
        _isEnabled.value = false
    }

    companion object {
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 10f
        const val MIN_FONT = 10
        const val MAX_FONT = 96
    }
}
