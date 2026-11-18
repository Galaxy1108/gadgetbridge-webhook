package nodomain.freeyourgadget.gadgetbridge.devices.sennheiser

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.LabeledEntry

/** The noise cancelling modes with their codes; transparent hearing replaces noise cancelling. */
enum class SennheiserMomentum3NoiseControl(override val label: Int, val code: Int?) : LabeledEntry {
    MAXIMUM(R.string.sennheiser_momentum_3_noise_control_maximum, 1),
    ANTI_WIND(R.string.sennheiser_momentum_3_noise_control_anti_wind, 3),
    ANTI_PRESSURE(R.string.sennheiser_momentum_3_noise_control_anti_pressure, 4),
    TRANSPARENT_HEARING(R.string.sennheiser_momentum_3_noise_control_transparent_hearing, null);

    companion object {
        fun fromCode(code: Int): SennheiserMomentum3NoiseControl? = entries.firstOrNull { it.code == code }

        fun fromPreference(value: String?): SennheiserMomentum3NoiseControl? =
            entries.firstOrNull { it.name.lowercase() == value }
    }
}
