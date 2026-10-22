package nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

/**
 * Reads and observes the dashboard-wide preferences.
 */
class DashboardPreferences(
    private val preferences: SharedPreferences = GBApplication.getPrefs().preferences,
) {
    fun current(): DashboardSettings = DashboardSettings(
        cardsEnabled = preferences.getBoolean(PREF_CARDS_ENABLED, true),
        showAllDevices = preferences.getBoolean(PREF_DEVICES_ALL, true),
        deviceAddresses = preferences.getStringSet(PREF_DEVICES_MULTISELECT, emptySet()).orEmpty(),
    )

    fun settingsFlow(): Flow<DashboardSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in WATCHED_KEYS) {
                trySend(current())
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(current())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun isDeviceInScope(device: GBDevice): Boolean = current().includesDevice(device.address)

    companion object {
        const val PREF_CARDS_ENABLED = "dashboard_cards_enabled"
        const val PREF_DEVICES_ALL = "dashboard_devices_all"
        const val PREF_DEVICES_MULTISELECT = "dashboard_devices_multiselect"

        private val WATCHED_KEYS = setOf(
            PREF_CARDS_ENABLED,
            PREF_DEVICES_ALL,
            PREF_DEVICES_MULTISELECT,
        )
    }
}
