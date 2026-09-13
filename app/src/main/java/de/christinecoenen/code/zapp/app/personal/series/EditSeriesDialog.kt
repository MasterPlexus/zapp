package de.christinecoenen.code.zapp.app.personal.series

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.christinecoenen.code.zapp.R
import de.christinecoenen.code.zapp.databinding.DialogEditSeriesBinding
import de.christinecoenen.code.zapp.models.collections.ShowCollection

/**
 * Dialog to create a new or edit an existing [ShowCollection].
 */
class EditSeriesDialog : AppCompatDialogFragment() {

	companion object {

		const val REQUEST_KEY_SAVE = "REQUEST_KEY_SERIES_SAVE"
		const val RESULT_ID = "RESULT_SERIES_ID"
		const val RESULT_NAME = "RESULT_SERIES_NAME"
		const val RESULT_QUERY = "RESULT_SERIES_QUERY"
		const val RESULT_MARK_ALL_AS_WATCHED = "RESULT_SERIES_MARK_ALL_AS_WATCHED"

		private const val ARG_ID = "ARG_SERIES_ID"
		private const val ARG_NAME = "ARG_SERIES_NAME"
		private const val ARG_QUERY = "ARG_SERIES_QUERY"
		private const val ARG_MARK_ALL_AS_WATCHED = "ARG_SERIES_MARK_ALL_AS_WATCHED"

		/**
		 * @param collection Collection to edit or null to create a new one.
		 */
		@JvmStatic
		fun newInstance(collection: ShowCollection?): EditSeriesDialog {
			return EditSeriesDialog().apply {
				arguments = bundleOf(
					ARG_ID to (collection?.id ?: 0),
					ARG_NAME to (collection?.name.orEmpty()),
					ARG_QUERY to (collection?.searchQuery.orEmpty()),
					ARG_MARK_ALL_AS_WATCHED to (collection?.markAllAsWatched ?: false)
				)
			}
		}
	}

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val binding = DialogEditSeriesBinding.inflate(layoutInflater)
		val args = requireArguments()
		val collectionId = args.getInt(ARG_ID)

		binding.name.setText(args.getString(ARG_NAME))
		binding.query.setText(args.getString(ARG_QUERY))
		binding.markAllAsWatched.isChecked = args.getBoolean(ARG_MARK_ALL_AS_WATCHED)

		val dialog = MaterialAlertDialogBuilder(requireActivity())
			.setTitle(
				if (collectionId == 0) R.string.dialog_series_create_title
				else R.string.dialog_series_edit_title
			)
			.setView(binding.root)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, null)
			.create()

		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val name = binding.name.text?.toString().orEmpty().trim()
				val searchQuery = binding.query.text?.toString().orEmpty().trim()

				if (name.isEmpty()) {
					binding.nameLayout.error = getString(R.string.error_series_missing_name)
					return@setOnClickListener
				}
				binding.nameLayout.error = null

				if (searchQuery.isEmpty()) {
					binding.queryLayout.error = getString(R.string.error_series_missing_query)
					return@setOnClickListener
				}
				binding.queryLayout.error = null

				setFragmentResult(
					REQUEST_KEY_SAVE,
					bundleOf(
						RESULT_ID to collectionId,
						RESULT_NAME to name,
						RESULT_QUERY to searchQuery,
						RESULT_MARK_ALL_AS_WATCHED to binding.markAllAsWatched.isChecked
					)
				)
				dismiss()
			}
		}

		return dialog
	}
}
