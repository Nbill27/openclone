package dev.bill.openclone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.bill.openclone.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One history row: the stored record plus what is currently true about its
 * APK on disk. The file can disappear (cache clear, manual delete) while the
 * record stays, so size and existence travel next to the record instead of
 * being re-read during the bind.
 */
data class HistoryEntry(
    val record: CloneRecord,
    val fileSize: Long,
    val fileExists: Boolean,
)

/**
 * Rows for "Riwayat Clone" (DESAIN.md section 4, Layar 5).
 *
 * The row shows the clone's icon, label and "size • date time"; the success
 * badge only appears while the APK still exists. The overflow menu offers
 * share and delete, matching the two actions the spec allows here.
 */
class HistoryAdapter(
    private var entries: List<HistoryEntry>,
    private val onShare: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    private val dateFormat = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val record = entry.record
        val context = holder.itemView.context

        // A clone that is installed resolves under newPackage; one that was
        // never installed still falls back to the source app's icon so the
        // row does not go blank.
        val iconPx = (ICON_DP * holder.itemView.resources.displayMetrics.density).toInt()
        holder.binding.historyIcon.setImageDrawable(
            AppIcons.load(context, record.newPackage, iconPx)
                ?: AppIcons.load(context, record.originalPackage, iconPx),
        )
        holder.binding.historyIcon.contentDescription = record.newLabel
        holder.binding.historyName.text = record.newLabel
        holder.binding.historyMeta.text = if (entry.fileExists) {
            context.getString(
                R.string.history_meta_format,
                formatSize(entry.fileSize),
                dateFormat.format(Date(record.createdAt)),
            )
        } else {
            context.getString(R.string.history_missing_file)
        }
        holder.binding.historyStatus.visibility =
            if (entry.fileExists) View.VISIBLE else View.GONE

        holder.binding.root.setOnClickListener { onShare(entry) }
        holder.binding.historyMenu.setOnClickListener { anchor ->
            showMenu(anchor, entry)
        }
    }

    override fun getItemCount(): Int = entries.size

    fun update(newEntries: List<HistoryEntry>) {
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = entries.size
                override fun getNewListSize(): Int = newEntries.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    entries[oldPos].record.newPackage == newEntries[newPos].record.newPackage
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    entries[oldPos].contentKey == newEntries[newPos].contentKey
            },
        )
        entries = newEntries
        diff.dispatchUpdatesTo(this)
    }

    private fun showMenu(anchor: View, entry: HistoryEntry) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, MENU_SHARE, 0, R.string.history_share)
        popup.menu.add(0, MENU_DELETE, 1, R.string.history_delete)
        popup.menu.findItem(MENU_SHARE).isEnabled = entry.fileExists
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> onShare(entry)
                MENU_DELETE -> onDelete(entry)
            }
            true
        }
        popup.show()
    }

    /** One decimal place, Indonesian comma, e.g. "12,6 MB". */
    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale("id"), "%.1f MB", mb)
        } else {
            String.format(Locale("id"), "%.0f KB", bytes / 1024.0)
        }
    }

    private val HistoryEntry.contentKey: String
        get() = "${record.newLabel}|${record.fileName}|${record.createdAt}|" +
            "$fileExists|$fileSize"

    private companion object {
        /** Matches the 48dp historyIcon in item_history.xml. */
        const val ICON_DP = 48
        const val MENU_SHARE = 1
        const val MENU_DELETE = 2
    }
}
