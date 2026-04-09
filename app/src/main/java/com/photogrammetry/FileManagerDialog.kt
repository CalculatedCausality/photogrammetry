package com.photogrammetry

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen file-manager dialog that lists all PLY and STL scan files
 * saved by the app. Per-file actions: Share, Open (with an external app),
 * and Delete.
 *
 * Launch from any FragmentActivity / AppCompatActivity:
 *   FileManagerDialog.newInstance().show(supportFragmentManager, "files")
 */
class FileManagerDialog : DialogFragment() {

    companion object {
        fun newInstance() = FileManagerDialog()
    }

    private lateinit var adapter:    ScanFileAdapter
    private lateinit var emptyText:  TextView
    private lateinit var recycler:   RecyclerView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx  = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_file_manager, null)

        recycler  = view.findViewById(R.id.recycler_files)
        emptyText = view.findViewById(R.id.text_empty)

        adapter = ScanFileAdapter(
            onShare  = { scanFile -> shareFile(scanFile.file) },
            onOpen   = { scanFile -> openFile(scanFile.file) },
            onDelete = { scanFile -> confirmDelete(scanFile) }
        )

        recycler.layoutManager = LinearLayoutManager(ctx)
        recycler.addItemDecoration(DividerItemDecoration(ctx, DividerItemDecoration.VERTICAL))
        recycler.adapter = adapter

        refreshList()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Saved Scans")
            .setView(view)
            .setPositiveButton("Close", null)
            .create()

        return dialog
    }

    // -------------------------------------------------------------------------
    // List management
    // -------------------------------------------------------------------------

    private fun refreshList() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { ScanFile.listAll(requireContext()) }
            if (!isAdded) return@launch
            adapter.submitList(files)
            emptyText.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            recycler.visibility  = if (files.isEmpty()) android.view.View.GONE   else android.view.View.VISIBLE
            if (files.isNotEmpty()) {
                val totalBytes = files.sumOf { it.file.length() }
                val totalLabel = when {
                    totalBytes < 1_048_576L -> "${"%.1f".format(totalBytes / 1_024f)} KB"
                    else                    -> "${"%.1f".format(totalBytes / 1_048_576f)} MB"
                }
                dialog?.setTitle("Saved Scans (${files.size} files \u00b7 $totalLabel)")
            } else {
                dialog?.setTitle("Saved Scans")
            }
        }
    }

    // -------------------------------------------------------------------------
    // File actions
    // -------------------------------------------------------------------------

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type    = mimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No app installed to open ${file.extension.uppercase()} files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(scanFile: ScanFile) {
        lifecycleScope.launch {
            val ptsStr = withContext(Dispatchers.IO) {
                ScanFile.pointCountFromHeader(scanFile.file)?.let { pts ->
                    "  \u2022  ${"% ,d".format(pts)} pts"
                } ?: ""
            }
            if (!isAdded) return@launch
            AlertDialog.Builder(requireContext())
                .setTitle("Delete scan?")
                .setMessage("${scanFile.displayName}\n${scanFile.sizeLabel}$ptsStr")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        scanFile.file.delete()
                        withContext(Dispatchers.Main) { if (isAdded) refreshList() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mimeType(file: File) = when (file.extension.lowercase()) {
        "ply" -> "application/octet-stream"
        "stl" -> "model/stl"
        "obj" -> "model/obj"
        "mp4" -> "video/mp4"
        else  -> "*/*"
    }
}
