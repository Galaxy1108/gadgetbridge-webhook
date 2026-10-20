package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetRegistry
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import java.text.Collator

/**
 * Lists all available widgets:
 * - A tap on a widget expands section with previews for all its variants.
 * - A tap on a preview returns the widget and the variant.
 */
class WidgetPickerActivity : AbstractGBActivity() {
    private lateinit var widgets: List<GBWidget<*>>
    private lateinit var layoutManager: LinearLayoutManager
    private var expanded = RecyclerView.NO_POSITION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_picker)

        val collator = Collator.getInstance()
        widgets = WidgetRegistry.available().sortedWith { a, b ->
            collator.compare(a.displayName(this), b.displayName(this))
        }

        val recyclerView = findViewById<RecyclerView>(R.id.widget_picker_list)
        layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = WidgetAdapter()
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun select(widget: GBWidget<*>, variant: WidgetVariant) {
        val result = Intent()
        result.putExtra(EXTRA_TYPE_ID, widget.id)
        result.putExtra(EXTRA_VARIANT_ID, variant.id)
        setResult(RESULT_OK, result)
        finish()
    }

    private inner class WidgetAdapter : RecyclerView.Adapter<WidgetAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: View = view.findViewById(R.id.widget_picker_header)
            val icon: ImageView = view.findViewById(R.id.widget_picker_icon)
            val name: TextView = view.findViewById(R.id.widget_picker_name)
            val variants: TextView = view.findViewById(R.id.widget_picker_variants)
            val previews: WidgetGridLayout = view.findViewById(R.id.widget_picker_previews)
        }

        override fun getItemCount(): Int = widgets.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_widget_picker, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val widget = widgets[position]
            val context = holder.itemView.context
            holder.icon.setImageResource(widget.icon)
            holder.name.text = widget.displayName(context)
            holder.variants.text =
                context.resources.getQuantityString(
                    R.plurals.widgets_variant_count,
                    widget.variants.size,
                    widget.variants.size
                )

            val isExpanded = position == expanded
            holder.previews.isVisible = isExpanded
            if (isExpanded) {
                showVariantPreviews(holder.previews, widget, null) { select(widget, it) }
            } else {
                holder.previews.removeAllViews()
            }

            holder.header.setOnClickListener {
                val previous = expanded
                val clicked = holder.bindingAdapterPosition
                val top = holder.itemView.top
                expanded = if (clicked == previous) RecyclerView.NO_POSITION else clicked
                if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
                if (expanded != RecyclerView.NO_POSITION) notifyItemChanged(expanded)
                // The row that collapses above would move the clicked row up
                layoutManager.scrollToPositionWithOffset(clicked, top)
            }
        }
    }

    companion object {
        const val EXTRA_TYPE_ID = "widget_type_id"
        const val EXTRA_VARIANT_ID = "widget_variant_id"
    }
}
