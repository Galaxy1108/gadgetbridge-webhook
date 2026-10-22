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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardCalendarActivity
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardEvent
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardGridBinder
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardNotice
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardUiState
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardUndoAction
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DashboardViewModel
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GridDragController
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WidgetGridLayout
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WidgetInstanceSettingsActivity
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WidgetPickerActivity
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.showVariantPreviews
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentDashboardBinding
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.BarShade
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * Hosts the widget grid: date header, calendar/settings menu, and the [WidgetGridLayout] the
 * [DashboardGridBinder] renders the placed widgets into, driven by [DashboardViewModel]. In edit mode, the
 * widgets can be moved, added, changed and deleted.
 */
class DashboardFragment : Fragment(), MenuProvider, HeaderShadePage, DashboardGridBinder.Callbacks {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var binder: DashboardGridBinder? = null
    private lateinit var dragController: GridDragController
    private val viewModel: DashboardViewModel by viewModels { DashboardViewModel.Factory }

    private var isConfigChanged = false
    private var startEditingOnResume = false
    private var preserveEditingOnDestroy = false
    private var receiverRegistered = false
    private var actionMode: ActionMode? = null
    private var snackbar: Snackbar? = null
    private var lastItems: List<PlacedWidget> = emptyList()
    private var lastNotices: List<DashboardNotice> = emptyList()

    private val pickWidget = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult
        val typeId = data.getStringExtra(WidgetPickerActivity.EXTRA_TYPE_ID) ?: return@registerForActivityResult
        val variantId = data.getStringExtra(WidgetPickerActivity.EXTRA_VARIANT_ID) ?: return@registerForActivityResult
        viewModel.onWidgetPicked(typeId, variantId)
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
            snackbar?.dismiss()
            binder?.editing = false
            setEditControls(false)
            if (!preserveEditingOnDestroy) {
                viewModel.onFinishEditing()
            }
            preserveEditingOnDestroy = false
        }
    }

    private val calendarLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val timeMillis = result.data!!.getLongExtra(DashboardCalendarActivity.EXTRA_TIMESTAMP, 0)
            if (timeMillis != 0L) {
                viewModel.onDaySelected(timeMillis)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                GBApplication.ACTION_NEW_DATA -> intent.getDevice()?.let(viewModel::onNewData)
                ACTION_CONFIG_CHANGE -> isConfigChanged = true
                ACTION_START_EDITING -> startEditingOnResume = true
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentDashboardBinding.inflate(inflater, container, false)
        _binding = binding
        lastItems = emptyList()
        lastNotices = emptyList()

        binding.dashboardEditBar.setBackgroundColor(MaterialColors.getColor(binding.dashboardEditBar, R.attr.main_bar_bg))
        ViewCompat.setOnApplyWindowInsetsListener(binding.dashboardEditBar) { bar, insets ->
            bar.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        binding.dashboardDiscard.setOnClickListener { confirmDiscard() }
        binding.dashboardAddWidget.setOnClickListener { viewModel.onAddWidgetClicked() }
        binding.dashboardDone.setOnClickListener { actionMode?.finish() }

        binder = DashboardGridBinder(binding.dashboardGrid).apply {
            callbacks = this@DashboardFragment
        }
        binding.dashboardGrid.columns = WidgetGrid.columnsForWidth(resources.configuration.screenWidthDp)
        dragController = GridDragController(
            binding.dashboardGrid,
            binding.dashboardScroll,
            binder!!::instanceIdOf,
        ) { arrangement ->
            viewModel.onArrange(arrangement)
        }

        binding.arrowLeft.setOnClickListener { viewModel.onPreviousDay() }
        binding.arrowRight.setOnClickListener { viewModel.onNextDay() }

        // The pager resumes only the visible page
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        observeViewModel()
        viewModel.onColumnsChanged(binding.dashboardGrid.columns)

        val filter = IntentFilter()
        filter.addAction(GBDevice.ACTION_DEVICE_CHANGED)
        filter.addAction(GBApplication.ACTION_NEW_DATA)
        filter.addAction(ACTION_CONFIG_CHANGE)
        filter.addAction(ACTION_START_EDITING)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, filter)
        receiverRegistered = true

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (isConfigChanged) {
            isConfigChanged = false
            viewModel.onSettingsClosed()
        }
        if (startEditingOnResume) {
            startEditingOnResume = false
            startEditing()
        } else if (viewModel.uiState.value.editState != null) {
            startEditingActionMode()
        }
    }

    override fun onDestroyView() {
        preserveEditingOnDestroy = requireActivity().isChangingConfigurations &&
            viewModel.uiState.value.editState != null
        actionMode?.finish()
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver)
            receiverRegistered = false
        }
        binder = null
        _binding = null
        super.onDestroyView()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.dashboard_menu, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.dashboard_show_calendar -> {
                val intent = Intent(requireActivity(), DashboardCalendarActivity::class.java)
                val date = viewModel.uiState.value.date
                intent.putExtra(
                    DashboardCalendarActivity.EXTRA_TIMESTAMP,
                    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
            }
        }
    }

    private fun render(state: DashboardUiState) {
        val binding = _binding ?: return
        val binder = binder ?: return
        binder.cardsEnabled = state.settings.cardsEnabled
        binder.dashboardShowAllDevices = state.settings.showAllDevices
        binder.dashboardDeviceList = state.settings.deviceAddresses
        binder.timestamp = endOfDaySeconds(state.date)

        val items = state.items.map { it.placed }
        if (items != lastItems) {
            lastItems = items
            binder.submitItems(items)
        }
        binder.submitStates(state.items.associate { it.instanceId to it.state })

        renderDate(state, binding)
        bindNotices(state.notices)
        binding.dashboardDiscard.isEnabled = state.editState?.canDiscard == true
        binder.editing = state.editState != null

        if (state.editState != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            startEditingActionMode()
        }
    }

    private fun renderDate(state: DashboardUiState, binding: FragmentDashboardBinding) {
        if (state.isToday) {
            binding.dashboardDate.text = requireContext().getString(R.string.activity_summary_today)
            binding.arrowRight.alpha = 0.5f
        } else {
            val date = Date.from(state.date.atStartOfDay(ZoneId.systemDefault()).toInstant())
            binding.dashboardDate.text = DateTimeUtils.formatDate(date, DateUtils.FORMAT_SHOW_WEEKDAY)
            binding.arrowRight.alpha = 1f
        }
    }

    private fun handleEvent(event: DashboardEvent) {
        if (_binding == null) return
        when (event) {
            is DashboardEvent.WidgetAdded -> {
                binder?.cardOf(event.instanceId)?.doOnLayout { card ->
                    binding.dashboardScroll.smoothScrollTo(0, binding.dashboardGrid.top + card.top)
                }
                binder?.highlight(event.instanceId)
            }

            DashboardEvent.OpenWidgetPicker -> {
                pickWidget.launch(Intent(requireContext(), WidgetPickerActivity::class.java))
            }

            DashboardEvent.NoWidgetsAvailable -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.widgets_add_widget)
                    .setMessage(R.string.no_supported_devices_found)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            is DashboardEvent.ShowUndo -> showUndo(event.action)
        }
    }

    private fun showUndo(action: DashboardUndoAction) {
        if (_binding == null) return
        val message = when (action) {
            DashboardUndoAction.DELETE_WIDGET -> R.string.widgets_deleted
            DashboardUndoAction.RESTORE_CLASSIC_DESIGN -> R.string.dashboard_notice_design_restored
        }
        snackbar?.dismiss()
        snackbar = Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                when (action) {
                    DashboardUndoAction.DELETE_WIDGET -> viewModel.onUndoDelete()
                    DashboardUndoAction.RESTORE_CLASSIC_DESIGN -> viewModel.onUndoRestoreClassicDesign()
                }
            }
            .apply {
                if (action == DashboardUndoAction.DELETE_WIDGET) {
                    setAnchorView(binding.dashboardEditBar)
                }
                show()
            }
    }

    private fun bindNotices(notices: List<DashboardNotice>) {
        if (notices == lastNotices) return
        lastNotices = notices

        binding.dashboardNotices.removeAllViews()
        for (notice in notices) {
            when (notice) {
                DashboardNotice.Migration -> addNotice(
                    getString(R.string.dashboard_notice_new_design_title),
                    getString(R.string.dashboard_notice_new_design_text),
                    R.string.dashboard_notice_restore_design to ::restoreClassicDesign,
                    R.string.dashboard_edit to ::startEditing,
                ) {
                    viewModel.onDismissMigrationNotice()
                }

                is DashboardNotice.NewWidgets -> addNotice(
                    null,
                    getString(R.string.dashboard_notice_new_widgets, notice.deviceName),
                    null,
                    R.string.dashboard_notice_add_widgets to {
                        pickWidget.launch(
                            Intent(requireContext(), WidgetPickerActivity::class.java)
                                .putExtra(WidgetPickerActivity.EXTRA_NEW_DEVICE, notice.deviceAddress)
                        )
                    },
                ) {
                    viewModel.onDismissNewWidgetsNotice()
                }
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
        val notice = layoutInflater.inflate(R.layout.dashboard_notice, binding.dashboardNotices, false)
        notice.findViewById<TextView>(R.id.dashboard_notice_title).apply {
            this.text = title
            isVisible = title != null
        }
        notice.findViewById<TextView>(R.id.dashboard_notice_text).text = text
        notice.findViewById<View>(R.id.dashboard_notice_dismiss).setOnClickListener {
            dismiss()
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
        binding.dashboardNotices.addView(notice)
    }

    private fun restoreClassicDesign() {
        viewModel.onRestoreClassicDesign()
    }

    private fun startEditing() {
        viewModel.onStartEditing()
        startEditingActionMode()
    }

    private fun startEditingActionMode() {
        if (actionMode != null) return
        actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(editCallback) ?: return
        setEditControls(true)
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.workout_template_discard_title)
            .setPositiveButton(R.string.workout_template_discard) { _, _ -> viewModel.onDiscardEditing() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Shows the edit bar in place of the bottom navigation, and hides the date arrows and disables the page swipe in
     * the edit mode.
     */
    private fun setEditControls(editing: Boolean) {
        val binding = _binding ?: return
        binding.arrowLeft.isInvisible = editing
        binding.arrowRight.isInvisible = editing
        binding.dashboardEditBar.isVisible = editing
        (activity as? DashboardHost)?.onDashboardEditingChanged(editing)
    }

    override fun onLongPress(item: PlacedWidget, card: View) {
        startEditing()
        dragController.start(card)
    }

    override fun onEditClick(item: PlacedWidget, button: View) {
        val binding = _binding ?: return
        val columns = binding.dashboardGrid.columns
        val instanceId = item.instance.instanceId
        val popup = PopupMenu(requireContext(), button)
        popup.inflate(R.menu.dashboard_widget_edit_menu)
        popup.menu.findItem(R.id.dashboard_widget_variant).isVisible = item.widget.variants.size > 1
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.dashboard_widget_variant -> showVariantSheet(item)
                R.id.dashboard_widget_settings -> WidgetInstanceSettingsActivity.startForWidget(
                    requireContext(),
                    columns,
                    instanceId,
                )

                R.id.dashboard_widget_duplicate -> viewModel.onDuplicate(instanceId)

                R.id.dashboard_widget_delete -> viewModel.onDelete(instanceId)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    override fun onMove(item: PlacedWidget, offset: Int) {
        viewModel.onMove(item.instance.instanceId, offset, item.x)
    }

    /**
     * Shows the variants of [item] in a bottom sheet.
     */
    @SuppressLint("InflateParams")  // The bottom sheet sets the layout params of its content view
    private fun showVariantSheet(item: PlacedWidget) {
        val sheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dashboard_widget_edit_sheet, null)

        view.findViewById<TextView>(R.id.dashboard_edit_title).text = item.widget.displayName(requireContext())
        val variants = view.findViewById<WidgetGridLayout>(R.id.dashboard_edit_variants)
        showVariantPreviews(variants, item.widget, item.variant.id) { variant ->
            viewModel.onVariantSelected(item.instance.instanceId, variant.id)
            sheet.dismiss()
        }

        sheet.setContentView(view)
        sheet.behavior.maxWidth = resources.displayMetrics.widthPixels
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    override fun getHeaderShade(): View? {
        val shade = _binding?.root?.findViewById<View>(R.id.dashboard_date_row_shade) ?: return null
        return if (BarShade.continuesToolbar(shade.context, R.attr.datestep_row_bg)) shade else null
    }

    private fun endOfDaySeconds(date: java.time.LocalDate): Int =
        date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toEpochSecond().toInt()

    companion object {
        const val ACTION_CONFIG_CHANGE =
            "nodomain.freeyourgadget.gadgetbridge.activities.dashboardfragment.action.config_change"
        const val ACTION_START_EDITING =
            "nodomain.freeyourgadget.gadgetbridge.activities.dashboardfragment.action.start_editing"
    }
}
