/*  Copyright (C) 2026 gadgetbridge-webhook contributors

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
package nodomain.freeyourgadget.gadgetbridge.webhook

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Date

class WebhookSettingsActivity : AbstractSettingsActivityV2() {
    override fun newFragment(): PreferenceFragmentCompat {
        return WebhookSettingsFragment()
    }

    class WebhookSettingsFragment : AbstractPreferenceFragment() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.webhook_settings, rootKey)

            // First visit: persist the default "upload everything" selection so the
            // multi-select list shows all categories checked instead of an empty list.
            if (!GBApplication.getPrefs().preferences.contains(WebhookConfig.PREF_DATA_TYPES)) {
                GBApplication.getPrefs().preferences.edit {
                    putStringSet(WebhookConfig.PREF_DATA_TYPES, WebhookConfig.ALL_DATA_TYPES)
                }
            }

            val bindingCode = WebhookConfig.getOrCreateBindingCode()
            val prefBindingCode = findPreference<Preference>(WebhookConfig.PREF_BINDING_CODE)
            prefBindingCode?.summary =
                getString(R.string.webhook_pref_binding_code_summary, "GB-$bindingCode")
            prefBindingCode?.setOnPreferenceClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // Copy the full command so it can be pasted into chat directly.
                clipboard.setPrimaryClip(ClipData.newPlainText("bind command", "/bind GB-$bindingCode"))
                Toast.makeText(requireContext(), getString(R.string.webhook_binding_code_copied, bindingCode), Toast.LENGTH_SHORT).show()
                true
            }

            val prefRunNow = findPreference<Preference>(WebhookConfig.PREF_RUN_NOW)
            prefRunNow?.setOnPreferenceClickListener {
                // Run the upload in the background and show the result, instead of
                // silently enqueueing a worker.
                lifecycleScope.launch {
                    Toast.makeText(requireContext(), R.string.webhook_upload_started, Toast.LENGTH_SHORT).show()
                    val result = withContext(Dispatchers.IO) { WebhookUploader.uploadAll() }
                    val text = when {
                        result.pendingBind -> result.message
                        result.success -> getString(R.string.webhook_upload_done, result.uploadedSamples)
                        else -> getString(R.string.webhook_upload_failed, result.message)
                    }
                    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
                    updateStatusRows()
                }
                true
            }

            val prefResetCursor = findPreference<Preference>(WebhookConfig.PREF_RESET_CURSOR)
            prefResetCursor?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.webhook_pref_reset_cursor)
                    .setMessage(R.string.webhook_reset_cursor_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        WebhookConfig.resetCursors()
                        Toast.makeText(requireContext(), R.string.webhook_reset_cursor_done, Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }

            val prefEnabled = findPreference<Preference>(WebhookConfig.PREF_ENABLED)
            prefEnabled?.setOnPreferenceChangeListener { _: Preference?, _: Any? ->
                scheduleDelayed()
                true
            }

            val prefDataTypes = findPreference<Preference>(WebhookConfig.PREF_DATA_TYPES)
            prefDataTypes?.setOnPreferenceChangeListener { _: Preference?, _: Any? ->
                updateDataTypesSummary()
                true
            }
            updateDataTypesSummary()

            val prefInterval = findPreference<Preference>(WebhookConfig.PREF_INTERVAL_MINUTES)
            prefInterval?.setOnPreferenceChangeListener { _: Preference?, newValue: Any? ->
                updateIntervalSummary(newValue.toString().toIntOrNull() ?: 0)
                scheduleDelayed()
                true
            }
            updateIntervalSummary(WebhookConfig.getIntervalMinutes())

            updateStatusRows()
        }

        override fun onResume() {
            super.onResume()
            updateStatusRows()
        }

        private fun scheduleDelayed() {
            lifecycleScope.launch(Dispatchers.Main) {
                delay(200)
                WebhookScheduler.schedule(requireContext())
            }
        }

        private fun updateIntervalSummary(intervalMinutes: Int) {
            val summary = if (intervalMinutes > 0) {
                getString(R.string.webhook_pref_interval_summary, intervalMinutes)
            } else {
                getString(R.string.webhook_pref_interval_summary, WebhookConfig.DEFAULT_INTERVAL_MINUTES)
            }
            findPreference<Preference>(WebhookConfig.PREF_INTERVAL_MINUTES)?.summary = summary
        }

        private fun updateDataTypesSummary() {
            val enabled = WebhookConfig.getEnabledDataTypes()
            val summary = if (enabled.size == WebhookConfig.ALL_DATA_TYPES.size) {
                getString(R.string.webhook_pref_data_types_summary_all)
            } else {
                getString(R.string.webhook_pref_data_types_summary_count, enabled.size, WebhookConfig.ALL_DATA_TYPES.size)
            }
            findPreference<Preference>(WebhookConfig.PREF_DATA_TYPES)?.summary = summary
        }

        private fun updateStatusRows() {
            // Pairing status with the binding code hint when waiting.
            val bindingCode = WebhookConfig.getOrCreateBindingCode()
            val pairStatus = WebhookConfig.getPairStatus()
            val pairSummary = when (pairStatus) {
                WebhookConfig.PAIR_STATUS_OK -> getString(R.string.webhook_pair_status_ok)
                WebhookConfig.PAIR_STATUS_PENDING ->
                    getString(R.string.webhook_pair_status_pending, "GB-$bindingCode")
                WebhookConfig.PAIR_STATUS_FAILED -> getString(R.string.webhook_pair_status_failed)
                else -> getString(R.string.webhook_pair_status_unknown)
            }
            findPreference<Preference>(WebhookConfig.PREF_PAIR_STATUS)?.summary = pairSummary

            val lastExecution = WebhookConfig.getLastExecution()
            val prefLastExecution = findPreference<Preference>(WebhookConfig.PREF_LAST_EXECUTION)
            prefLastExecution?.summary = if (lastExecution > 0) {
                DateTimeUtils.formatDateTime(Date(lastExecution))
            } else {
                getString(R.string.unknown)
            }
            val lastError = WebhookConfig.getLastError()
            val prefLastStatus = findPreference<Preference>(WebhookConfig.PREF_LAST_STATUS)
            prefLastStatus?.summary = if (lastError.isNotEmpty()) {
                WebhookConfig.getLastStatus().ifEmpty { getString(R.string.unknown) } + "\n" + lastError
            } else {
                WebhookConfig.getLastStatus().ifEmpty { getString(R.string.unknown) }
            }
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(WebhookSettingsActivity::class.java)
    }
}
