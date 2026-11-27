package dev.bill.openclone

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.bill.openclone.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layar 5 "Riwayat Clone" (DESAIN.md section 4): every finished clone, with
 * search, Semua/Berhasil/Gagal filters and the shared bottom navigation.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var apkInstaller: ApkInstaller

    private var allEntries: List<HistoryEntry> = emptyList()
    private var query: String = ""
    private var filter: HistoryFilter = HistoryFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkInstaller = ApkInstaller(this) { _, status ->
            if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                loadHistory()
            }
        }

        adapter = HistoryAdapter(
            entries = emptyList(),
            onInstall = ::install,
            onShare = ::share,
            onDelete = ::delete,
        )
        binding.historyList.setHasFixedSize(true)
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter

        binding.searchInput.doAfterTextChanged { text ->
            query = text?.toString().orEmpty()
            applyFilters()
        }

        binding.chipAll.setOnClickListener { selectFilter(HistoryFilter.ALL) }
        binding.chipSuccess.setOnClickListener { selectFilter(HistoryFilter.SUCCESS) }
        binding.chipFailed.setOnClickListener { selectFilter(HistoryFilter.FAILED) }

        binding.bottomNav.select(BottomNavView.Tab.HISTORY)
        renderChips()
    }

    override fun onResume() {
        super.onResume()
        apkInstaller.onResume()
        loadHistory()
    }

    override fun onPause() {
        apkInstaller.onPause()
        super.onPause()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val clonesDir = File(filesDir, "clones")
                CloneStore(this@HistoryActivity).list().map { record ->
                    val file = File(clonesDir, record.fileName)
                    val exists = file.exists()
                    HistoryEntry(
                        record = record,
                        fileSize = if (exists) file.length() else 0L,
                        fileExists = exists,
                    )
                }
            }
            if (isFinishing || isDestroyed) return@launch
            allEntries = entries
            applyFilters()
        }
    }

    private fun selectFilter(next: HistoryFilter) {
        if (filter == next) return
        filter = next
        renderChips()
        applyFilters()
    }

    private fun applyFilters() {
        val q = query.trim().lowercase()
        val filtered = allEntries.asSequence()
            .filter { filter.matches(it) }
            .filter { q.isEmpty() || it.searchKey.contains(q) }
            .toList()

        adapter.update(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderChips() {
        val chips = mapOf(
            HistoryFilter.ALL to binding.chipAll,
            HistoryFilter.SUCCESS to binding.chipSuccess,
            HistoryFilter.FAILED to binding.chipFailed,
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

    private fun install(entry: HistoryEntry) {
        val file = File(File(filesDir, "clones"), entry.record.fileName)
        if (!file.exists()) {
            toast(getString(R.string.history_missing_file))
            return
        }
        apkInstaller.install(entry.record.newPackage, file, entry.record.newLabel)
    }

    private fun share(entry: HistoryEntry) {
        val file = File(File(filesDir, "clones"), entry.record.fileName)
        if (!file.exists()) {
            toast(getString(R.string.history_missing_file))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ApkInstaller.APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_apk)))
        } catch (_: Exception) {
            toast(getString(R.string.no_share_target))
        }
    }

    private fun delete(entry: HistoryEntry) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                CloneStore(this@HistoryActivity).remove(entry.record.newPackage)
                val clonesDir = File(filesDir, "clones")
                val file = File(clonesDir, entry.record.fileName)
                if (file.exists()) file.delete()
                val iconFile = AppIcons.clonePreviewFile(this@HistoryActivity, entry.record.newPackage)
                if (iconFile.exists()) iconFile.delete()
                AppIcons.evict(entry.record.newPackage)
            }
            if (isFinishing || isDestroyed) return@launch
            toast(getString(R.string.history_deleted))
            loadHistory()
        }
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private val HistoryEntry.searchKey: String
        get() = "${record.newLabel} ${record.originalPackage} ${record.newPackage}".lowercase()

    private enum class HistoryFilter(val success: Boolean?) {
        ALL(null),
        SUCCESS(true),
        FAILED(false);

        fun matches(entry: HistoryEntry): Boolean =
            success == null || entry.fileExists == success
    }
}
