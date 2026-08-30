/*  Copyright (C) 2015-2024 Andreas Shimokawa, Arjan Schrijver, Daniele
    Gobbetti, Frank Slezak, José Rebelo, mvn23, Petr Kadlec

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
package nodomain.freeyourgadget.gadgetbridge.model

import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.RtlUtils
import nodomain.freeyourgadget.gadgetbridge.util.language.Transliterator
import nodomain.freeyourgadget.gadgetbridge.util.sanitizeText

import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

data class NotificationSpec @JvmOverloads constructor(
    private val requestedId: Int = -1,
    val `when`: Long = System.currentTimeMillis(),
    var flags: Int = 0,
    var key: String? = null,
    var sender: String? = null,
    var phoneNumber: String? = null,
    var title: String? = null,
    var subject: String? = null,
    var body: String? = null,
    var type: NotificationType? = null,
    var sourceName: String? = null,
    var channelId: String? = null,
    var category: String? = null,
    var cannedReplies: Array<String>? = null,
    var attachedActions: ArrayList<Action?>? = null,
    var sourceAppId: String? = null,
    var iconId: Int = 0,
    var iconPackageId: String? = null,
    var picturePath: String? = null,
    var dndSuppressed: Int = 0
) : DeviceTextAdaptable<NotificationSpec> {
    val id: Int = if (requestedId != -1) requestedId else c.incrementAndGet()

    override fun withRtlFix(): NotificationSpec {
        if (!RtlUtils.rtlSupport()) return this
        return copy(
            sender = sender?.let(RtlUtils::fixRtl),
            subject = subject?.let(RtlUtils::fixRtl),
            title = title?.let(RtlUtils::fixRtl),
            body = body?.let(RtlUtils::fixRtl),
            sourceName = sourceName?.let(RtlUtils::fixRtl)
        )
    }

    override fun transliterated(
        deviceSupport: DeviceSupport,
        deviceCoordinator: DeviceCoordinator,
        device: GBDevice,
        transliterator: Transliterator?
    ): NotificationSpec {
        fun transform(text: String?): String? {
            val sanitized = sanitizeText(deviceSupport, deviceCoordinator, device, text)
            return transliterator?.let { sanitized?.let(it::transliterate) } ?: sanitized
        }
        return copy(
            sender = transform(sender),
            subject = transform(subject),
            title = transform(title),
            body = transform(body),
            sourceName = transform(sourceName)
        )
    }

    companion object {
        private val c = AtomicInteger((System.currentTimeMillis() / 1000).toInt())
    }

    class Action : Serializable {
        var type: Int = TYPE_UNDEFINED
        var handle: Long = 0
        var title: String? = null

        val isReply: Boolean
            get() = type == TYPE_WEARABLE_REPLY || type == TYPE_SYNTHETIC_REPLY_PHONENR || type == TYPE_CUSTOM_REPLY

        companion object {
            const val TYPE_UNDEFINED: Int = -1
            const val TYPE_WEARABLE_SIMPLE: Int = 0
            const val TYPE_WEARABLE_REPLY: Int = 1
            const val TYPE_SYNTHETIC_REPLY_PHONENR: Int = 2
            const val TYPE_SYNTHETIC_DISMISS: Int = 3
            const val TYPE_SYNTHETIC_DISMISS_ALL: Int = 4
            const val TYPE_SYNTHETIC_MUTE: Int = 5
            const val TYPE_SYNTHETIC_OPEN: Int = 6
            const val TYPE_CUSTOM_SIMPLE: Int = 7
            const val TYPE_CUSTOM_REPLY: Int = 8
        }
    }
}