package nodomain.freeyourgadget.gadgetbridge.widgets

import androidx.core.content.edit
import com.google.gson.Gson
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.gson.GsonSerialized
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The notice for the widget instances that were added for the newly paired device with [address].
 */
@GsonSerialized
data class DeviceNotice(val address: String, val instanceIds: List<String>)

/**
 * The notices that the dashboard displays above the widgets.
 */
object DashboardNotices {
    private val LOG: Logger = LoggerFactory.getLogger(DashboardNotices::class.java)

    private const val PREF_MIGRATION = "pref_dashboard_notice_migration"
    private const val PREF_DEVICE = "pref_dashboard_notice_device"

    private val gson = Gson()

    /**
     * The notices to display:
     * - dashboard migration
     * - last device that added widgets
     */
    data class State(val migration: Boolean, val device: DeviceNotice?) {
        companion object {
            val NONE = State(migration = false, device = null)
        }
    }

    fun state(): State {
        val prefs = GBApplication.getPrefs()
        val deviceJson = prefs.getString(PREF_DEVICE, "")
        val device = if (deviceJson.isBlank()) {
            null
        } else {
            try {
                gson.fromJson(deviceJson, DeviceNotice::class.java)
            } catch (e: Exception) {
                LOG.error("Failed to parse the device notice", e)
                null
            }
        }
        return State(prefs.getBoolean(PREF_MIGRATION, false), device)
    }

    fun set(state: State) {
        LOG.debug("Setting the dashboard notices to {}", state)
        GBApplication.getPrefs().preferences.edit {
            putBoolean(PREF_MIGRATION, state.migration)
            if (state.device != null) {
                putString(PREF_DEVICE, gson.toJson(state.device))
            } else {
                remove(PREF_DEVICE)
            }
        }
    }
}
