package com.androidvirtualcam.audio

/**
 * Singleton holder for MusicDuckingManager to allow VoiceChangerEngine to update VAD without direct dependency
 */
object MusicDuckingManagerHolder {
    @Volatile
    private var instance: MusicDuckingManager? = null

    fun set(manager: MusicDuckingManager) {
        instance = manager
    }

    fun get(): MusicDuckingManager? = instance

    fun clear() {
        instance = null
    }
}
