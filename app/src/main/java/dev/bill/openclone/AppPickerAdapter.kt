package dev.bill.openclone

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.bill.openclone.databinding.ItemPickerTileBinding

/**
 * The 4-column app grid on "Pilih Aplikasi" (DESAIN.md section 4, Layar 6).
 *
 * Same lazy-icon rule as [AppListAdapter]: icons are loaded per visible tile
 * through [AppIcons] rather than decoded for every installed package up front.
 */
class AppPickerAdapter(
    private var apps: List<InstalledApp>,
    private val onClick: (InstalledApp) -> Unit,
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPickerTileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPickerTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val iconPx = (ICON_DP * holder.itemView.resources.displayMetrics.density).toInt()
        holder.binding.tileIcon.setImageDrawable(
            AppIcons.load(holder.itemView.context, app.packageName, iconPx),
        )
        holder.binding.tileIcon.contentDescription = app.label
        holder.binding.tileName.text = app.label
        holder.binding.root.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = apps.size

    fun update(newApps: List<InstalledApp>) {
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = apps.size
                override fun getNewListSize(): Int = newApps.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    apps[oldPos].identity == newApps[newPos].identity
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    apps[oldPos].contentKey == newApps[newPos].contentKey
            },
        )
        apps = newApps
        diff.dispatchUpdatesTo(this)
    }

    private val InstalledApp.identity: String get() = "$packageName@$sourceDir"
    private val InstalledApp.contentKey: String
        get() = "$packageName|$label|$versionName|$versionCode|" +
            "${splitSourceDirs.size}|$isSystemApp"

    private companion object {
        /** Matches the 44dp tileIcon in item_picker_tile.xml. */
        const val ICON_DP = 44
    }
}
