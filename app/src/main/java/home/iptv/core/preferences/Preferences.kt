package home.iptv.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import home.iptv.core.model.PlaybackPreferences
import kotlinx.coroutines.flow.map

private val Context.playbackStore by preferencesDataStore("playback")
class Preferences(context: Context) {
    private val store = context.playbackStore
    private val last = stringPreferencesKey("last_channel")
    private val list = stringPreferencesKey("list_context")
    private val auto = booleanPreferencesKey("auto_resume")
    private val fill = booleanPreferencesKey("fill")
    private val recent = stringPreferencesKey("recent_channels")
    private fun decode(value: String?): List<String> = runCatching {
        val array = org.json.JSONArray(value ?: "[]")
        (0 until array.length()).map { array.getString(it) }.filter(String::isNotBlank).distinct().take(10)
    }.getOrDefault(emptyList())
    val flow = store.data.map { PlaybackPreferences(it[last].orEmpty(), it[list] ?: "all", it[auto] ?: true, it[fill] ?: false, decode(it[recent])) }
    suspend fun played(id: String, context: String) { store.edit {
        val history = (listOf(id) + decode(it[recent]) + listOfNotNull(it[last])).filter(String::isNotBlank).distinct().take(10)
        it[recent] = org.json.JSONArray(history).toString()
        it[last] = id; it[list] = context
    } }

    suspend fun autoResume(value: Boolean) { store.edit { it[auto] = value } }
    suspend fun fill(value: Boolean) { store.edit { it[fill] = value } }
}
