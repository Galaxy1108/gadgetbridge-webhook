package nodomain.freeyourgadget.gadgetbridge.model

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.LabeledEntry
import java.util.Locale

enum class HydrationUnit(
    override val label: Int,
    val ml: Double,
) : LabeledEntry {
    MILLILITER(R.string.hydration_unit_milliliter, 1.0),
    CUP(R.string.hydration_unit_cup, 236.588),
    OUNCE(R.string.hydration_unit_ounce, 29.5735),
    ;

    val key: String
        get() = name.lowercase(Locale.ROOT)

    companion object {
        @JvmStatic
        fun fromKey(key: String?): HydrationUnit? = entries.firstOrNull { it.key == key }
    }
}
