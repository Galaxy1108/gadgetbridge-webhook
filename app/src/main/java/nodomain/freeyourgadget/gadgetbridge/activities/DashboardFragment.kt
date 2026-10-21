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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
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
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardMigration
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardNotices
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardSnapshot
import nodomain.freeyourgadget.gadgetbridge.widgets.DeviceNotice
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetRegistry
import java.util.Calendar
import java.util.GregorianCalendar

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
    private lateinit var editBar: View
    private lateinit var discardButton: MaterialButton
    private lateinit var notices: LinearLayout
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
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            deleteSnackbar?.dismiss()
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
        editBar = view.findViewById(R.id.dashboard_edit_bar)
        editBar.setBackgroundColor(MaterialColors.getColor(editBar, R.attr.main_bar_bg))
        ViewCompat.setOnApplyWindowInsetsListener(editBar) { bar, insets ->
            bar.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        discardButton = view.findViewById(R.id.dashboard_discard)
        discardButton.setOnClickListener { confirmDiscard() }
        view.findViewById<View>(R.id.dashboard_add_widget).setOnClickListener { addWidget() }
        view.findViewById<View>(R.id.dashboard_done).setOnClickListener { actionMode?.finish() }
        notices = view.findViewById(R.id.dashboard_notices)
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

        // The pager resumes only the visible page
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
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
        updateDiscardButton()
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
                updateDiscardButton()
                newInstanceId?.let { instanceId ->
                    newInstanceId = null
                    binder.cardOf(instanceId)?.doOnLayout { scrollView.smoothScrollTo(0, grid.top + it.top) }
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
        bindNotices()
    }

    private fun bindNotices() {
        notices.removeAllViews()
        val state = DashboardNotices.state()
        if (state.migration) {
            addNotice(
                getString(R.string.dashboard_notice_new_design_title),
                getString(R.string.dashboard_notice_new_design_text),
                R.string.dashboard_notice_restore_design to ::restoreClassicDesign,
                R.string.dashboard_edit to ::startEditing,
            ) {
                DashboardNotices.set(DashboardNotices.state().copy(migration = false))
            }
        }
        state.device?.let { device ->
            val name = GBApplication.app().deviceManager.getDeviceByAddress(device.address)?.aliasOrName
                ?: device.address
            addNotice(
                null,
                getString(R.string.dashboard_notice_widgets_added, name),
                null,
                R.string.dashboard_notice_remove_widgets to { removeAddedWidgets(device) },
            ) {
                DashboardNotices.set(DashboardNotices.state().copy(device = null))
            }
        }
    }

    private fun addNotice(
        title: String?,
        text: String,
        secondary: Pair<Int, () -> Unit>?,
        primary: Pair<Int, () -> Unit>,
        dismiss: () -> Unit,
    ) {
        val notice = layoutInflater.inflate(R.layout.dashboard_notice, notices, false)
        notice.findViewById<TextView>(R.id.dashboard_notice_title).apply {
            this.text = title
            isVisible = title != null
        }
        notice.findViewById<TextView>(R.id.dashboard_notice_text).text = text
        notice.findViewById<View>(R.id.dashboard_notice_dismiss).setOnClickListener {
            dismiss()
            bindNotices()
        }
        notice.findViewById<Button>(R.id.dashboard_notice_secondary).apply {
            isVisible = secondary != null
            secondary?.let { (label, action) ->
                setText(label)
                setOnClickListener { action() }
            }
        }
        notice.findViewById<Button>(R.id.dashboard_notice_primary).apply {
            setText(primary.first)
            setOnClickListener { primary.second() }
        }
        notices.addView(notice)
    }

    private fun restoreClassicDesign() {
        val snapshot = WidgetLayoutStore.snapshot()
        val state = DashboardNotices.state()
        DashboardMigration.restoreClassic()
        DashboardNotices.set(DashboardNotices.State.NONE)
        fullRefresh()
        showUndo(R.string.dashboard_notice_design_restored, snapshot, state)
    }

    private fun removeAddedWidgets(notice: DeviceNotice) {
        val snapshot = WidgetLayoutStore.snapshot()
        val state = DashboardNotices.state()
        WidgetLayoutStore.deleteInstances(notice.instanceIds)
        DashboardNotices.set(state.copy(device = null))
        fullRefresh()
        showUndo(R.string.dashboard_notice_widgets_removed, snapshot, state)
    }

    /**
     * Shows [message] with an action that restores [snapshot] and [notices].
     */
    private fun showUndo(@StringRes message: Int, snapshot: DashboardSnapshot, notices: DashboardNotices.State) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                WidgetLayoutStore.restore(snapshot)
                DashboardNotices.set(notices)
                fullRefresh()
            }
            .show()
    }

    private fun startEditing() {
        if (actionMode != null) return
        actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(editCallback) ?: return
        if (viewModel.editSnapshot == null) {
            viewModel.editSnapshot = WidgetLayoutStore.snapshot()
        }
        DashboardNotices.set(DashboardNotices.State.NONE)
        bindNotices()
        binder.editing = true
        setEditControls(true)
        updateDiscardButton()
    }

    private fun updateDiscardButton() {
        val snapshot = viewModel.editSnapshot
        discardButton.isEnabled = snapshot != null && snapshot != WidgetLayoutStore.snapshot()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_template_discard_title)
            .setPositiveButton(R.string.workout_template_discard) { _, _ -> discardEdits() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Restores the dashboard to its state at the start of the edit mode, and ends the edit mode.
     */
    private fun discardEdits() {
        val snapshot = viewModel.editSnapshot ?: return
        deleteSnackbar?.dismiss()
        WidgetLayoutStore.restore(snapshot)
        actionMode?.finish()
        fullRefresh()
    }

    /**
     * Shows the edit bar in place of the bottom navigation, and hides the date arrows and disables the page swipe in
     * the edit mode.
     */
    private fun setEditControls(editing: Boolean) {
        arrowLeft.isInvisible = editing
        arrowRight.isInvisible = editing
        editBar.isVisible = editing
        (activity as? ControlCenterv2)?.setPageBottomBarShown(editing)
        activity?.findViewById<ViewPager2>(R.id.dashboard_viewpager)?.isUserInputEnabled = !editing
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

    override fun onEditClick(item: PlacedWidget, button: View) {
        val columns = grid.columns
        val instanceId = item.instance.instanceId
        val popup = PopupMenu(requireContext(), button)
        popup.inflate(R.menu.dashboard_widget_edit_menu)
        popup.menu.findItem(R.id.dashboard_widget_variant).isVisible = item.widget.variants.size > 1
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.dashboard_widget_variant -> showVariantSheet(item)
                R.id.dashboard_widget_settings -> settingsInstanceId =
                    WidgetInstanceSettingsActivity.startForWidget(requireContext(), columns, instanceId)?.instanceId

                R.id.dashboard_widget_duplicate -> {
                    newInstanceId = WidgetLayoutStore.duplicate(columns, instanceId)?.instanceId
                    viewModel.updateLayout(columns)
                }

                R.id.dashboard_widget_delete -> deleteWidget(instanceId)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    override fun onMove(item: PlacedWidget, offset: Int) {
        val index = viewModel.items.value.indexOfFirst { it.instance.instanceId == item.instance.instanceId } + offset
        if (index < 0) return
        WidgetLayoutStore.move(grid.columns, item.instance.instanceId, index, item.x)
        viewModel.updateLayout(grid.columns)
    }

    private fun deleteWidget(instanceId: String) {
        val columns = grid.columns
        val deleted = WidgetLayoutStore.delete(columns, instanceId) ?: return
        viewModel.updateLayout(columns)
        deleteSnackbar = Snackbar.make(requireView(), R.string.widgets_deleted, Snackbar.LENGTH_LONG)
            .setAnchorView(editBar)
            .setAction(R.string.undo) {
                WidgetLayoutStore.restore(deleted)
                viewModel.updateLayout(columns)
            }
            .apply { show() }
    }

    /**
     * Shows the variants of [item] in a bottom sheet.
     */
    @SuppressLint("InflateParams")  // The bottom sheet sets the layout params of its content view
    private fun showVariantSheet(item: PlacedWidget) {
        val columns = grid.columns
        val sheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dashboard_widget_edit_sheet, null)

        view.findViewById<TextView>(R.id.dashboard_edit_title).text = item.widget.displayName(requireContext())
        val variants = view.findViewById<WidgetGridLayout>(R.id.dashboard_edit_variants)
        showVariantPreviews(variants, item.widget, item.variant.id) { variant ->
            WidgetLayoutStore.setVariant(columns, item.instance.instanceId, variant.id)
            viewModel.updateLayout(columns)
            sheet.dismiss()
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
