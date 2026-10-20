package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.SettingsRenderHost
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSetting
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingRenderer
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsRefreshHandle
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetRegistry
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import nodomain.freeyourgadget.gadgetbridge.widgets.variant

/**
 * Renders one placed widget instance's settings: the common ones (device selection, variant, title override),
 * followed by the widget's own [GBWidget.settings].
 */
class WidgetInstanceSettingsFragment : AbstractPreferenceFragment(), SettingsRenderHost {
    private var refreshHandle: DeviceSettingsRefreshHandle? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val instanceId = requireArguments().getString(ARG_INSTANCE_ID) ?: return
        val typeId = requireArguments().getString(ARG_TYPE_ID) ?: return
        val widget = WidgetRegistry.byId(typeId)!!

        preferenceManager.sharedPreferencesName = "widgetsettings_$instanceId"

        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen

        val prefs = Prefs(GBApplication.getWidgetSharedPrefs(instanceId))
        val items = mutableListOf<DeviceSetting>()
        items += commonSettings(widget)
        widget.settings(requireContext())?.let {
            items += it.items
        }

        refreshHandle = DeviceSettingRenderer.render(items, screen, prefs, this)

        setupVariantPreference(widget, instanceId, requireArguments().getInt(ARG_COLUMNS, WidgetGrid.BASE_COLUMNS))
    }

    private fun setupVariantPreference(widget: GBWidget<*>, instanceId: String, columns: Int) {
        val variantPref = findPreference<Preference>(KEY_VARIANT) ?: return

        val placement = WidgetLayoutStore.layout(columns).find { it.instance.instanceId == instanceId }
        if (placement == null) {
            variantPref.isVisible = false
            return
        }
        var variantId = placement.variantId
        variantPref.summary = getString(widget.variant(variantId).name)
        variantPref.setOnPreferenceClickListener {
            showVariantSheet(widget, variantId) { variant ->
                variantId = variant.id
                WidgetLayoutStore.setVariant(columns, instanceId, variant.id)
                variantPref.summary = getString(variant.name)
            }
            true
        }
    }

    /**
     * Shows a preview of each variant of [widget] in a bottom sheet, as wide as the screen so that the previews size
     * match the dashboard.
     */
    @SuppressLint("InflateParams")  // The bottom sheet sets the layout params of its content view
    private fun showVariantSheet(widget: GBWidget<*>, selectedVariantId: String, onSelect: (WidgetVariant) -> Unit) {
        val sheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dashboard_widget_previews, null)
        showVariantPreviews(view.findViewById(R.id.dashboard_widget_previews), widget, selectedVariantId) { variant ->
            onSelect(variant)
            sheet.dismiss()
        }
        sheet.setContentView(view)
        sheet.behavior.maxWidth = resources.displayMetrics.widthPixels
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    override fun onDestroyView() {
        refreshHandle?.cleanup()
        refreshHandle = null
        super.onDestroyView()
    }

    private fun commonSettings(widget: GBWidget<*>): List<DeviceSetting> = deviceSettings {
        list(
            key = WidgetConfig.KEY_DEVICES_MODE,
            title = R.string.bottom_nav_devices,
            icon = R.drawable.ic_devices_wearables,
            entries = listOf(
                ListEntry.Res(WidgetConfig.DEVICES_MODE_INHERIT, R.string.pref_widget_devices_mode_inherit),
                ListEntry.Res(WidgetConfig.DEVICES_MODE_ALL, R.string.pref_dashboard_all_devices_title),
                ListEntry.Res(WidgetConfig.DEVICES_MODE_SELECTED, R.string.pref_auto_export_gpx_selected_devices),
            ),
            defaultValue = WidgetConfig.DEVICES_MODE_INHERIT,
        )
        multiSelect(
            key = WidgetConfig.KEY_DEVICES,
            title = R.string.pref_dashboard_select_devices_title,
            icon = R.drawable.ic_devices_other,
            entriesProvider = { prefs ->
                val selected = prefs.getStringSet(WidgetConfig.KEY_DEVICES, emptySet())
                GBApplication.app().deviceManager.devices
                    .filter { widget.isSupportedBy(it) }
                    .sortedWith(
                        compareBy<GBDevice> { it.address !in selected }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.aliasOrName }
                    )
                    .map { ListEntry.Text(it.address, it.aliasOrName) }
            },
            visibleWhen = {
                it.getString(
                    WidgetConfig.KEY_DEVICES_MODE,
                    WidgetConfig.DEVICES_MODE_INHERIT
                ) == WidgetConfig.DEVICES_MODE_SELECTED
            },
        )
        if (widget.variants.size > 1) {
            action(
                key = KEY_VARIANT,
                title = R.string.pref_widget_variant_title,
                icon = R.drawable.ic_font_size,
                connectedOnly = false,
            )
        }
        text(
            key = WidgetConfig.KEY_TITLE,
            title = R.string.pref_widget_title_title,
            summary = R.string.pref_widget_title_summary,
            icon = R.drawable.ic_label_24px,
            defaultValue = "",
            inputType = InputType.TYPE_CLASS_TEXT,
            connectedOnly = false,
        )
    }.items

    //
    // SettingsRenderHost
    //
    // findPreference(CharSequence) and getContext() are already provided by
    // PreferenceFragmentCompat/Fragment with compatible signatures.
    //

    override fun addPreferenceHandlerFor(preferenceKey: String) {
        // No device to send configuration to.
    }

    override fun addPreferenceHandlerFor(preferenceKey: String, extraListener: Preference.OnPreferenceChangeListener) {
        findPreference<Preference>(preferenceKey)?.onPreferenceChangeListener = extraListener
    }

    override fun notifyPreferenceChanged(preferenceKey: String) {
        // No device to notify - the renderer already re-evaluates visibleWhen/entriesProvider
        // itself after every change.
    }

    override fun setInputTypeFor(preferenceKey: String, editTypeFlags: Int) {
        (findPreference<Preference>(preferenceKey) as? EditTextPreference)?.setOnBindEditTextListener { editText: EditText ->
            editText.inputType = editTypeFlags
        }
    }

    override fun navigateToScreen(screen: PreferenceScreen) {
        onNavigateToScreen(screen)
    }

    override fun addXmlPreferences(resId: Int) {
        addPreferencesFromResource(resId)
    }

    companion object {
        const val ARG_INSTANCE_ID = "widget_instance_id"
        const val ARG_TYPE_ID = "widget_type_id"
        const val ARG_COLUMNS = "widget_columns"
        private const val KEY_VARIANT = "widget_variant"

        fun newInstance(instanceId: String, typeId: String, columns: Int): WidgetInstanceSettingsFragment {
            val fragment = WidgetInstanceSettingsFragment()
            val args = Bundle()
            args.putString(ARG_INSTANCE_ID, instanceId)
            args.putString(ARG_TYPE_ID, typeId)
            args.putInt(ARG_COLUMNS, columns)
            fragment.arguments = args
            return fragment
        }
    }
}
