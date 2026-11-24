package dev.bill.openclone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.bill.openclone.databinding.ActivityCloneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen 2, "Atur Clone", and screen 3, the result.
 *
 * Flow, kept to three taps on purpose:
 * 1. The fields are pre-filled: "<App> 2" and "<package>.klon1".
 * 2. Optionally pick a custom icon from the gallery.
 * 3. Tap CLONE, wait for the progress lines, tap INSTALL.
 *
 * The finished APK is saved in private storage under files/clones/,
 * and remembered in CloneStore so it can be re-installed later.
 * The signing key is the same for every clone (KeystoreManager),
 * so a clone can be updated in place. Back up the keystore file
 * (files/openclone-keystore.p12) if you ever reinstall OpenClone.
 */
class CloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneBinding
    private lateinit var app: InstalledApp
    private var iconBytes: ByteArray? = null
    private var lastResult: CloneResult? = null

    private val pickIcon = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    iconBytes = bytes
                    withContext(Dispatchers.Main) {
                        binding.iconStatus.text = "Ikon custom dipilih (${bytes.size / 1024} KB)"
                        binding.removeIconButton.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CloneActivity, "Gagal membaca gambar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val found = AppRepository(packageManager).listInstalledApps()
                .firstOrNull { it.packageName == packageName }
            withContext(Dispatchers.Main) {
                if (found == null) {
                    Toast.makeText(this@CloneActivity, "Aplikasi tidak ditemukan", Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                app = found
                fillForm()
            }
        }

        binding.pickIconButton.setOnClickListener { pickIcon.launch("image/*") }
        binding.removeIconButton.setOnClickListener {
            iconBytes = null
            binding.iconStatus.text = "Kosongkan bila ingin ikon asli tetap dipakai."
            binding.removeIconButton.visibility = View.GONE
        }
        binding.cloneButton.setOnClickListener { startClone() }
        binding.installButton.setOnClickListener { installLastClone() }
        binding.shareButton.setOnClickListener { shareLastClone() }
    }

    private fun fillForm() {
        binding.appIcon.setImageDrawable(app.icon)
        binding.appName.text = app.label
        binding.appPackage.text = app.packageName
        val splitsNote = if (app.splitCount > 0) {
            "Aplikasi ini punya ${app.splitCount} split APK, akan digabung dulu jadi satu file."
        } else {
            "Bagian APK: 1 (base)"
        }
        binding.appMeta.text = "v${app.displayVersion}  •  $splitsNote"

        val existing = AppRepository(packageManager).installedPackageNames()
        binding.cloneNameInput.setText(suggestCloneLabel(app.label))
        binding.clonePackageInput.setText(suggestClonePackage(app.packageName, existing))
    }

    private fun startClone() {
        if (!::app.isInitialized) return
        val newLabel = binding.cloneNameInput.text.toString().trim()
        val newPackage = binding.clonePackageInput.text.toString().trim()
        if (newLabel.isEmpty()) {
            binding.cloneNameInput.error = "Nama clone tidak boleh kosong"
            return
        }
        if (!isValidPackageName(newPackage)) {
            binding.clonePackageInput.error = "Package name tidak valid, contoh: ${app.packageName}.klon1"
            return
        }
        if (newPackage == app.packageName) {
            binding.clonePackageInput.error = "Package clone harus beda dari aslinya"
            return
        }
        if (AppRepository(packageManager).isInstalled(newPackage)) {
            binding.clonePackageInput.error = "Package ini sudah terpasang, ganti suffix-nya"
            return
        }
        binding.cloneButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "Memulai..."
        binding.resultCard.visibility = View.GONE

        val config = CloneConfig(
            originalPackage = app.packageName,
            originalLabel = app.label,
            newPackage = newPackage,
            newLabel = newLabel,
            iconBytes = iconBytes,
        )
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = CloneEngine(this@CloneActivity).clone(app, config) { status ->
                    launch(Dispatchers.Main) { binding.statusText.text = status }
                }
                CloneStore(this@CloneActivity).add(
                    CloneRecord(
                        originalPackage = result.originalPackage,
                        originalLabel = app.label,
                        newPackage = result.newPackage,
                        newLabel = result.newLabel,
                        fileName = result.outputFileName,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                withContext(Dispatchers.Main) { showResult(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.cloneButton.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.text = "Gagal: ${e.message}"
                    Toast.makeText(
                        this@CloneActivity,
                        "Clone gagal: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showResult(result: CloneResult) {
        lastResult = result
        binding.cloneButton.isEnabled = true
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = "Selesai."
        binding.resultCard.visibility = View.VISIBLE
        val sizeKb = result.outputFileSize / 1024
        binding.resultText.text =
            "${result.newLabel} (${result.newPackage})\n" +
                "File: ${result.outputFileName} (${sizeKb} KB)" +
                if (result.hadSplits) "\nSplit APK digabung jadi satu file." else ""
    }

    private fun cloneFile(): File? {
        val result = lastResult ?: return null
        val file = File(File(filesDir, "clones"), result.outputFileName)
        return if (file.exists()) file else null
    }

    private fun installLastClone() {
        val file = cloneFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun shareLastClone() {
        val file = cloneFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan APK clone"))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
