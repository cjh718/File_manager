package com.folio.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.folio.files.databinding.SheetSelectionMoreBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * "More" quick action for the selection bar: the remaining file actions that do not fit in the
 * bar. Single-item actions enable only when exactly one row is selected.
 */
class SelectionMoreSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSelectionMoreBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetSelectionMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        val selected = activity.selectedItems()
        if (selected.isEmpty()) {
            dismiss()
            return
        }
        val single = selected.singleOrNull()

        binding.sheetCopy.setOnClickListener {
            activity.beginTransfer(selected, move = false)
            dismiss()
        }
        binding.sheetSelectAll.setOnClickListener {
            activity.selectAllVisible()
            dismiss()
        }
        binding.sheetRename.isEnabled = single != null && activity.canModify(single.file)
        binding.sheetRename.setOnClickListener {
            single?.let(activity::showRenameDialog)
            dismiss()
        }
        binding.sheetDetails.isEnabled = single != null
        binding.sheetDetails.setOnClickListener {
            single?.let(activity::showDetails)
            dismiss()
        }
        binding.sheetOpenWith.isEnabled = single != null && !single.isDirectory
        binding.sheetOpenWith.setOnClickListener {
            single?.let { activity.openFile(it.file, forceChooser = true) }
            dismiss()
        }
        binding.sheetQuickAccess.isEnabled =
            single != null && single.isDirectory && activity.isInPrimaryStorage(single.file)
        binding.sheetQuickAccess.setOnClickListener {
            single?.let { activity.addFolderToQuickAccess(it.file) }
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}