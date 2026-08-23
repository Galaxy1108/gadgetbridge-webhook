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

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Parcel
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Diagnostics: logs the serialized size of every Activity's saved instance
 * state right before the system transfers it. TransactionTooLargeException
 * crashes happen during that transfer, so the log line identifies the exact
 * activity that is about to exceed the Binder limit (e.g. "Saved state size
 * for ControlCenterv2: 691096 bytes").
 */
object WebhookStateMonitor : Application.ActivityLifecycleCallbacks {

    private val LOG: Logger = LoggerFactory.getLogger(WebhookStateMonitor::class.java)

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        try {
            val parcel = Parcel.obtain()
            try {
                outState.writeToParcel(parcel, 0)
                val size = parcel.dataSize()
                LOG.warn(
                    "Saved state size for {}: {} bytes",
                    activity.javaClass.simpleName,
                    size
                )
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            LOG.warn("Could not measure saved state for {}", activity.javaClass.simpleName)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
