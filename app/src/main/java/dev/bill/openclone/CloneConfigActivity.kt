package dev.bill.openclone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.bill.openclone.databinding.ActivityCloneConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layar 2 "Buat Salinan" (DESAIN.md section 4, Varian B).
 *
 * The activity only receives [EXTRA_ORIGINAL_PACKAGE]. Everything else (label,
 * version, splits, the set of installed packages) is resolved once on
 * [Dispatchers.IO], so the caller never has to hand over a half-built model
 * that could go stale between screens.
 *
 * The picked icon is copied into the cache directory and only its path is
 * carried through the flow: a [ByteArray] in an Intent is capped by the
 * transaction size, and a file path survives process death.
 */
class CloneConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneConfigBinding

    private var sourceApp: InstalledApp? = null
    private var installedPackages: Set<String> = emptySet()
    private var iconPath: String? = null
    private var advancedExpanded: Boolean = false
    private var stripAdId: Boolean = false

    private val pickIcon =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importIcon(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        iconPath = savedInstanceState?.getString(STATE_ICON_PATH)
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_EXPANDED) ?: false
        stripAdId = savedInstanceState?.getBoolean(STATE_STRIP_AD_ID) ?: false

        binding.backButton.setOnClickListener { finish() }
        binding.clearNameButton.setOnClickListener { binding.nameInput.setText("") }
        binding.resetPackageButton.setOnClickListener { resetPackage() }
        binding.changeIconButton.setOnClickListener { pickIcon.launch(ICON_MIME) }
        binding.advancedHeader.setOnClickListener { setAdvancedExpanded(!advancedExpanded) }
        binding.stripAdIdSwitch.isChecked = stripAdId
        binding.stripAdIdSwitch.setOnCheckedChangeListener { _, checked -> stripAdId = checked }
        binding.createButton.setOnClickListener { startClone() }

        renderAdvancedState()
        iconPath?.let { showIconPreview(it) }

        loadSource(savedInstanceState == null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ICON_PATH, iconPath)
        outState.putBoolean(STATE_ADVANCED_EXPANDED, advancedExpanded)
        outState.putBoolean(STATE_STRIP_AD_ID, stripAdId)
    }

    /**
     * One IO pass resolves both the source app and the package set used by
     * [suggestClonePackage]. A missing package is fatal for this screen: the
     * user picked a row that has since been uninstalled.
     */
    private fun loadSource(applyDefaults: Boolean) {
        val packageName = intent.getStringExtra(EXTRA_ORIGINAL_PACKAGE)
        if (packageName.isNullOrBlank()) {
            failAndClose()
            return
        }
        lifecycleScope.launch {
            val (app, packages) = withContext(Dispatchers.IO) {
                val repository = AppRepository(this@CloneConfigActivity)
                repository.findInstalledApp(packageName) to repository.installedPackageNames()
            }
            if (isFinishing || isDestroyed) return@launch
            if (app == null) {
                failAndClose()
                return@launch
            }
            sourceApp = app
            installedPackages = packages
            renderSource(app)
            if (applyDefaults) {
                binding.nameInput.setText(suggestCloneLabel(app.label))
                binding.packageInput.setText(suggestClonePackage(app.packageName, packages))
            }
        }
    }

    private fun renderSource(app: InstalledApp) {
        binding.sourceName.text = app.label
        binding.sourcePackage.text = app.packageName
        binding.advancedVersionValue.text = app.displayVersion
        binding.advancedSplitsValue.text = if (app.splitCount == 0) {
            getString(R.string.advanced_splits_none)
        } else {
            getString(R.string.advanced_splits_count, app.splitCount)
        }

        val icon = AppIcons.load(this, app.packageName, dp(48))
        binding.sourceIcon.setImageDrawable(icon)
        if (iconPath == null) binding.iconPreview.setImageDrawable(icon)
    }

    private fun resetPackage() {
        val app = sourceApp ?: return
        binding.packageInput.setText(suggestClonePackage(app.packageName, installedPackages))
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedExpanded = expanded
        renderAdvancedState()
    }

    private fun renderAdvancedState() {
        binding.advancedContent.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        binding.advancedChevron.animate()
            .rotation(if (advancedExpanded) 180f else 0f)
            .setDuration(200L)
            .start()
    }

    private fun startClone() {
        val error = validate()
        if (error != null) {
            toast(error)
            return
        }
        val app = sourceApp ?: return
        startActivity(
            Intent(this, CloneProgressActivity::class.java)
                .putExtra(CloneProgressActivity.EXTRA_ORIGINAL_PACKAGE, app.packageName)
                .putExtra(CloneProgressActivity.EXTRA_ORIGINAL_LABEL, app.label)
                .putExtra(CloneProgressActivity.EXTRA_NEW_PACKAGE, currentPackage())
                .putExtra(CloneProgressActivity.EXTRA_NEW_LABEL, currentLabel())
                .putExtra(CloneProgressActivity.EXTRA_ICON_PATH, iconPath)
                .putExtra(CloneProgressActivity.EXTRA_STRIP_AD_ID, stripAdId),
        )
    }

    /** Returns the first user-facing problem, or null when the form is valid. */
    private fun validate(): String? {
        val app = sourceApp ?: return getString(R.string.error_app_not_found)
        if (currentLabel().isEmpty()) return getString(R.string.error_label_empty)

        val pkg = currentPackage()
        if (!isValidPackageName(pkg)) return getString(R.string.error_package_invalid)
        if (pkg == app.packageName) return getString(R.string.error_package_same)
        if (pkg in installedPackages) return getString(R.string.error_package_installed)
        return null
    }

    private fun currentLabel(): String = binding.nameInput.text.toString().trim()

    private fun currentPackage(): String = binding.packageInput.text.toString().trim()

    private fun importIcon(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    toast(getString(R.string.error_icon_empty))
                    return@launch
                }
                val path = withContext(Dispatchers.IO) {
                    File(cacheDir, "picked_icon_${System.currentTimeMillis()}.img")
                        .apply { writeBytes(bytes) }
                        .absolutePath
                }
                if (isFinishing || isDestroyed) return@launch
                replaceIconFile(path)
                showIconPreview(path)
            } catch (e: Exception) {
                toast(getString(R.string.error_icon_read, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun replaceIconFile(newPath: String) {
        val previous = iconPath
        iconPath = newPath
        if (previous != null && previous != newPath) File(previous).delete()
    }

    private fun showIconPreview(path: String) {
        val bitmap = decodeSampled(path, dp(48).coerceAtLeast(48))
        if (bitmap != null) {
            binding.iconPreview.setImageBitmap(bitmap)
        } else {
            sourceApp?.let {
                binding.iconPreview.setImageDrawable(AppIcons.load(this, it.packageName, dp(48)))
            }
        }
    }

    /** Decodes [path] at roughly [targetPx] so a 12 MP gallery photo cannot OOM the preview. */
    private fun decodeSampled(path: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun failAndClose() {
        toast(getString(R.string.error_app_not_found))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_ORIGINAL_PACKAGE = "original_package"

        private const val STATE_ICON_PATH = "state_icon_path"
        private const val STATE_ADVANCED_EXPANDED = "state_advanced_expanded"
        private const val STATE_STRIP_AD_ID = "state_strip_ad_id"
        private const val ICON_MIME = "image/*"
    }
}
