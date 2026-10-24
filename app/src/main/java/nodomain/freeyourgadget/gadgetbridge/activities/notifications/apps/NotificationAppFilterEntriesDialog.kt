package nodomain.freeyourgadget.gadgetbridge.activities.notifications.apps

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.DialogNotificationAppFilterEntriesBinding
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.apps.NotificationAppFilterSyntax
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.apps.NotificationAppRules

class NotificationAppFilterEntriesDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val arguments = requireArguments()
        val syntax = NotificationAppFilterSyntax.valueOf(requireNotNull(arguments.getString(KEY_SYNTAX)))
        val binding = DialogNotificationAppFilterEntriesBinding.inflate(layoutInflater)
        if (savedInstanceState == null) {
            binding.notificationAppFilterEntries.setText(
                arguments.getStringArrayList(KEY_ENTRIES).orEmpty().joinToString("\n")
            )
        }
        binding.notificationAppFilterEntriesLayout.helperText = getString(syntax.entriesHint)
        binding.notificationAppFilterEntries.doAfterTextChanged {
            binding.notificationAppFilterEntriesLayout.error = null
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(syntax.entriesTitle)
            .setView(binding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val lines = binding.notificationAppFilterEntries.text?.toString().orEmpty().lines()
                val error =
                    if (syntax == NotificationAppFilterSyntax.REGEX) firstRegexError(requireContext(), lines) else null
                if (error != null) {
                    binding.notificationAppFilterEntriesLayout.error = error
                    return@setOnClickListener
                }
                val entries = ArrayList(
                    // We trim spaces in words-mode, but not regex since spaces are meaningful there
                    lines.map { if (syntax == NotificationAppFilterSyntax.WORDS) it.trim() else it }
                        .filter { it.isNotBlank() }
                )
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply { putStringArrayList(KEY_ENTRIES, entries) })
                dialog.dismiss()
            }
        }
        return dialog
    }

    companion object {
        const val TAG = "NotificationAppFilterEntriesDialog"
        const val REQUEST_KEY = "notification_app_filter_entries"

        /**
         * The entries in the arguments and in the result, as a string array list.
         */
        const val KEY_ENTRIES = "entries"

        private const val KEY_SYNTAX = "syntax"

        fun newInstance(
            syntax: NotificationAppFilterSyntax,
            entries: List<String>
        ): NotificationAppFilterEntriesDialog {
            val dialog = NotificationAppFilterEntriesDialog()
            dialog.arguments = Bundle().apply {
                putString(KEY_SYNTAX, syntax.name)
                putStringArrayList(KEY_ENTRIES, ArrayList(entries))
            }
            return dialog
        }
    }
}

/**
 * The error of the first line in [lines] that is not a valid regex, with its line number.
 */
fun firstRegexError(context: Context, lines: List<String>): String? =
    lines.withIndex().firstNotNullOfOrNull { (index, line) ->
        NotificationAppRules.regexError(line)
            ?.let { context.getString(R.string.notification_apps_invalid_regex_line, index + 1, it) }
    }
