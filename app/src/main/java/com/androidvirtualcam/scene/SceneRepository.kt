package com.androidvirtualcam.scene

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "scene_prefs")

class SceneRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        classDiscriminator = "type"
    }

    private val SCENES_KEY = stringPreferencesKey("scenes_json")

    val sceneCollectionFlow: Flow<SceneCollection> = context.dataStore.data.map { prefs ->
        val jsonString = prefs[SCENES_KEY]
        if (jsonString.isNullOrBlank()) {
            SceneCollection()
        } else {
            try {
                json.decodeFromString<SceneCollection>(jsonString)
            } catch (e: Exception) {
                // Fallback to default if corrupted
                SceneCollection()
            }
        }
    }

    suspend fun load(): SceneCollection {
        return sceneCollectionFlow.first()
    }

    suspend fun save(collection: SceneCollection) {
        val encoded = json.encodeToString(collection)
        context.dataStore.edit { prefs ->
            prefs[SCENES_KEY] = encoded
        }
    }

    // Synchronous in-memory version for tests / non-Android
    companion object {
        fun encode(collection: SceneCollection): String {
            val json = Json { prettyPrint = true; classDiscriminator = "type" }
            return json.encodeToString(collection)
        }

        fun decode(jsonString: String): SceneCollection {
            val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
            return json.decodeFromString(jsonString)
        }
    }
}

/**
 * Pure in-memory repository for unit tests and preview.
 */
class InMemorySceneRepository(initial: SceneCollection = SceneCollection()) {
    private var collection: SceneCollection = initial
    private val listeners = mutableListOf<(SceneCollection) -> Unit>()

    fun get(): SceneCollection = collection

    fun update(transform: (SceneCollection) -> SceneCollection) {
        collection = transform(collection)
        listeners.forEach { it(collection) }
    }

    fun observe(listener: (SceneCollection) -> Unit) {
        listeners += listener
        listener(collection)
    }
}
