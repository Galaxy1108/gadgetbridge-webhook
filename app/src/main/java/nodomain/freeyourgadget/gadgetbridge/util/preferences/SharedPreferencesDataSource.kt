package nodomain.freeyourgadget.gadgetbridge.util.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import nodomain.freeyourgadget.gadgetbridge.util.Prefs

/**
 * The data source for a single [SharedPreferences] file. Repositories use it to read and write their keys, so that
 * only this class talks to the file directly.
 *
 * Reads are tolerant of the stored type in the same way as [Prefs], e.g. a boolean stored as a string is parsed.
 */
class SharedPreferencesDataSource(private val preferences: SharedPreferences) {
    private val prefs = Prefs(preferences)

    fun getString(key: String, defaultValue: String?): String? = prefs.getString(key, defaultValue)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    /**
     * Applies the changes made by [action], asynchronously unless [commit] is true.
     */
    fun edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
        preferences.edit(commit, action)
    }

    /**
     * The keys that change while the flow is collected. A null key means that the file was cleared, which is only
     * reported on Android 11 and later.
     */
    fun changedKeys(): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.UNLIMITED)
}
