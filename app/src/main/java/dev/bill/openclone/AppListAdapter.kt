package dev.bill.openclone

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.bill.openclone.databinding.ItemAppCardBinding

/**
 * Rows for the "Aplikasi Terpasang" list on Beranda (DESAIN.md section 4,
 * Layar 1). Each card is the app icon, its name, the package name and the
 * white "Clone" pill; both the card and the pill open the config screen.
 */
class AppListAdapter(
    private var apps: List<InstalledApp>,
    private val onClick: (InstalledApp) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemAppCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        // Icons are fetched per visible row and cached, instead of decoding
        // every installed app's icon before the list can be shown.
        val iconPx = (ICON_DP * holder.itemView.resources.displayMetrics.density).toInt()
        holder.binding.appIcon.setImageDrawable(
            AppIcons.load(holder.itemView.context, app.packageName, iconPx),
        )
        holder.binding.appIcon.contentDescription = app.label
        holder.binding.appName.text = app.label
        holder.binding.appPackage.text = app.packageName
        holder.binding.root.setOnClickListener { onClick(app) }
        holder.binding.cloneAction.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = apps.size

    /**
     * DiffUtil instead of notifyDataSetChanged: the list is re-read on every
     * onResume and after each keystroke, and a blanket refresh cancels item
     * animations and rebinds rows that did not change.
     */
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
        /** Matches the 48dp appIcon in item_app_card.xml. */
        const val ICON_DP = 48
    }
}
