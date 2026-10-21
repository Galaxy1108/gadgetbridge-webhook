/*  Copyright (C) 2023-2026 Arjan Schrijver, José Rebelo

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.view.MenuProvider
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardCalendarActivity
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardGridBinder
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardViewModel
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GridDragController
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WidgetGridLayout
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WidgetInstanceSettingsActivity
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WidgetPickerActivity
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.showVariantPreviews
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.BarShade
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetRegistry
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.roundToInt

/**
 * Hosts the widget grid: date header, calendar/settings menu, and the [WidgetGridLayout] the
 * [DashboardGridBinder] renders the placed widgets into, driven by [DashboardViewModel]. In edit mode, the
 * widgets can be moved, added, changed and deleted.
 */
class DashboardFragment : Fragment(), MenuProvider, HeaderShadePage, DashboardGridBinder.Callbacks {
    private val day: Calendar = GregorianCalendar.getInstance()
    private lateinit var textViewDate: TextView
    private lateinit var arrowLeft: TextView
    private lateinit var arrowRight: TextView
    private lateinit var scrollView: NestedScrollView
    private lateinit var addButton: FloatingActionButton
    private lateinit var saveButton: FloatingActionButton
    private lateinit var grid: WidgetGridLayout
    private lateinit var binder: DashboardGridBinder
    private lateinit var dragController: GridDragController
    private lateinit var viewModel: DashboardViewModel

    private var isConfigChanged = false
    private var startEditingOnResume = false
    private var actionMode: ActionMode? = null
    private var deleteSnackbar: Snackbar? = null

    /**
     * The instance whose settings are open, to reload them when they close.
     */
    private var settingsInstanceId: String? = null

    /**
     * The new instance to scroll to and highlight after the next layout.
     */
    private var newInstanceId: String? = null

    private val pickWidget = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
        val typeId = data.getStringExtra(WidgetPickerActivity.EXTRA_TYPE_ID) ?: return@registerForActivityResult
        val variantId = data.getStringExtra(WidgetPickerActivity.EXTRA_VARIANT_ID) ?: return@registerForActivityResult
        newInstanceId = WidgetLayoutStore.add(grid.columns, typeId, variantId).instanceId
        viewModel.updateLayout(grid.columns)
    }

    private val editCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.setTitle(R.string.dashboard_edit)
            mode.menuInflater.inflate(R.menu.dashboard_edit_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.dashboard_edit_undo) return false
            resetEdits()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            viewModel.editSnapshot = null
            binder.editing = false
            setEditControls(false)
        }
    }

    private val calendarLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val timeMillis = result.data!!.getLongExtra(DashboardCalendarActivity.EXTRA_TIMESTAMP, 0)
            if (timeMillis != 0L) {
                day.timeInMillis = timeMillis
                fullRefresh()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                GBApplication.ACTION_NEW_DATA -> {
                    val dev: GBDevice? = intent.getDevice()
                    if (dev != null && isDeviceInScope(dev)) {
                        refresh()
                    }
                }

                ACTION_CONFIG_CHANGE -> isConfigChanged = true
                ACTION_START_EDITING -> startEditingOnResume = true
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        textViewDate = view.findViewById(R.id.dashboard_date)
        scrollView = view.findViewById(R.id.dashboard_scroll)
        addButton = view.findViewById(R.id.dashboard_add_widget)
        addButton.setOnClickListener { addWidget() }
        saveButton = view.findViewById(R.id.dashboard_save)
        saveButton.setOnClickListener { actionMode?.finish() }
        grid = view.findViewById(R.id.dashboard_grid)
        arrowLeft = view.findViewById(R.id.arrow_left)
        arrowRight = view.findViewById(R.id.arrow_right)

        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]
        binder = DashboardGridBinder(grid)
        binder.callbacks = this
        grid.columns = WidgetGrid.columnsForWidth(resources.configuration.screenWidthDp)
        dragController = GridDragController(grid, scrollView, binder::instanceIdOf) { arrangement ->
            WidgetLayoutStore.arrange(grid.columns, arrangement)
            viewModel.updateLayout(grid.columns)
        }

        arrowLeft.setOnClickListener {
            day.add(Calendar.DAY_OF_MONTH, -1)
            refresh()
        }
        arrowRight.setOnClickListener {
            val today = GregorianCalendar.getInstance()
            if (!DateTimeUtils.isSameDay(today, day)) {
                day.add(Calendar.DAY_OF_MONTH, 1)
                refresh()
            }
        }

        savedInstanceState?.let {
            if (it.containsKey(KEY_DAY)) {
                day.timeInMillis = it.getLong(KEY_DAY)
            }
            startEditingOnResume = it.getBoolean(KEY_EDITING)
        }

        observeViewModel()
        refresh()

        val filter = IntentFilter()
        filter.addAction(GBDevice.ACTION_DEVICE_CHANGED)
        filter.addAction(GBApplication.ACTION_NEW_DATA)
        filter.addAction(ACTION_CONFIG_CHANGE)
        filter.addAction(ACTION_START_EDITING)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, filter)

        return view
    }

    override fun onResume() {
        super.onResume()
        if (isConfigChanged) {
            isConfigChanged = false
            if (actionMode != null) {
                viewModel.updateLayout(grid.columns, setOfNotNull(settingsInstanceId))
            } else {
                fullRefresh()
            }
        }
        settingsInstanceId = null
        if (startEditingOnResume) {
            startEditingOnResume = false
            startEditing()
        }
    }

    override fun onDestroyView() {
        // Keep the snapshot when the edit mode continues in the new view
        val snapshot = viewModel.editSnapshot
        actionMode?.finish()
        viewModel.editSnapshot = snapshot
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver)
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DAY, day.timeInMillis)
        outState.putBoolean(KEY_EDITING, actionMode != null)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.dashboard_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.dashboard_show_calendar -> {
                val intent = Intent(requireActivity(), DashboardCalendarActivity::class.java)
                intent.putExtra(DashboardCalendarActivity.EXTRA_TIMESTAMP, day.timeInMillis)
                calendarLauncher.launch(intent)
                true
            }

            R.id.dashboard_edit -> {
                startEditing()
                true
            }

            R.id.dashboard_settings -> {
                startActivity(Intent(requireActivity(), DashboardPreferencesActivity::class.java))
                true
            }

            else -> false
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                binder.submitItems(items)
                newInstanceId?.let { instanceId ->
                    newInstanceId = null
                    binder.cardOf(instanceId)?.doOnLayout { scrollView.smoothScrollTo(0, it.top) }
                    binder.highlight(instanceId)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collect { states ->
                binder.submitStates(states)
            }
        }
    }

    private fun fullRefresh() {
        refresh()
    }

    private fun refresh() {
        day.set(Calendar.HOUR_OF_DAY, 23)
        day.set(Calendar.MINUTE, 59)
        day.set(Calendar.SECOND, 59)

        val prefs = GBApplication.getPrefs()
        val showAllDevices = prefs.getBoolean("dashboard_devices_all", true)
        val deviceList = prefs.getStringSet("dashboard_devices_multiselect", HashSet())
        val timestamp = (day.timeInMillis / 1000L).toInt()

        binder.cardsEnabled = prefs.getBoolean("dashboard_cards_enabled", true)
        binder.dashboardShowAllDevices = showAllDevices
        binder.dashboardDeviceList = deviceList
        binder.timestamp = timestamp

        val today = GregorianCalendar.getInstance()
        if (DateTimeUtils.isSameDay(today, day)) {
            textViewDate.text = requireContext().getString(R.string.activity_summary_today)
            arrowRight.alpha = 0.5f
        } else {
            textViewDate.text = DateTimeUtils.formatDate(day.time, DateUtils.FORMAT_SHOW_WEEKDAY)
            arrowRight.alpha = 1f
        }

        viewModel.refresh(day, grid.columns, showAllDevices, deviceList)
    }

    private fun startEditing() {
        if (actionMode != null) return
        actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(editCallback) ?: return
        if (viewModel.editSnapshot == null) {
            viewModel.editSnapshot = WidgetLayoutStore.snapshot()
        }
        binder.editing = true
        setEditControls(true)
    }

    /**
     * Restores the dashboard to its state at the start of the edit mode.
     */
    private fun resetEdits() {
        val snapshot = viewModel.editSnapshot ?: return
        deleteSnackbar?.dismiss()
        WidgetLayoutStore.restore(snapshot)
        fullRefresh()
    }

    /**
     * Shows the add and save FABs, and hides the date arrows and disables the page swipe in the edit mode.
     */
    private fun setEditControls(editing: Boolean) {
        arrowLeft.isInvisible = editing
        arrowRight.isInvisible = editing
        if (editing) {
            addButton.show()
            saveButton.show()
        } else {
            addButton.hide()
            saveButton.hide()
        }
        activity?.findViewById<ViewPager2>(R.id.dashboard_viewpager)?.isUserInputEnabled = !editing
        // Reserve space for the two buttons and their margins
        val bottom = if (editing) (160f * resources.displayMetrics.density).roundToInt() else 0
        scrollView.updatePadding(bottom = bottom)
    }

    private fun addWidget() {
        if (WidgetRegistry.available().isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.widgets_add_widget)
                .setMessage(R.string.no_supported_devices_found)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        pickWidget.launch(Intent(requireContext(), WidgetPickerActivity::class.java))
    }

    override fun onLongPress(item: PlacedWidget, card: View) {
        startEditing()
        dragController.start(card)
    }

    override fun onEditClick(item: PlacedWidget) {
        showEditSheet(item)
    }

    override fun onMove(item: PlacedWidget, offset: Int) {
        val index = viewModel.items.value.indexOfFirst { it.instance.instanceId == item.instance.instanceId } + offset
        if (index < 0) return
        WidgetLayoutStore.move(grid.columns, item.instance.instanceId, index, item.x)
        viewModel.updateLayout(grid.columns)
    }

    /**
     * Shows the variants of [item] and its Settings, Duplicate and Delete actions in a bottom sheet.
     */
    @SuppressLint("InflateParams")  // The bottom sheet sets the layout params of its content view
    private fun showEditSheet(item: PlacedWidget) {
        val context = requireContext()
        val columns = grid.columns
        val instanceId = item.instance.instanceId
        val sheet = BottomSheetDialog(context)
        val view = layoutInflater.inflate(R.layout.dashboard_widget_edit_sheet, null)

        view.findViewById<TextView>(R.id.dashboard_edit_title).text = item.widget.displayName(context)
        val variants = view.findViewById<WidgetGridLayout>(R.id.dashboard_edit_variants)
        if (item.widget.variants.size > 1) {
            showVariantPreviews(variants, item.widget, item.variant.id) { variant ->
                WidgetLayoutStore.setVariant(columns, instanceId, variant.id)
                viewModel.updateLayout(columns)
                sheet.dismiss()
            }
        } else {
            variants.isVisible = false
        }

        view.findViewById<View>(R.id.dashboard_edit_settings).setOnClickListener {
            settingsInstanceId = WidgetInstanceSettingsActivity.startForWidget(context, columns, instanceId)?.instanceId
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.dashboard_edit_duplicate).setOnClickListener {
            newInstanceId = WidgetLayoutStore.duplicate(columns, instanceId)?.instanceId
            viewModel.updateLayout(columns)
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.dashboard_edit_delete).setOnClickListener {
            val deleted = WidgetLayoutStore.delete(columns, instanceId)
            viewModel.updateLayout(columns)
            sheet.dismiss()
            if (deleted != null) {
                deleteSnackbar = Snackbar.make(requireView(), R.string.widgets_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) {
                        WidgetLayoutStore.restore(deleted)
                        viewModel.updateLayout(columns)
                    }
                    .apply { show() }
            }
        }

        sheet.setContentView(view)
        sheet.behavior.maxWidth = resources.displayMetrics.widthPixels
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    override fun getHeaderShade(): View? {
        val shade = view?.findViewById<View>(R.id.dashboard_date_row_shade) ?: return null
        return if (BarShade.continuesToolbar(shade.context, R.attr.datestep_row_bg)) shade else null
    }

    private fun isDeviceInScope(device: GBDevice): Boolean {
        val prefs = GBApplication.getPrefs()
        val showAllDevices = prefs.getBoolean("dashboard_devices_all", true)
        val deviceList = prefs.getStringSet("dashboard_devices_multiselect", HashSet())
        return showAllDevices || deviceList.contains(device.address)
    }

    companion object {
        const val ACTION_CONFIG_CHANGE =
            "nodomain.freeyourgadget.gadgetbridge.activities.dashboardfragment.action.config_change"
        const val ACTION_START_EDITING =
            "nodomain.freeyourgadget.gadgetbridge.activities.dashboardfragment.action.start_editing"
        private const val KEY_DAY = "dashboard_day"
        private const val KEY_EDITING = "dashboard_editing"
    }
}
