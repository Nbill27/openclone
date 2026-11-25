package dev.bill.openclone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.bill.openclone.databinding.ActivityCloneProgressBinding
import dev.bill.openclone.databinding.ActivityCloneResultBinding
import dev.bill.openclone.databinding.ItemStepBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Layar 3 "Proses Cloning" and Layar 4 "Selesai" (DESAIN.md section 4).
 *
 * Both screens live in one activity on purpose: the result is the natural
 * continuation of the job, so swapping the content view keeps the back stack
 * free of a progress screen the user can never return to.
 *
 * [CloneEngine.clone] is CPU and disk bound, so it runs on
 * [Dispatchers.Default]; progress lines hop back to the main thread to update
 * the ring and the five-step checklist.
 */
class CloneProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneProgressBinding
    private var resultBinding: ActivityCloneResultBinding? = null

    private val stepRows = mutableListOf<ItemStepBinding>()
    private var result: CloneResult? = null
    private var isCloning = false
    private var isInstalling = false
    private var installReceiver: BroadcastReceiver? = null

    private val resultBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, resultBackCallback)

        binding.backButton.setOnClickListener { finish() }
        buildStepRows()
        registerInstallReceiver()

        val restored = savedInstanceState
        if (restored != null && restored.getBoolean(STATE_DONE)) {
            showResult(
                CloneResult(
                    originalPackage = restored.getString(STATE_ORIGINAL_PACKAGE).orEmpty(),
                    newPackage = restored.getString(STATE_NEW_PACKAGE).orEmpty(),
                    newLabel = restored.getString(STATE_NEW_LABEL).orEmpty(),
                    outputFileName = restored.getString(STATE_FILE_NAME).orEmpty(),
                    outputFileSize = restored.getLong(STATE_FILE_SIZE),
                    hadSplits = restored.getBoolean(STATE_HAD_SPLITS),
                    compatNotes = restored.getStringArrayList(STATE_COMPAT_NOTES).orEmpty(),
                    identityNotes = restored.getStringArrayList(STATE_IDENTITY_NOTES).orEmpty(),
                ),
            )
            return
        }

        startClone()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val current = result ?: return
        outState.putBoolean(STATE_DONE, true)
        outState.putString(STATE_ORIGINAL_PACKAGE, current.originalPackage)
        outState.putString(STATE_NEW_PACKAGE, current.newPackage)
        outState.putString(STATE_NEW_LABEL, current.newLabel)
        outState.putString(STATE_FILE_NAME, current.outputFileName)
        outState.putLong(STATE_FILE_SIZE, current.outputFileSize)
        outState.putBoolean(STATE_HAD_SPLITS, current.hadSplits)
        outState.putStringArrayList(STATE_COMPAT_NOTES, ArrayList(current.compatNotes))
        outState.putStringArrayList(STATE_IDENTITY_NOTES, ArrayList(current.identityNotes))
    }

    override fun onDestroy() {
        installReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        installReceiver = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Layar 3

    private fun buildStepRows() {
        val labels = listOf(
            R.string.step_prepare,
            R.string.step_copy,
            R.string.step_modify,
            R.string.step_sign,
            R.string.step_finalize,
        )
        labels.forEach { label ->
            val row = ItemStepBinding.inflate(layoutInflater, binding.stepList, false)
            row.stepLabel.setText(label)
            binding.stepList.addView(row.root)
            stepRows += row
        }
        renderSteps(doneThrough = -1, active = STEP_PREPARE)
        binding.progressRing.progress = 5
    }

    private fun startClone() {
        if (isCloning) return
        val originalPackage = intent.getStringExtra(EXTRA_ORIGINAL_PACKAGE)
        val newPackage = intent.getStringExtra(EXTRA_NEW_PACKAGE)
        val newLabel = intent.getStringExtra(EXTRA_NEW_LABEL)
        if (originalPackage.isNullOrBlank() || newPackage.isNullOrBlank() || newLabel.isNullOrBlank()) {
            toast(getString(R.string.error_app_not_found))
            finish()
            return
        }

        isCloning = true
        val originalLabel = intent.getStringExtra(EXTRA_ORIGINAL_LABEL).orEmpty()
        val iconPath = intent.getStringExtra(EXTRA_ICON_PATH)
        val stripAdId = intent.getBooleanExtra(EXTRA_STRIP_AD_ID, false)

        lifecycleScope.launch {
            try {
                val (app, iconBytes) = withContext(Dispatchers.IO) {
                    val installed = AppRepository(this@CloneProgressActivity)
                        .findInstalledApp(originalPackage)
                    val bytes = iconPath
                        ?.let { File(it) }
                        ?.takeIf { it.exists() }
                        ?.let { runCatching { it.readBytes() }.getOrNull() }
                    installed to bytes
                }
                if (app == null) {
                    fail(getString(R.string.error_app_not_found))
                    return@launch
                }

                val config = CloneConfig(
                    originalPackage = originalPackage,
                    originalLabel = originalLabel.ifBlank { app.label },
                    newPackage = newPackage,
                    newLabel = newLabel,
                    iconBytes = iconBytes,
                    stripAdId = stripAdId,
                )
                // The engine copies, rewrites, re-zips and signs the APK: keep
                // that off the main thread or the watchdog kills the activity
                // with "OpenClone tidak ada tanggapan" right as it finishes.
                val cloned = withContext(Dispatchers.Default) {
                    CloneEngine(this@CloneProgressActivity).clone(app, config) { line ->
                        onProgress(line)
                    }
                }
                if (isFinishing || isDestroyed) return@launch

                // CloneStore.add rewrites clones.json on disk (temp file plus
                // rename), so it must not run on the main thread either.
                withContext(Dispatchers.IO) {
                    CloneStore(this@CloneProgressActivity).add(
                        CloneRecord(
                            originalPackage = cloned.originalPackage,
                            originalLabel = config.originalLabel,
                            newPackage = cloned.newPackage,
                            newLabel = cloned.newLabel,
                            fileName = cloned.outputFileName,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                onFinished()
                showResult(cloned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                fail(readableError(e))
            } finally {
                isCloning = false
            }
        }
    }

    private suspend fun onProgress(line: String) {
        val update = PROGRESS_STEPS.firstOrNull { line.startsWith(it.prefix) }
            ?: ProgressUpdate(line, STEP_PREPARE, 5)
        withContext(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@withContext
            renderSteps(doneThrough = update.step - 1, active = update.step)
            binding.progressRing.progress = update.percent
            binding.progressSubtitle.text = line.trimEnd('.')
        }
    }

    private suspend fun onFinished() {
        withContext(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@withContext
            renderSteps(doneThrough = STEP_FINALIZE, active = -1)
            binding.progressRing.progress = 100
            binding.progressSubtitle.text = getString(R.string.success_subtitle)
        }
        // Let the ring reach 100% before the content view swaps.
        delay(350L)
    }

    private fun renderSteps(doneThrough: Int, active: Int) {
        stepRows.forEachIndexed { index, row ->
            val done = index <= doneThrough
            val isActive = index == active
            row.stepIndicator.setBackgroundResource(
                when {
                    done -> R.drawable.bg_step_done
                    isActive -> R.drawable.bg_step_active
                    else -> R.drawable.bg_step_pending
                },
            )
            row.stepCheck.visibility = if (done) View.VISIBLE else View.GONE
            row.stepLabel.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (done || isActive) R.color.accent_white else R.color.text_muted,
                ),
            )
        }
    }

    private fun fail(message: String) {
        toast(getString(R.string.clone_failed, message))
        finish()
    }

    // ---------------------------------------------------------------- Layar 4

    private fun showResult(cloned: CloneResult) {
        if (result != null) return
        result = cloned
        resultBackCallback.isEnabled = true
        isCloning = false

        val view = ActivityCloneResultBinding.inflate(layoutInflater)
        resultBinding = view
        setContentView(view.root)

        view.backButton.setOnClickListener { goHome() }
        view.resultName.text = cloned.newLabel
        view.resultPackage.text = cloned.newPackage
        view.resultSize.text = formatSize(cloned.outputFileSize)
        renderNoteList(view.compatList, view.compatCard, cloned.compatNotes)
        renderNoteList(view.identityList, view.identityCard, cloned.identityNotes)
        // Loading the original icon hits PackageManager and may decode a large
        // adaptive icon, so it runs off the main thread and fills in later.
        lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) {
                AppIcons.load(this@CloneProgressActivity, cloned.originalPackage, dp(48))
            }
            if (isFinishing || isDestroyed) return@launch
            icon?.let(view.resultIcon::setImageDrawable)
        }

        view.installButton.setOnClickListener { installClone() }
        view.shareButton.setOnClickListener { shareClone() }
        view.viewFileButton.setOnClickListener { viewCloneFile() }
        view.createAnotherButton.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
            finish()
        }
    }

    /**
     * Compatibility findings and identity facts come from the engine as plain
     * sentences, so the rows are built at runtime. Each card starts hidden in
     * XML and only appears when it actually has something to report.
     */
    private fun renderNoteList(container: LinearLayout, card: View, notes: List<String>) {
        container.removeAllViews()
        if (notes.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        notes.forEachIndexed { index, note ->
            val row = TextView(this)
            row.setTextAppearance(R.style.Text_OpenClone_Caption)
            row.text = getString(R.string.note_bullet, note)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (index > 0) params.topMargin = dp(6)
            row.layoutParams = params
            container.addView(row)
        }
        card.visibility = View.VISIBLE
    }

    private fun cloneFile(): File? {
        val current = result ?: return null
        val file = File(File(filesDir, "clones"), current.outputFileName)
        return file.takeIf { it.exists() }
    }

    private fun installClone() {
        if (isInstalling) return
        val current = result ?: return
        val file = cloneFile()
        if (file == null) {
            toast(getString(R.string.error_clone_file_missing))
            return
        }
        isInstalling = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { installWithSession(current.newPackage, file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Some OEM installer stacks refuse sessions created by a
                // normal app. Fall back to the system installer UI.
                installWithViewer(file)
            } finally {
                isInstalling = false
            }
        }
    }

    private fun installWithSession(packageToInstall: String, file: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageToInstall)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val sourceUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val source = contentResolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException(getString(R.string.install_read_error))
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
            AppRepository.invalidate()
        } catch (e: Exception) {
            toast(getString(R.string.install_viewer_missing, readableError(e)))
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
                        @Suppress("DEPRECATION")
                        val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null) startActivity(confirm)
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        AppRepository.invalidate()
                        toast(getString(R.string.install_success))
                    }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                    else -> toast(
                        getString(
                            R.string.install_failed,
                            message.ifBlank { "kode $status" },
                        ),
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

    private fun shareClone() {
        val file = cloneFile()
        if (file == null) {
            toast(getString(R.string.error_clone_file_missing))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(
                Intent.createChooser(intent, getString(R.string.share_chooser_title)),
            )
        } catch (e: Exception) {
            toast(getString(R.string.no_share_target))
        }
    }

    private fun viewCloneFile() {
        val file = cloneFile()
        if (file == null) {
            toast(getString(R.string.error_clone_file_missing))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.no_file_viewer))
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    // ------------------------------------------------------------------ utils

    /** One decimal place, Indonesian comma, e.g. "12,6 MB". */
    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale("id"), "%.1f MB", mb)
        } else {
            String.format(Locale("id"), "%.0f KB", bytes / 1024.0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                getString(R.string.install_error_permission_conflict)
            raw.contains("INSTALL_FAILED_CONFLICTING_PROVIDER", ignoreCase = true) ->
                getString(R.string.install_error_provider_conflict)
            raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ->
                getString(R.string.install_error_signature)
            raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                getString(R.string.install_error_downgrade)
            raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ||
                raw.contains("ENOSPC", ignoreCase = true) ->
                getString(R.string.install_error_storage)
            raw.contains(KeystoreManager.ERROR_KEY_UNREADABLE, ignoreCase = true) ->
                getString(R.string.keystore_error_unreadable)
            else -> raw
        }
    }

    private data class ProgressUpdate(val prefix: String, val step: Int, val percent: Int)

    companion object {
        const val EXTRA_ORIGINAL_PACKAGE = "original_package"
        const val EXTRA_ORIGINAL_LABEL = "original_label"
        const val EXTRA_NEW_PACKAGE = "new_package"
        const val EXTRA_NEW_LABEL = "new_label"
        const val EXTRA_ICON_PATH = "icon_path"
        const val EXTRA_STRIP_AD_ID = "strip_ad_id"

        private const val STEP_PREPARE = 0
        private const val STEP_FINALIZE = 4
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private const val STATE_DONE = "state_done"
        private const val STATE_ORIGINAL_PACKAGE = "state_original_package"
        private const val STATE_NEW_PACKAGE = "state_new_package"
        private const val STATE_NEW_LABEL = "state_new_label"
        private const val STATE_FILE_NAME = "state_file_name"
        private const val STATE_FILE_SIZE = "state_file_size"
        private const val STATE_HAD_SPLITS = "state_had_splits"
        private const val STATE_COMPAT_NOTES = "state_compat_notes"
        private const val STATE_IDENTITY_NOTES = "state_identity_notes"

        /**
         * Progress lines emitted by [CloneEngine.clone], in the order they
         * arrive. Matched by prefix so the trailing "..." never matters.
         */
        private val PROGRESS_STEPS = listOf(
            ProgressUpdate("Menyalin APK asli", 1, 12),
            ProgressUpdate("Membaca dan menggabungkan APK", 1, 26),
            ProgressUpdate("Mengganti package name", 2, 45),
            ProgressUpdate("Mengganti nama aplikasi", 2, 58),
            ProgressUpdate("Mengganti ikon", 2, 66),
            ProgressUpdate("Menulis APK baru", 3, 78),
            ProgressUpdate("Menandatangani APK", 3, 90),
        )
    }
}
