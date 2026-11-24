package dev.bill.openclone

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.bill.openclone.databinding.ItemAppBinding

class AppListAdapter(
    private var apps: List<InstalledApp>,
    private val onClick: (InstalledApp) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.binding.appIcon.setImageDrawable(app.icon)
        holder.binding.appName.text = app.label
        holder.binding.appPackage.text = app.packageName
        val meta = StringBuilder()
        meta.append("v${app.displayVersion}")
        if (app.splitCount > 0) meta.append("  •  ${app.splitCount + 1} bagian APK")
        if (app.isSystemApp) meta.append("  •  sistem")
        holder.binding.appMeta.text = meta.toString()
        holder.binding.root.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = apps.size

    fun update(newApps: List<InstalledApp>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
