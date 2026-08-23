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

import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.GBApplication

/**
 * Configuration for the Webhook upload module.
 *
 * All values are stored in the regular Gadgetbridge shared preferences, using the
 * "webhook_" prefix. Everything is a pure read/write wrapper around [GBApplication.getPrefs],
 * so this module never touches any upstream code paths.
 */
object WebhookConfig {

    const val PREF_ENABLED = "webhook_enabled"
    const val PREF_SERVER_URL = "webhook_server_url"
    const val PREF_TOKEN = "webhook_token"
    const val PREF_INTERVAL_MINUTES = "webhook_interval_minutes"
    const val PREF_ALLOW_INSECURE = "webhook_allow_insecure"

    /** Settings screen only: "upload now" button (not persisted). */
    const val PREF_RUN_NOW = "webhook_run_now"

    /** When the last upload (any kind) finished, epoch millis. */
    const val PREF_LAST_EXECUTION = "webhook_last_execution"

    /** Human readable result of the last upload, shown on the settings screen. */
    const val PREF_LAST_STATUS = "webhook_last_status"

    /** Epoch millis of the last sync-triggered (immediate) upload, for rate limiting. */
    const val PREF_LAST_IMMEDIATE = "webhook_last_immediate"

    const val DEFAULT_INTERVAL_MINUTES = 15

    /** Minimum gap between sync-triggered uploads, to avoid hammering the server. */
    const val MIN_IMMEDIATE_INTERVAL_MS = 2 * 60 * 1000L

    /** How far back the first upload goes when no cursor exists yet, in seconds. */
    const val INITIAL_BACKFILL_SECONDS = 24 * 60 * 60L

    /** Safety cap for a single upload request, in seconds (one week). */
    const val MAX_RANGE_SECONDS = 7 * 24 * 60 * 60L

    /** Per-device upload cursor key: last successfully uploaded timestamp (epoch seconds). */
    fun cursorKey(address: String): String = "webhook_cursor_$address"

    fun isEnabled(): Boolean = GBApplication.getPrefs().getBoolean(PREF_ENABLED, false)

    fun getServerUrl(): String = GBApplication.getPrefs().getString(PREF_SERVER_URL, "").trim()

    fun getToken(): String = GBApplication.getPrefs().getString(PREF_TOKEN, "").trim()

    fun getIntervalMinutes(): Int =
        GBApplication.getPrefs().getInt(PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES).coerceAtLeast(1)

    fun allowInsecure(): Boolean = GBApplication.getPrefs().getBoolean(PREF_ALLOW_INSECURE, false)

    fun getCursor(address: String): Long = GBApplication.getPrefs().getLong(cursorKey(address), 0)

    fun setCursor(address: String, timestampSeconds: Long) {
        GBApplication.getPrefs().preferences.edit {
            putLong(cursorKey(address), timestampSeconds)
        }
    }

    fun getLastExecution(): Long = GBApplication.getPrefs().getLong(PREF_LAST_EXECUTION, 0)

    fun setLastExecution(timestampMillis: Long) {
        GBApplication.getPrefs().preferences.edit {
            putLong(PREF_LAST_EXECUTION, timestampMillis)
        }
    }

    fun getLastStatus(): String = GBApplication.getPrefs().getString(PREF_LAST_STATUS, "")

    fun setLastStatus(status: String) {
        GBApplication.getPrefs().preferences.edit {
            putString(PREF_LAST_STATUS, status)
        }
    }

    fun getLastImmediate(): Long = GBApplication.getPrefs().getLong(PREF_LAST_IMMEDIATE, 0)

    fun setLastImmediate(timestampMillis: Long) {
        GBApplication.getPrefs().preferences.edit {
            putLong(PREF_LAST_IMMEDIATE, timestampMillis)
        }
    }
}
