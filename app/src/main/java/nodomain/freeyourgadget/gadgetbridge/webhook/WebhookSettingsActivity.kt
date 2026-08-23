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

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

            val bindingCode = WebhookConfig.getOrCreateBindingCode()
            findPreference<Preference>(WebhookConfig.PREF_BINDING_CODE)?.summary =
                getString(R.string.webhook_pref_binding_code_summary, "GB-$bindingCode")

            val prefRunNow = findPreference<Preference>(WebhookConfig.PREF_RUN_NOW)
            prefRunNow?.setOnPreferenceClickListener {
                WebhookScheduler.executeNow()
                true
            }

            val prefEnabled = findPreference<Preference>(WebhookConfig.PREF_ENABLED)
            prefEnabled?.setOnPreferenceChangeListener { _: Preference?, _: Any? ->
                scheduleDelayed()
                true
            }

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

        private fun updateStatusRows() {
            val lastExecution = WebhookConfig.getLastExecution()
            val prefLastExecution = findPreference<Preference>(WebhookConfig.PREF_LAST_EXECUTION)
            prefLastExecution?.summary = if (lastExecution > 0) {
                DateTimeUtils.formatDateTime(Date(lastExecution))
            } else {
                getString(R.string.unknown)
            }
            findPreference<Preference>(WebhookConfig.PREF_LAST_STATUS)?.summary =
                WebhookConfig.getLastStatus().ifEmpty { getString(R.string.unknown) }
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(WebhookSettingsActivity::class.java)
    }
}
