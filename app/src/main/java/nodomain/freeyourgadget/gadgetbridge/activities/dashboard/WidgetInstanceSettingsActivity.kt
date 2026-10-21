package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceFragmentCompat
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.activities.DashboardFragment
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetInstance
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore
import nodomain.freeyourgadget.gadgetbridge.widgets.launcher.LauncherWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.launcher.LauncherWidgets

/**
 * Hosts [WidgetInstanceSettingsFragment] for one widget instance, on the dashboard or on the launcher.
 */
class WidgetInstanceSettingsActivity : AbstractSettingsActivityV2() {
    private val appWidgetId: Int
        get() = intent.getIntExtra(WidgetInstanceSettingsFragment.ARG_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    override fun newFragment(): PreferenceFragmentCompat {
        val instanceId = intent.getStringExtra(WidgetInstanceSettingsFragment.ARG_INSTANCE_ID).orEmpty()
        val typeId = intent.getStringExtra(WidgetInstanceSettingsFragment.ARG_TYPE_ID).orEmpty()
        val columns = intent.getIntExtra(WidgetInstanceSettingsFragment.ARG_COLUMNS, WidgetGrid.BASE_COLUMNS)
        return WidgetInstanceSettingsFragment.newInstance(instanceId, typeId, columns, appWidgetId)
    }

    override fun onPause() {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            LauncherWidgets.update(this, intArrayOf(appWidgetId))
        } else {
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(DashboardFragment.ACTION_CONFIG_CHANGE))
        }
        super.onPause()
    }

    companion object {
        /**
         * Opens the settings of [instanceId] in the layout of [columns] columns. If that layout is derived,
         * it is stored first, and the settings of the copy are opened.
         */
        fun startForWidget(context: Context, columns: Int, instanceId: String): WidgetInstance? {
            val instance = WidgetLayoutStore.instanceForEdit(columns, instanceId) ?: return null
            val intent = Intent(context, WidgetInstanceSettingsActivity::class.java)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_INSTANCE_ID, instance.instanceId)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_TYPE_ID, instance.typeId)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_COLUMNS, columns)
            context.startActivity(intent)
            return instance
        }

        fun startForLauncherWidget(context: Context, appWidgetId: Int, launcherWidget: LauncherWidget) {
            val intent = Intent(context, WidgetInstanceSettingsActivity::class.java)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_INSTANCE_ID, launcherWidget.instance.instanceId)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_TYPE_ID, launcherWidget.instance.typeId)
            intent.putExtra(WidgetInstanceSettingsFragment.ARG_APP_WIDGET_ID, appWidgetId)
            context.startActivity(intent)
        }
    }
}
