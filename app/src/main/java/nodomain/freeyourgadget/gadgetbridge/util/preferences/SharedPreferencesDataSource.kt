package nodomain.freeyourgadget.gadgetbridge.util.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
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
     * The file only keeps a weak reference to [listener], so the caller must hold on to it.
     */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
