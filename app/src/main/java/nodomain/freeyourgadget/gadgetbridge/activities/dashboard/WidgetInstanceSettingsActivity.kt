package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceFragmentCompat
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.activities.DashboardFragment
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore

/**
 * Hosts [WidgetInstanceSettingsFragment] for one placed widget instance.
 */
class WidgetInstanceSettingsActivity : AbstractSettingsActivityV2() {
    override fun newFragment(): PreferenceFragmentCompat {
        val instanceId = intent.getStringExtra(WidgetInstanceSettingsFragment.ARG_INSTANCE_ID).orEmpty()
        val typeId = intent.getStringExtra(WidgetInstanceSettingsFragment.ARG_TYPE_ID).orEmpty()
        val columns = intent.getIntExtra(WidgetInstanceSettingsFragment.ARG_COLUMNS, WidgetGrid.BASE_COLUMNS)
        return WidgetInstanceSettingsFragment.newInstance(instanceId, typeId, columns)
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(DashboardFragment.ACTION_CONFIG_CHANGE))
        super.onPause()
    }

    companion object {
        /**
         * Opens the settings of [instanceId] in the layout of [columns] columns. If that layout is derived,
         * it is stored first, and the settings of the copy are opened.
         */
        fun startForWidget(context: Context, columns: Int, instanceId: String) {
            val instance = WidgetLayoutStore.instanceForEdit(columns, instanceId) ?: return
            val intent = Intent(context, WidgetInstanceSettingsActivity::class.java)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_INSTANCE_ID, instance.instanceId)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_TYPE_ID, instance.typeId)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_COLUMNS, columns)
            context.startActivity(intent)
        }
    }
}
