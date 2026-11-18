package nodomain.freeyourgadget.gadgetbridge.devices.sennheiser

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.components.equalizerPreset
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.components.multipointPairing
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.sennheiser.momentum3.SennheiserMomentum3Equalizer
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
        multipointPairing()
        screen(
            key = DeviceSpecificSettingsScreen.SOUND.key,
            title = R.string.pref_header_sound,
            icon = R.drawable.ic_volume_up,
        ) {
            switchSetting(
                key = DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_HIGH_END,
                title = R.string.sennheiser_momentum_3_high_end_title,
                summary = R.string.sennheiser_momentum_3_high_end_summary,
                icon = R.drawable.ic_music_note,
                disableDependentsState = true,
            )
            equalizerPreset(
                key = DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_EQUALIZER,
                defaultValue = SennheiserMomentum3EqualizerPreset.CUSTOM,
                dependency = DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_HIGH_END,
            )
            listOf(
                DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_EQUALIZER_BASS to R.string.sennheiser_momentum_3_equalizer_bass,
                DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_EQUALIZER_MID to R.string.sennheiser_momentum_3_equalizer_mid,
                DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_EQUALIZER_TREBLE to R.string.sennheiser_momentum_3_equalizer_treble,
            ).forEach { (key, title) ->
                seekbar(
                    key = key,
                    title = title,
                    icon = R.drawable.ic_graphic_eq,
                    min = -SennheiserMomentum3Equalizer.MAX_DB,
                    max = SennheiserMomentum3Equalizer.MAX_DB,
                    defaultValue = 0,
                    dependency = DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_HIGH_END,
                    visibleWhen = { prefs ->
                        SennheiserMomentum3EqualizerPreset.fromPreference(
                            prefs.getString(DeviceSettingsPreferenceConst.PREF_SENNHEISER_MOMENTUM_3_EQUALIZER, "")
                        ) == SennheiserMomentum3EqualizerPreset.CUSTOM
                    },
                )
            }
        }
        xmlScreen(
            DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
            R.xml.devicesettings_headphones,
        )
    }
}
