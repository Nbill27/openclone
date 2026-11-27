package dev.bill.openclone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import dev.bill.openclone.databinding.ActivityCloneConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Layar 2 "Buat Salinan": supports single or multi-clone batching (`1..20`),
 * automatic distinct icon recoloring per clone, color presets, optional clone
 * number badges, and custom gallery icons.
 */
class CloneConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneConfigBinding

    private var sourceApp: InstalledApp? = null
    private var customGalleryBitmap: Bitmap? = null
    private var installedOnDevice: Set<String> = emptySet()
    private var installedPackages: Set<String> = emptySet()

    private var cloneCount: Int = 1
    private var iconPath: String? = null
    private var advancedExpanded: Boolean = false
    private var stripAdId: Boolean = false

    private var updatingInputs = false
    private val quickCountChips = LinkedHashMap<Int, TextView>()

    private val pickIcon =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importIcon(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cloneCount = (savedInstanceState?.getInt(STATE_CLONE_COUNT, 1) ?: 1)
            .coerceIn(1, MAX_BATCH_CLONES)
        iconPath = savedInstanceState?.getString(STATE_ICON_PATH)
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_EXPANDED) ?: false
        stripAdId = savedInstanceState?.getBoolean(STATE_STRIP_AD_ID) ?: false

        setupQuickCountChips()

        updatingInputs = true
        binding.countInput.setText(cloneCount.toString())
        binding.stripAdIdSwitch.isChecked = stripAdId
        updatingInputs = false

        binding.backButton.setOnClickListener { finish() }
        binding.decreaseCountButton.setOnClickListener { setCloneCount(cloneCount - 1, syncText = true) }
        binding.increaseCountButton.setOnClickListener { setCloneCount(cloneCount + 1, syncText = true) }
        binding.countInput.doAfterTextChanged { text ->
            if (updatingInputs) return@doAfterTextChanged
            val parsed = text?.toString()?.trim()?.toIntOrNull() ?: return@doAfterTextChanged
            val clamped = parsed.coerceIn(1, MAX_BATCH_CLONES)
            if (clamped != parsed) {
                setCloneCount(clamped, syncText = true)
            } else {
                setCloneCount(clamped, syncText = false)
            }
        }

        binding.nameInput.doAfterTextChanged {
            if (!updatingInputs) refreshBatchStateAndPreviews(rebuildIcons = false)
        }
        binding.packageInput.doAfterTextChanged {
            if (!updatingInputs && cloneCount == 1) refreshBatchStateAndPreviews(rebuildIcons = true)
        }

        binding.clearNameButton.setOnClickListener { binding.nameInput.setText("") }
        binding.resetPackageButton.setOnClickListener { resetPackage() }
        binding.changeIconButton.setOnClickListener { pickIcon.launch(ICON_MIME) }
        binding.advancedHeader.setOnClickListener { setAdvancedExpanded(!advancedExpanded) }
        binding.stripAdIdSwitch.setOnCheckedChangeListener { _, checked -> stripAdId = checked }
        binding.createButton.setOnClickListener { startClone() }

        renderAdvancedState()
        renderQuickCountChipStyles()
        iconPath?.let { customGalleryBitmap = decodeSampled(it, ICON_PX) }

        loadSource(savedInstanceState == null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CLONE_COUNT, cloneCount)
        outState.putString(STATE_ICON_PATH, iconPath)
        outState.putBoolean(STATE_ADVANCED_EXPANDED, advancedExpanded)
        outState.putBoolean(STATE_STRIP_AD_ID, stripAdId)
    }

    private fun setupQuickCountChips() {
        binding.quickCountContainer.removeAllViews()
        quickCountChips.clear()
        for (preset in QUICK_COUNTS) {
            val chip = createPillChip("${preset}x")
            chip.setOnClickListener { setCloneCount(preset, syncText = true) }
            binding.quickCountContainer.addView(chip)
            quickCountChips[preset] = chip
        }
    }

    private fun createPillChip(label: String): TextView {
        val chip = TextView(this)
        chip.setTextAppearance(R.style.Text_OpenClone_Caption)
        chip.text = label
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(12), 0, dp(12), 0)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(44),
        ).apply {
            marginEnd = dp(6)
        }
        chip.layoutParams = lp
        return chip
    }

    private fun renderQuickCountChipStyles() {
        quickCountChips.forEach { (preset, chip) ->
            styleChip(chip, active = preset == cloneCount)
        }
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

    private fun setCloneCount(next: Int, syncText: Boolean) {
        val prevCount = cloneCount
        val clamped = next.coerceIn(1, MAX_BATCH_CLONES)
        cloneCount = clamped
        updatingInputs = true
        if (syncText) {
            val str = clamped.toString()
            if (binding.countInput.text?.toString() != str) {
                binding.countInput.setText(str)
                binding.countInput.setSelection(str.length)
            }
        }
        val app = sourceApp
        if (app != null) {
            val currentText = binding.nameInput.text?.toString()?.trim().orEmpty()
            val defaultSingle = suggestCloneLabel(
                app.label,
                nextCloneNumber(app.packageName, installedPackages),
            )
            if (prevCount == 1 && clamped > 1 && currentText == defaultSingle) {
                binding.nameInput.setText(app.label)
            } else if (prevCount > 1 && clamped == 1 && currentText.equals(app.label, ignoreCase = true)) {
                binding.nameInput.setText(defaultSingle)
                binding.packageInput.setText(suggestClonePackage(app.packageName, installedPackages))
            } else if (prevCount > 1 && clamped == 1) {
                binding.packageInput.setText(suggestClonePackage(app.packageName, installedPackages))
            }
        }
        updatingInputs = false
        renderQuickCountChipStyles()
        refreshBatchStateAndPreviews(rebuildIcons = true)
    }

    private fun loadSource(applyDefaults: Boolean) {
        val packageName = intent.getStringExtra(EXTRA_ORIGINAL_PACKAGE)
        if (packageName.isNullOrBlank()) {
            failAndClose()
            return
        }
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val repository = AppRepository(this@CloneConfigActivity)
                val deviceSet = repository.installedPackageNames()
                val historyPackages = CloneStore(this@CloneConfigActivity)
                    .list()
                    .map { it.newPackage }
                val app = repository.findInstalledApp(packageName)
                LoadedSourceData(
                    app = app,
                    devicePackages = deviceSet,
                    allKnownPackages = deviceSet + historyPackages,
                )
            }
            if (isFinishing || isDestroyed) return@launch
            val app = loaded.app
            if (app == null) {
                failAndClose()
                return@launch
            }
            sourceApp = app
            installedOnDevice = loaded.devicePackages
            installedPackages = loaded.allKnownPackages
            renderSource(app)
            if (applyDefaults) {
                updatingInputs = true
                val cloneNum = nextCloneNumber(app.packageName, loaded.allKnownPackages)
                binding.nameInput.setText(suggestCloneLabel(app.label, cloneNum))
                binding.packageInput.setText(suggestClonePackage(app.packageName, loaded.allKnownPackages))
                updatingInputs = false
            }
            refreshBatchStateAndPreviews(rebuildIcons = true)
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
        binding.sourceIcon.setImageDrawable(AppIcons.load(this, app.packageName, dp(48)))
    }

    private fun currentBatchSpecs(): List<BatchCloneSpec> {
        val app = sourceApp ?: return emptyList()
        return buildBatchCloneSpecs(
            originalPackage = app.packageName,
            originalLabel = app.label,
            customLabelInput = currentLabel(),
            customPackageInput = if (cloneCount == 1) {
                currentPackage()
            } else {
                suggestClonePackage(app.packageName, installedPackages)
            },
            count = cloneCount,
            existingPackages = installedPackages,
        )
    }

    private fun refreshBatchStateAndPreviews(rebuildIcons: Boolean) {
        if (sourceApp == null) return
        val specs = currentBatchSpecs()
        if (specs.isEmpty()) return

        updatingInputs = true
        if (cloneCount == 1) {
            binding.nameFieldLabel.setText(R.string.label_app_name)
            binding.packageInput.isEnabled = true
            binding.packageInput.alpha = 1f
            binding.resetPackageButton.visibility = View.VISIBLE
            binding.batchSummaryText.visibility = View.GONE
            binding.createButton.setText(R.string.create_clone)
        } else {
            binding.nameFieldLabel.setText(R.string.label_base_app_name)
            binding.packageInput.isEnabled = false
            binding.packageInput.alpha = 0.72f
            binding.packageInput.setText(
                getString(
                    R.string.batch_package_range,
                    specs.first().newPackage,
                    specs.last().cloneNumber,
                ),
            )
            binding.resetPackageButton.visibility = View.GONE
            binding.batchSummaryText.visibility = View.VISIBLE
            binding.batchSummaryText.text = getString(
                R.string.batch_preview_summary,
                specs.size,
                specs.first().newLabel,
                specs.last().newLabel,
            )
            binding.createButton.text = getString(R.string.create_clone_batch, specs.size)
        }
        updatingInputs = false

        if (rebuildIcons) {
            refreshIconPreview()
        }
    }

    private fun refreshIconPreview() {
        val app = sourceApp ?: return
        val custom = customGalleryBitmap
        if (custom != null) {
            binding.iconPreview.setImageBitmap(custom)
        } else {
            binding.iconPreview.setImageDrawable(AppIcons.load(this, app.packageName, dp(48)))
        }
    }

    private fun resetPackage() {
        val app = sourceApp ?: return
        if (cloneCount == 1) {
            binding.packageInput.setText(suggestClonePackage(app.packageName, installedPackages))
        }
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
        val specs = currentBatchSpecs()
        if (specs.isEmpty()) return

        val first = specs.first()
        startActivity(
            Intent(this, CloneProgressActivity::class.java)
                .putExtra(CloneProgressActivity.EXTRA_ORIGINAL_PACKAGE, app.packageName)
                .putExtra(CloneProgressActivity.EXTRA_ORIGINAL_LABEL, app.label)
                .putExtra(CloneProgressActivity.EXTRA_NEW_PACKAGE, first.newPackage)
                .putExtra(CloneProgressActivity.EXTRA_NEW_LABEL, first.newLabel)
                .putStringArrayListExtra(
                    CloneProgressActivity.EXTRA_BATCH_PACKAGES,
                    ArrayList(specs.map { it.newPackage }),
                )
                .putStringArrayListExtra(
                    CloneProgressActivity.EXTRA_BATCH_LABELS,
                    ArrayList(specs.map { it.newLabel }),
                )
                .putExtra(CloneProgressActivity.EXTRA_ICON_PATH, iconPath)
                .putExtra(CloneProgressActivity.EXTRA_STRIP_AD_ID, stripAdId),
        )
    }

    /** Returns the first user-facing problem, or null when the form is valid. */
    private fun validate(): String? {
        val app = sourceApp ?: return getString(R.string.error_app_not_found)
        val rawCount = binding.countInput.text?.toString()?.trim()?.toIntOrNull()
        if (rawCount == null || rawCount !in 1..MAX_BATCH_CLONES) {
            return getString(R.string.error_count_invalid, MAX_BATCH_CLONES)
        }
        if (cloneCount == 1) {
            if (currentLabel().isEmpty()) return getString(R.string.error_label_empty)
            val pkg = currentPackage()
            if (!isValidPackageName(pkg)) return getString(R.string.error_package_invalid)
            if (pkg == app.packageName) return getString(R.string.error_package_same)
            if (pkg in installedOnDevice) return getString(R.string.error_package_installed)
            // A package that only exists in the clone history would silently
            // overwrite the old APK and duplicate its history row.
            if (pkg in installedPackages) return getString(R.string.error_package_in_history)
        }
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
                val (path, decoded) = withContext(Dispatchers.IO) {
                    val savedPath = File(cacheDir, "picked_icon_${System.currentTimeMillis()}.img")
                        .apply { writeBytes(bytes) }
                        .absolutePath
                    savedPath to decodeSampled(savedPath, ICON_PX)
                }
                if (isFinishing || isDestroyed) return@launch
                replaceIconFile(path)
                customGalleryBitmap = decoded
                refreshIconPreview()
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

    private data class LoadedSourceData(
        val app: InstalledApp?,
        val devicePackages: Set<String>,
        val allKnownPackages: Set<String>,
    )

    companion object {
        const val EXTRA_ORIGINAL_PACKAGE = "original_package"

        private val QUICK_COUNTS = listOf(1, 2, 3, 5, 10)

        private const val STATE_CLONE_COUNT = "state_clone_count"
        private const val STATE_ICON_PATH = "state_icon_path"
        private const val STATE_ADVANCED_EXPANDED = "state_advanced_expanded"
        private const val STATE_STRIP_AD_ID = "state_strip_ad_id"
        private const val ICON_MIME = "image/*"

        /** Target size for decoding a gallery image used as the clone icon. */
        private const val ICON_PX = 192
    }
}
