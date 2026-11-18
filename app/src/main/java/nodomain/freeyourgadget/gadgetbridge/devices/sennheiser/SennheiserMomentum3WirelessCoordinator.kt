package nodomain.freeyourgadget.gadgetbridge.devices.sennheiser

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.sennheiser.momentum3.SennheiserMomentum3Support
import java.util.regex.Pattern

/** Sennheiser MOMENTUM 3 Wireless (M3AEBTXL), controlled over its "Nautilus" serial port service. */
class SennheiserMomentum3WirelessCoordinator : AbstractBLClassicDeviceCoordinator() {
    override fun getDeviceNameResource(): Int = R.string.devicetype_sennheiser_momentum_3_wireless

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_headphones

    override fun getManufacturer(): String = "Sennheiser"

    override fun getSupportedDeviceName(): Pattern = Pattern.compile("MOMENTUM 3")

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.HEADPHONES

    override fun getBatteryCount(device: GBDevice): Int = 1

    override fun supportsAppNotifications(device: GBDevice): Boolean = true

    override fun supportsPhoneCalls(device: GBDevice): Boolean = true

    override fun supportsCallControl(device: GBDevice): Boolean = true

    override fun createDeviceSupport(device: GBDevice): DeviceSupport = SennheiserMomentum3Support()

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        xmlScreen(
            DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
            R.xml.devicesettings_headphones,
        )
    }
}
