package nodomain.freeyourgadget.gadgetbridge.widgets

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * One presentation of a [GBWidget] at one size, in grid cells. All variants of a widget show the same data.
 */
data class WidgetVariant(
    val id: String,
    @StringRes val name: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        @JvmField
        val GAUGE = WidgetVariant("gauge", R.string.widget_variant_gauge, 3, 2)

        @JvmField
        val SMALL = WidgetVariant("small", R.string.widget_variant_small, 3, 3)

        @JvmField
        val LARGE = WidgetVariant("large", R.string.widget_variant_large, 6, 6)
    }
}

/**
 * The variant with [variantId], or the default variant if there is none.
 */
fun GBWidget<*>.variant(variantId: String?): WidgetVariant =
    variants.find { it.id == variantId } ?: variants.first()
