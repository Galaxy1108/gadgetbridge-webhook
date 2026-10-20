package nodomain.freeyourgadget.gadgetbridge.activities.debug

import android.content.Intent
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.DashboardFragment
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore

class DashboardDebugFragment : AbstractDebugFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.debug_preferences_dashboard, rootKey)

        onClick(PREF_DEBUG_DASHBOARD_RESET) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset dashboard")
                .setMessage(R.string.are_you_sure)
                .setPositiveButton(R.string.reset) { _, _ ->
                    WidgetLayoutStore.reset()
                    notifyDashboard()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        onClick(PREF_DEBUG_DASHBOARD_ADD_ALL_VARIANTS) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add every widget variant")
                .setMessage(R.string.are_you_sure)
                .setPositiveButton(R.string.yes) { _, _ ->
                    WidgetLayoutStore.addAllVariants()
                    notifyDashboard()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun notifyDashboard() {
        LocalBroadcastManager.getInstance(requireContext())
            .sendBroadcast(Intent(DashboardFragment.ACTION_CONFIG_CHANGE))
    }

    companion object {
        private const val PREF_DEBUG_DASHBOARD_RESET = "pref_debug_dashboard_reset"
        private const val PREF_DEBUG_DASHBOARD_ADD_ALL_VARIANTS = "pref_debug_dashboard_add_all_variants"
    }
}
