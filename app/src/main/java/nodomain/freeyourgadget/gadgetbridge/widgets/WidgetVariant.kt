package nodomain.freeyourgadget.gadgetbridge.widgets

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * One presentation of a [GBWidget] at one size, in grid cells: columns wide and rows of a half-step high. All
 * variants of a widget show the same data.
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
        val NUMBER = WidgetVariant("number", R.string.widget_variant_number, 3, 2)

        @JvmField
        val TALL = WidgetVariant("tall", R.string.widget_variant_tall, 3, 3)

        @JvmField
        val WIDE = WidgetVariant("wide", R.string.widget_variant_wide, 6, 2)

        /**
         * The large tile of a single metric.
         */
        @JvmField
        val METRIC_LARGE = WidgetVariant("large", R.string.widget_variant_large, 6, 3)

        /**
         * The small size of the day and goals overviews.
         */
        @JvmField
        val SMALL = WidgetVariant("small", R.string.widget_variant_small, 6, 2)

        /**
         * The single-column size of the day and goals overviews.
         */
        @JvmField
        val OVERVIEW_TALL = WidgetVariant("tall", R.string.widget_variant_tall, 3, 4)

        /**
         * The large size of the day and goals overviews.
         */
        @JvmField
        val LARGE = WidgetVariant("large", R.string.widget_variant_large, 6, 5)
    }
}

/**
 * The variant with [variantId], or the default variant if there is none.
 */
fun GBWidget<*>.variant(variantId: String?): WidgetVariant =
    variants.find { it.id == variantId } ?: variants.first()
