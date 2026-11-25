package dev.bill.openclone

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.bill.openclone.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar 1 "Beranda" (DESAIN.md section 4): searchable list of installed apps
 * with Semua/Sistem/Pengguna filters, a shortcut to the app picker grid, and
 * the shared bottom navigation.
 *
 * Tapping a row or its Clone pill opens [CloneConfigActivity] with the app's
 * package name; the config screen re-resolves the rest, so nothing else has
 * to be handed over here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppListAdapter

    private var allApps: List<InstalledApp> = emptyList()
    private var query: String = ""
    private var filter: AppFilter = AppFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppListAdapter(emptyList()) { app -> openConfig(app.packageName) }
        // The row height never changes, so RecyclerView can skip a layout pass
        // over the whole list on every update.
        binding.appList.setHasFixedSize(true)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            applyFilters()
        }

        binding.chipAll.setOnClickListener { selectFilter(AppFilter.ALL) }
        binding.chipSystem.setOnClickListener { selectFilter(AppFilter.SYSTEM) }
        binding.chipUser.setOnClickListener { selectFilter(AppFilter.USER) }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.pickerButton.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        binding.bottomNav.select(BottomNavView.Tab.APPS)
        renderChips()

        // No loadApps() here: onResume runs immediately after onCreate and
        // owns the refresh, so loading in both places started the same
        // package scan twice on every cold start.
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            // The repository serves a short-lived cached scan, so a quick trip
            // to the clone screen costs no package-manager traffic at all.
            val apps = withContext(Dispatchers.IO) {
                AppRepository(this@MainActivity).listInstalledApps()
            }
            // The activity can be torn down while the scan runs; writing to
            // the adapter afterwards would leak a dead view hierarchy.
            if (isFinishing || isDestroyed) return@launch
            allApps = apps
            applyFilters()
        }
    }

    private fun selectFilter(next: AppFilter) {
        if (filter == next) return
        filter = next
        renderChips()
        applyFilters()
    }

    private fun applyFilters() {
        val q = query.trim().lowercase()
        val filtered = allApps.asSequence()
            .filter { filter.matches(it) }
            .filter { q.isEmpty() || it.searchKey.contains(q) }
            .toList()

        binding.listHeader.text = getString(R.string.installed_apps_count, filtered.size)
        adapter.update(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * The chips are plain TextViews, so the selected look is swapped here
     * instead of through a selector: an active chip is a white pill with dark
     * text, an inactive one a dark pill with secondary text (both still keep
     * the 48dp touch layer supplied by the drawable).
     */
    private fun renderChips() {
        val chips = mapOf(
            AppFilter.ALL to binding.chipAll,
            AppFilter.SYSTEM to binding.chipSystem,
            AppFilter.USER to binding.chipUser,
        )
        chips.forEach { (candidate, chip) -> styleChip(chip, candidate == filter) }
    }

    private fun styleChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(
            if (active) R.drawable.bg_chip_active_touch else R.drawable.bg_chip_inactive_touch,
        )
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) R.color.text_on_white else R.color.text_secondary,
            ),
        )
        chip.isSelected = active
    }

    private fun openConfig(packageName: String) {
        startActivity(
            Intent(this, CloneConfigActivity::class.java)
                .putExtra(CloneConfigActivity.EXTRA_ORIGINAL_PACKAGE, packageName),
        )
    }

    /** Semua / Sistem / Pengguna, applied on top of the text query. */
    private enum class AppFilter {
        ALL,
        SYSTEM,
        USER;

        fun matches(app: InstalledApp): Boolean = when (this) {
            ALL -> true
            SYSTEM -> app.isSystemApp
            USER -> !app.isSystemApp
        }
    }
}
