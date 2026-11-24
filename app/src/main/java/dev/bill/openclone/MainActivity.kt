package dev.bill.openclone

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.bill.openclone.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 1, "Pilih Aplikasi": searchable list of installed apps.
 * Tap one to go to the "Atur Clone" screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppListAdapter
    private var allApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = AppListAdapter(emptyList()) { app ->
            val intent = Intent(this, CloneActivity::class.java).apply {
                putExtra(CloneActivity.EXTRA_PACKAGE, app.packageName)
            }
            startActivity(intent)
        }
        // The row height never changes, so RecyclerView can skip a layout pass
        // over the whole list on every update.
        binding.appList.setHasFixedSize(true)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                filter(newText.orEmpty())
                return true
            }
        })

        binding.aboutButton.setOnClickListener {
            Toast.makeText(
                this,
                "OpenClone v0.1.0, open source MIT. " +
                    "Clone hanya untuk aplikasi milikmu sendiri, " +
                    "bukan untuk Play Store.",
                Toast.LENGTH_LONG,
            ).show()
        }

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
            filter(binding.searchView.query?.toString().orEmpty())
        }
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            // Already sorted and already carrying cached lowercase keys.
            allApps
        } else {
            allApps.filter { it.searchKey.contains(q) }
        }
        adapter.update(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
}
