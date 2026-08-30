package nodomain.freeyourgadget.gadgetbridge.model

import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.language.Transliterator

interface DeviceTextAdaptable<T> {
    fun transliterated(
        deviceSupport: DeviceSupport,
        deviceCoordinator: DeviceCoordinator,
        device: GBDevice,
        transliterator: Transliterator?
    ): T

    fun withRtlFix(): T

}