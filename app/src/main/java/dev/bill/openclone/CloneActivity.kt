package dev.bill.openclone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.bill.openclone.databinding.ActivityCloneBinding
import kotlinx.coroutines.CancellationException
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
 *
 * Rotation while a clone is running cancels the job: the work is CPU and
 * disk bound and owned by this screen, so it is tied to lifecycleScope
 * instead of leaking into a detached scope. The temporary files are
 * removed by CloneEngine's finally block and the user can simply run it
 * again on the recreated screen. A finished result survives rotation via
 * onSaveInstanceState.
 */
class CloneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneBinding
    private lateinit var app: InstalledApp
    private var iconBytes: ByteArray? = null
    private var lastResult: CloneResult? = null
    private var isCloning = false
    private var installReceiver: BroadcastReceiver? = null
    private var existingPackages: Set<String> = emptySet()

    private val pickIcon =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        iconBytes = bytes
                        binding.iconStatus.text = "Ikon custom dipilih (${bytes.size / 1024} KB)"
                        binding.removeIconButton.visibility = View.VISIBLE
                    } else {
                        toast("Gagal membaca gambar: file kosong")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("Gagal membaca gambar: ${readableError(e)}")
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

        registerInstallReceiver()
        restoreResult(savedInstanceState)

        // Both the app lookup and the "package already used" set scan every
        // installed package. That is a binder round-trip per package and must
        // never run on the main thread, so it is one background pass here
        // instead of a blocking call inside fillForm().
        lifecycleScope.launch {
            val (found, installed) = withContext(Dispatchers.IO) {
                val repository = AppRepository(applicationContext)
                repository.findInstalledApp(packageName) to repository.installedPackageNames()
            }
            if (found == null) {
                toast("Aplikasi tidak ditemukan")
                finish()
                return@launch
            }
            app = found
            existingPackages = installed
            // Do not overwrite text the user already typed before a rotation.
            fillForm(applyDefaults = savedInstanceState == null)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastResult?.let { result ->
            outState.putString(STATE_NEW_LABEL, result.newLabel)
            outState.putString(STATE_NEW_PACKAGE, result.newPackage)
            outState.putString(STATE_FILE_NAME, result.outputFileName)
            outState.putLong(STATE_FILE_SIZE, result.outputFileSize)
            outState.putBoolean(STATE_HAD_SPLITS, result.hadSplits)
        }
    }

    override fun onDestroy() {
        installReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        installReceiver = null
        super.onDestroy()
    }

    private fun restoreResult(state: Bundle?) {
        if (state == null) return
        val newPackage = state.getString(STATE_NEW_PACKAGE) ?: return
        val fileName = state.getString(STATE_FILE_NAME) ?: return
        val result = CloneResult(
            originalPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
            newPackage = newPackage,
            newLabel = state.getString(STATE_NEW_LABEL).orEmpty(),
            outputFileName = fileName,
            outputFileSize = state.getLong(STATE_FILE_SIZE),
            hadSplits = state.getBoolean(STATE_HAD_SPLITS),
        )
        if (File(File(filesDir, "clones"), fileName).exists()) {
            showResult(result)
        }
    }

    private fun fillForm(applyDefaults: Boolean) {
        val iconPx = (56 * resources.displayMetrics.density).toInt()
        binding.appIcon.setImageDrawable(AppIcons.load(this, app.packageName, iconPx))
        binding.appName.text = app.label
        binding.appPackage.text = app.packageName
        val splitsNote = if (app.splitCount > 0) {
            "Aplikasi ini punya ${app.splitCount} split APK, akan digabung dulu jadi satu file."
        } else {
            "Bagian APK: 1 (base)"
        }
        binding.appMeta.text = "v${app.displayVersion}  •  $splitsNote"

        if (applyDefaults) {
            binding.cloneNameInput.setText(suggestCloneLabel(app.label))
            binding.clonePackageInput.setText(suggestClonePackage(app.packageName, existingPackages))
        }
    }

    private fun startClone() {
        if (!::app.isInitialized || isCloning) return
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
        if (AppRepository(this).isInstalled(newPackage)) {
            binding.clonePackageInput.error = "Package ini sudah terpasang, ganti suffix-nya"
            return
        }

        isCloning = true
        binding.cloneButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "Memulai..."
        binding.resultCard.visibility = View.GONE

        val appSnapshot = app
        val config = CloneConfig(
            originalPackage = appSnapshot.packageName,
            originalLabel = appSnapshot.label,
            newPackage = newPackage,
            newLabel = newLabel,
            iconBytes = iconBytes,
        )

        lifecycleScope.launch {
            try {
                // ARSCLib rewriting, zipping and signing are CPU and disk
                // bound, so Dispatchers.Default is the right pool; IO is for
                // blocking waits (network, file system) and is sized for it.
                val result = withContext(Dispatchers.Default) {
                    // The progress callback hops back to the main thread and
                    // suspends until the label is drawn. Because it runs inside
                    // this job, a status line can never land after the final
                    // "Selesai." / "Gagal:" write, and cancellation is
                    // observed at every checkpoint.
                    CloneEngine(applicationContext).clone(appSnapshot, config) { status ->
                        withContext(Dispatchers.Main.immediate) {
                            if (!isFinishing && !isDestroyed) {
                                binding.statusText.text = status
                            }
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    CloneStore(applicationContext).add(
                        CloneRecord(
                            originalPackage = result.originalPackage,
                            originalLabel = appSnapshot.label,
                            newPackage = result.newPackage,
                            newLabel = result.newLabel,
                            fileName = result.outputFileName,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }

                if (!isFinishing && !isDestroyed) showResult(result)
            } catch (e: CancellationException) {
                // The screen went away. CloneEngine's finally block has already
                // removed the temp files; this is not a failure to report.
                throw e
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) showFailure(e)
            } finally {
                isCloning = false
            }
        }
    }

    private fun showResult(result: CloneResult) {
        lastResult = result
        isCloning = false
        binding.cloneButton.isEnabled = true
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = "Selesai."
        binding.resultCard.visibility = View.VISIBLE
        val sizeKb = result.outputFileSize / 1024
        val splitsLine = if (result.hadSplits) "\nSplit APK digabung jadi satu file." else ""
        binding.resultText.text =
            "${result.newLabel} (${result.newPackage})\n" +
                "File: ${result.outputFileName} (${sizeKb} KB)" +
                splitsLine
    }

    private fun showFailure(error: Throwable) {
        isCloning = false
        binding.cloneButton.isEnabled = true
        binding.progressBar.visibility = View.GONE
        val message = readableError(error)
        binding.statusText.text = "Gagal: $message"
        toast("Clone gagal: $message")
    }

    private fun cloneFile(): File? {
        val result = lastResult ?: return null
        val file = File(File(filesDir, "clones"), result.outputFileName)
        return if (file.exists()) file else null
    }

    private fun installLastClone() {
        val result = lastResult ?: return
        val file = cloneFile()
        if (file == null) {
            toast("File clone tidak ditemukan, buat ulang dulu")
            return
        }
        // Copying the APK into a PackageInstaller session is a real file
        // transfer; on a large base APK it is tens of megabytes and would
        // block the main thread long enough to trip the ANR watchdog.
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { installWithSession(result.newPackage, file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Some OEM installer stacks refuse sessions created by a
                // normal app. Fall back to the system installer UI, which
                // reports its own failure reasons.
                installWithViewer(file)
            }
        }
    }

    /**
     * Real install with a readable failure reason: PackageInstaller reports
     * INSTALL_FAILED_* codes and a message through the session callback,
     * where ACTION_VIEW only gives the user a generic dialog.
     */
    private fun installWithSession(packageToInstall: String, file: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageToInstall)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val sourceUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val source = contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("Tidak bisa membaca file APK")
                source.use { input ->
                    val output = session.openWrite("openclone", 0, file.length())
                    try {
                        input.copyTo(output)
                        session.fsync(output)
                    } finally {
                        output.close()
                    }
                }
                val statusIntent = Intent(installStatusAction()).setPackage(packageName)
                var flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or android.app.PendingIntent.FLAG_MUTABLE
                }
                val pending = android.app.PendingIntent.getBroadcast(this, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun installWithViewer(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
            // No session callback on this path, so drop the cached package
            // list here; the next app-list refresh will pick up the clone.
            AppRepository.invalidate()
        } catch (e: Exception) {
            toast("Tidak ada aplikasi pemasang APK: ${readableError(e)}")
        }
    }

    private fun registerInstallReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE,
                )
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // The system needs one confirmation tap. On API 33+
                        // the typed overload is required, below it the plain
                        // one still works.
                        @Suppress("DEPRECATION")
                        val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null) startActivity(confirm)
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        // The app list is cached; drop it so the clone shows
                        // up as installed when this screen is closed.
                        AppRepository.invalidate()
                        toast("Clone berhasil dipasang")
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // user backed out
                    else -> toast(
                        "Pemasangan gagal: ${message.ifBlank { "kode $status" }}",
                    )
                }
            }
        }
        installReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(installStatusAction()),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun installStatusAction(): String = "$packageName.install.STATUS"

    private fun shareLastClone() {
        val file = cloneFile() ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan APK clone"))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Turns the most common installer / ARSCLib failures into something the
     * user can act on instead of a raw stack-trace message.
     */
    private fun readableError(error: Throwable): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return when {
            raw.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true) ->
                "Permission bentrok dengan aplikasi lain"
            raw.contains("INSTALL_FAILED_CONFLICTING_PROVIDER", ignoreCase = true) ->
                "Authority provider bentrok dengan aplikasi lain"
            raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ->
                "Package ini sudah ada dengan kunci berbeda, uninstall dulu"
            raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                "Versi lebih rendah dari yang terpasang"
            raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ||
                raw.contains("ENOSPC", ignoreCase = true) ->
                "Penyimpanan penuh"
            else -> raw
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val STATE_NEW_LABEL = "state_new_label"
        private const val STATE_NEW_PACKAGE = "state_new_package"
        private const val STATE_FILE_NAME = "state_file_name"
        private const val STATE_FILE_SIZE = "state_file_size"
        private const val STATE_HAD_SPLITS = "state_had_splits"
    }
}
