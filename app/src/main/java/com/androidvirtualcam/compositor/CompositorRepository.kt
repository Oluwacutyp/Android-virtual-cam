package com.androidvirtualcam.compositor

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.compositorDataStore by preferencesDataStore(name = "compositor_graphs")

/**
 * Repository for saving/loading compositor graphs via DataStore.
 */
class CompositorRepository(private val context: Context) {

    private val currentGraphKey = stringPreferencesKey("current_graph_json")
    private val currentPresetKey = stringPreferencesKey("current_preset_id")

    val currentGraphJson: Flow<String?> = context.compositorDataStore.data.map { prefs ->
        prefs[currentGraphKey]
    }

    val currentPresetId: Flow<String?> = context.compositorDataStore.data.map { prefs ->
        prefs[currentPresetKey]
    }

    suspend fun saveGraphJson(json: String) {
        context.compositorDataStore.edit { prefs ->
            prefs[currentGraphKey] = json
        }
    }

    suspend fun savePresetId(presetId: String) {
        context.compositorDataStore.edit { prefs ->
            prefs[currentPresetKey] = presetId
        }
    }

    suspend fun loadGraph(factory: CompositorNodeFactory): CompositorGraph? {
        val json = currentGraphJson
        // This is Flow, need to get first value – for simplicity, use blocking read via DataStore is async
        // Caller should collect flow
        return null
    }

    companion object {
        suspend fun getCurrentGraphJsonSync(context: Context): String? {
            var result: String? = null
            context.compositorDataStore.data.map { it[stringPreferencesKey("current_graph_json")] }.collect { result = it }
            return result
        }
    }
}
