package com.photogrammetry

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RecyclerView adapter for the file-manager dialog.
 * Uses [ListAdapter] / [DiffUtil] so updates animate correctly.
 */
class ScanFileAdapter(
    private val onShare:  (ScanFile) -> Unit,
    private val onOpen:   (ScanFile) -> Unit,
    private val onDelete: (ScanFile) -> Unit
) : ListAdapter<ScanFile, ScanFileAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanFile>() {
            override fun areItemsTheSame(a: ScanFile, b: ScanFile)    = a.file.path == b.file.path
            override fun areContentsTheSame(a: ScanFile, b: ScanFile) =
                a.file.lastModified() == b.file.lastModified() && a.file.length() == b.file.length()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val txtName:   TextView = itemView.findViewById(R.id.txt_filename)
        private val txtMeta:   TextView = itemView.findViewById(R.id.txt_meta)
        private val txtFormat: TextView = itemView.findViewById(R.id.txt_format)
        private val btnShare:  Button   = itemView.findViewById(R.id.btn_share)
        private val btnOpen:   Button   = itemView.findViewById(R.id.btn_open)
        private val btnDelete: Button   = itemView.findViewById(R.id.btn_delete)

        fun bind(scanFile: ScanFile) {
            txtName.text   = scanFile.displayName
            // Show synchronous data immediately; async data filled in background
            txtMeta.text   = "${scanFile.dateLabel}  \u2022  ${scanFile.sizeLabel}"
            txtFormat.text = scanFile.format.name

            // Colour-code the format badge
            val badgeColour = when (scanFile.format) {
                ScanFile.Format.PLY -> 0xFF1A73E8.toInt()
                ScanFile.Format.STL -> 0xFF0F9D58.toInt()
                ScanFile.Format.OBJ -> 0xFFFF6D00.toInt()
                ScanFile.Format.MP4 -> 0xFFE53935.toInt()
                else                -> 0xFF757575.toInt()
            }
            txtFormat.setBackgroundColor(badgeColour)

            btnShare.setOnClickListener  { onShare(scanFile)  }
            btnOpen.setOnClickListener   { onOpen(scanFile)   }
            btnDelete.setOnClickListener { onDelete(scanFile) }

            // Load point count + duration off the main thread, then update meta
            val scope = itemView.findViewTreeLifecycleOwner()?.lifecycleScope ?: return
            scope.launch {
                val pts = withContext(Dispatchers.IO) {
                    if (scanFile.format == ScanFile.Format.PLY ||
                        scanFile.format == ScanFile.Format.OBJ ||
                        scanFile.format == ScanFile.Format.STL) {
                        ScanFile.pointCountFromHeader(scanFile.file)?.let {
                            "  \u2022  ${"%,d".format(it)} pts"
                        } ?: ""
                    } else ""
                }
                val dur = withContext(Dispatchers.IO) { scanFile.durationLabel }
                // Guard against the ViewHolder being recycled to a different item
                if (txtName.text == scanFile.displayName) {
                    txtMeta.text = buildString {
                        append("${scanFile.dateLabel}  \u2022  ${scanFile.sizeLabel}")
                        if (pts.isNotEmpty()) append(pts)
                        if (dur.isNotEmpty()) append("  \u2022  $dur")
                    }
                }
            }
        }
    }
}
