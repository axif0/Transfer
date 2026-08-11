package com.matanh.transfer.util

import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.matanh.transfer.R

class FileAdapter(
    private var files: List<FileItem>,
    private val onItemClick: (FileItem, Int) -> Unit,
    private val onItemLongClick: (FileItem, Int) -> Boolean,
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val selectedItems = mutableSetOf<Int>()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvSize: TextView = view.findViewById(R.id.tvFileSize)
        val ivIcon: ImageView = view.findViewById(R.id.ivFileIcon)
        val ivSelectionCheck: ImageView = view.findViewById(R.id.ivSelectionCheck)
        val itemLayout: ConstraintLayout = view as ConstraintLayout
        var boundUri: android.net.Uri? = null

        fun bind(file: FileItem, position: Int, isSelected: Boolean) {
            boundUri = file.uri
            tvName.text = file.name
            tvSize.text = FileUtils.formatFileSize(file.size)
            ivIcon.setImageResource(FileTypeHelper.iconRes(file.name))
            ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE

            if (FileTypeHelper.kindOf(file.name) == FileTypeHelper.Kind.IMAGE) {
                FileTypeHelper.loadImageThumb(itemView.context, file.uri) { bmp ->
                    if (boundUri == file.uri && bmp != null) {
                        ivIcon.setImageDrawable(BitmapDrawable(itemView.resources, bmp))
                        ivIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                }
            }

            if (isSelected) {
                itemLayout.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.file_item_selected_background)
                )
                ivSelectionCheck.visibility = View.VISIBLE
            } else {
                itemLayout.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, R.color.default_file_item_background)
                )
                ivSelectionCheck.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(file, position) }
            itemView.setOnLongClickListener { onItemLongClick(file, position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position], position, selectedItems.contains(position))
    }

    override fun getItemCount(): Int = files.size

    fun updateFiles(newFiles: List<FileItem>) {
        files = newFiles
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(position: Int) {
        if (selectedItems.contains(position)) selectedItems.remove(position)
        else selectedItems.add(position)
        notifyItemChanged(position)
    }

    fun getSelectedFileItems(): List<FileItem> = selectedItems.map { files[it] }
    fun getSelectedItemCount(): Int = selectedItems.size

    fun clearSelections() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun getFileItem(position: Int): FileItem? = files.getOrNull(position)

    fun selectAll() {
        if (selectedItems.size == files.size) selectedItems.clear()
        else files.forEachIndexed { index, _ -> selectedItems.add(index) }
        notifyDataSetChanged()
    }
}
