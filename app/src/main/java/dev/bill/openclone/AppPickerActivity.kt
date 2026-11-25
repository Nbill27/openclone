package dev.bill.openclone

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import dev.bill.openclone.databinding.ActivityAppPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar 6 "Pilih Aplikasi" (DESAIN.md section 4): the full installed-app grid,
 * four columns wide, opened from the Beranda header.
 *
 * The grid searches over the same cached [AppRepository] scan Beranda uses, so
 * opening it costs no extra package-manager traffic.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var adapter: AppPickerAdapter

    private var allApps: List<InstalledApp> = emptyList()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppPickerAdapter(emptyList()) { app -> openConfig(app.packageName) }
        // Tiles are fixed-height, so the grid can skip a full layout pass when
        // the filter result changes.
        binding.pickerGrid.setHasFixedSize(true)
        binding.pickerGrid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.pickerGrid.adapter = adapter

        binding.searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            applyFilter()
        }

        binding.backButton.setOnClickListener { finish() }

        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppRepository(this@AppPickerActivity).listInstalledApps()
            }
            if (isFinishing || isDestroyed) return@launch
            allApps = apps
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.searchKey.contains(q) }
        }
        adapter.update(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openConfig(packageName: String) {
        startActivity(
            Intent(this, CloneConfigActivity::class.java)
                .putExtra(CloneConfigActivity.EXTRA_ORIGINAL_PACKAGE, packageName),
        )
    }

    private companion object {
        /** Four tiles per row, as specified for the picker grid. */
        const val GRID_COLUMNS = 4
    }
}
