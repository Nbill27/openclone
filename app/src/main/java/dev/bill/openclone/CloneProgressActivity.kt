package dev.bill.openclone

import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
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
 * Layar 3 "Proses Cloning" and Layar 4 "Selesai": executes single or multi-clone
 * jobs sequentially with distinct recolored icons per clone and presents either
 * a single APK summary or a multi-clone batch installation list.
 */
class CloneProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloneProgressBinding
    private var resultBinding: ActivityCloneResultBinding? = null
    private lateinit var apkInstaller: ApkInstaller

    private val stepRows = mutableListOf<ItemStepBinding>()
    private var results: List<CloneResult> = emptyList()
    private var batchWarning: String? = null
    private var isCloning = false

    private val batchActionButtons = LinkedHashMap<String, TextView>()
    private var activeInstallingPackage: String? = null

    private val resultBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloneProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkInstaller = ApkInstaller(this) { targetPackage, status ->
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    activeInstallingPackage = targetPackage
                }
                else -> {
                    if (activeInstallingPackage == targetPackage) {
                        activeInstallingPackage = null
                    }
                }
            }
            if (results.size > 1) {
                refreshBatchInstallStates()
            }
        }
        onBackPressedDispatcher.addCallback(this, resultBackCallback)

        binding.backButton.setOnClickListener { finish() }
        buildStepRows()

        val restored = savedInstanceState
        if (restored != null && restored.getBoolean(STATE_DONE)) {
            val restoredResults = restoreResultsFromState(restored)
            if (restoredResults.isNotEmpty()) {
                showResults(
                    clonedList = restoredResults,
                    warning = restored.getString(STATE_BATCH_WARNING),
                )
                return
            }
        }

        startClone()
    }

    override fun onResume() {
        super.onResume()
        apkInstaller.onResume()
        if (results.size > 1) {
            refreshBatchInstallStates()
        }
    }

    override fun onPause() {
        apkInstaller.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (results.isEmpty()) return
        val first = results.first()
        outState.putBoolean(STATE_DONE, true)
        outState.putString(STATE_ORIGINAL_PACKAGE, first.originalPackage)
        outState.putStringArrayList(STATE_PACKAGES, ArrayList(results.map { it.newPackage }))
        outState.putStringArrayList(STATE_LABELS, ArrayList(results.map { it.newLabel }))
        outState.putStringArrayList(STATE_FILES, ArrayList(results.map { it.outputFileName }))
        outState.putLongArray(STATE_SIZES, results.map { it.outputFileSize }.toLongArray())
        outState.putBoolean(STATE_HAD_SPLITS, first.hadSplits)
        outState.putStringArrayList(STATE_COMPAT_NOTES, ArrayList(first.compatNotes))
        outState.putStringArrayList(STATE_IDENTITY_NOTES, ArrayList(first.identityNotes))
        outState.putString(STATE_BATCH_WARNING, batchWarning)
    }

    private fun restoreResultsFromState(state: Bundle): List<CloneResult> {
        val origPkg = state.getString(STATE_ORIGINAL_PACKAGE).orEmpty()
        val pkgs = state.getStringArrayList(STATE_PACKAGES).orEmpty()
        val labels = state.getStringArrayList(STATE_LABELS).orEmpty()
        val files = state.getStringArrayList(STATE_FILES).orEmpty()
        val sizes = state.getLongArray(STATE_SIZES) ?: LongArray(0)
        val hadSplits = state.getBoolean(STATE_HAD_SPLITS)
        val compat = state.getStringArrayList(STATE_COMPAT_NOTES).orEmpty()
        val identity = state.getStringArrayList(STATE_IDENTITY_NOTES).orEmpty()
        if (pkgs.isEmpty() || pkgs.size != labels.size || pkgs.size != files.size) {
            return emptyList()
        }
        return pkgs.indices.map { idx ->
            CloneResult(
                originalPackage = origPkg,
                newPackage = pkgs[idx],
                newLabel = labels[idx],
                outputFileName = files[idx],
                outputFileSize = sizes.getOrElse(idx) { 0L },
                hadSplits = hadSplits,
                compatNotes = compat,
                identityNotes = identity,
            )
        }
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
        val singlePkg = intent.getStringExtra(EXTRA_NEW_PACKAGE)
        val singleLabel = intent.getStringExtra(EXTRA_NEW_LABEL)

        val batchPackages = intent.getStringArrayListExtra(EXTRA_BATCH_PACKAGES)
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(singlePkg)
        val batchLabels = intent.getStringArrayListExtra(EXTRA_BATCH_LABELS)
            ?.takeIf { it.size == batchPackages.size }
            ?: listOfNotNull(singleLabel)
        val batchNumbers = intent.getIntArrayExtra(EXTRA_BATCH_NUMBERS)
            ?.takeIf { it.size == batchPackages.size }
            ?: batchPackages.mapIndexed { i, pkg ->
                extractCloneNumber(pkg, batchLabels.getOrElse(i) { "" }, fallback = i + 1)
            }.toIntArray()

        if (originalPackage.isNullOrBlank() || batchPackages.isEmpty() || batchLabels.size != batchPackages.size) {
            toast(getString(R.string.error_app_not_found))
            finish()
            return
        }

        isCloning = true
        val originalLabel = intent.getStringExtra(EXTRA_ORIGINAL_LABEL).orEmpty()
        val iconPath = intent.getStringExtra(EXTRA_ICON_PATH)
        val iconColor = IconColorOption.fromId(intent.getStringExtra(EXTRA_ICON_COLOR))
        val iconBadge = intent.getBooleanExtra(EXTRA_ICON_BADGE, false)
        val stripAdId = intent.getBooleanExtra(EXTRA_STRIP_AD_ID, false)
        val compatMode = SettingsActivity.isCompatModeEnabled(this)

        lifecycleScope.launch {
            val completed = mutableListOf<CloneResult>()
            var baseBitmap: Bitmap? = null
            try {
                val (app, rawCustomBytes, loadedBitmap) = withContext(Dispatchers.IO) {
                    val installed = AppRepository(this@CloneProgressActivity)
                        .findInstalledApp(originalPackage)
                    val customFile = iconPath?.let { File(it) }?.takeIf { it.exists() }
                    val customBytes = customFile?.let { runCatching { it.readBytes() }.getOrNull() }
                    val bmp = when {
                        customFile != null -> decodeSampled(customFile.absolutePath, IconColorizer.EXPORT_ICON_PX)
                        installed != null -> IconColorizer.loadBaseAppBitmap(
                            this@CloneProgressActivity,
                            installed.packageName,
                            IconColorizer.EXPORT_ICON_PX,
                        )
                        else -> null
                    }
                    Triple(installed, customBytes, bmp)
                }
                baseBitmap = loadedBitmap
                if (app == null) {
                    fail(getString(R.string.error_app_not_found))
                    return@launch
                }

                val total = batchPackages.size
                val engine = CloneEngine(this@CloneProgressActivity)
                val store = CloneStore(this@CloneProgressActivity)
                var partialError: String? = null

                for (idx in 0 until total) {
                    val targetPkg = batchPackages[idx]
                    val targetLabel = batchLabels[idx]
                    val cloneNum = batchNumbers[idx]

                    try {
                        val iconBytes = withContext(Dispatchers.Default) {
                            buildCloneIconBytes(
                                baseBitmap = baseBitmap,
                                rawCustomBytes = rawCustomBytes,
                                iconColor = iconColor,
                                iconBadge = iconBadge,
                                cloneNumber = cloneNum,
                            )
                        }

                        val config = CloneConfig(
                            originalPackage = originalPackage,
                            originalLabel = originalLabel.ifBlank { app.label },
                            newPackage = targetPkg,
                            newLabel = targetLabel,
                            iconBytes = iconBytes,
                            stripAdId = stripAdId,
                            compatMode = compatMode,
                        )

                        val cloned = withContext(Dispatchers.Default) {
                            engine.clone(app, config) { line ->
                                onBatchProgress(
                                    batchIndex = idx,
                                    batchTotal = total,
                                    cloneLabel = targetLabel,
                                    line = line,
                                )
                            }
                        }
                        if (isFinishing || isDestroyed) return@launch

                        withContext(Dispatchers.IO) {
                            store.add(
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
                        completed += cloned
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val readable = ApkInstaller.readableError(this@CloneProgressActivity, e)
                        if (completed.isEmpty()) {
                            throw e
                        } else {
                            partialError = readable
                            break
                        }
                    }
                }

                onFinished()
                showResults(
                    clonedList = completed,
                    warning = partialError?.let { getString(R.string.batch_partial_warning, it) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isFinishing || isDestroyed) return@launch
                fail(ApkInstaller.readableError(this@CloneProgressActivity, e))
            } finally {
                baseBitmap?.recycle()
                isCloning = false
            }
        }
    }

    private fun buildCloneIconBytes(
        baseBitmap: Bitmap?,
        rawCustomBytes: ByteArray?,
        iconColor: IconColorOption,
        iconBadge: Boolean,
        cloneNumber: Int,
    ): ByteArray? {
        val needsStyling = iconColor != IconColorOption.ORIGINAL || iconBadge
        if (!needsStyling) {
            return rawCustomBytes
        }
        if (baseBitmap == null) {
            return rawCustomBytes
        }
        val styled = IconColorizer.createStyledBitmap(
            baseBitmap = baseBitmap,
            option = iconColor,
            cloneNumber = cloneNumber,
            showBadge = iconBadge,
            sizePx = IconColorizer.EXPORT_ICON_PX,
        )
        return try {
            IconColorizer.encodePng(styled)
        } finally {
            if (styled !== baseBitmap) styled.recycle()
        }
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

    private suspend fun onBatchProgress(
        batchIndex: Int,
        batchTotal: Int,
        cloneLabel: String,
        line: String,
    ) {
        val update = PROGRESS_STEPS.firstOrNull { line.startsWith(it.prefix) }
            ?: ProgressUpdate(line, STEP_PREPARE, 5)
        val overallPercent = if (batchTotal <= 1) {
            update.percent
        } else {
            ((batchIndex * 100 + update.percent) / batchTotal).coerceIn(2, 99)
        }
        withContext(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@withContext
            renderSteps(doneThrough = update.step - 1, active = update.step)
            binding.progressRing.progress = overallPercent
            if (batchTotal > 1) {
                binding.progressHeadline.text =
                    getString(R.string.progress_batch_headline, batchIndex + 1, batchTotal)
                binding.progressSubtitle.text = "$cloneLabel • ${line.trimEnd('.')}"
            } else {
                binding.progressHeadline.setText(R.string.progress_headline)
                binding.progressSubtitle.text = line.trimEnd('.')
            }
        }
    }

    private suspend fun onFinished() {
        withContext(Dispatchers.Main.immediate) {
            if (isFinishing || isDestroyed) return@withContext
            renderSteps(doneThrough = STEP_FINALIZE, active = -1)
            binding.progressRing.progress = 100
            binding.progressSubtitle.text = getString(R.string.success_subtitle)
        }
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

    private fun showResults(clonedList: List<CloneResult>, warning: String? = null) {
        if (results.isNotEmpty() || clonedList.isEmpty()) return
        results = clonedList
        batchWarning = warning
        resultBackCallback.isEnabled = true
        isCloning = false

        val view = ActivityCloneResultBinding.inflate(layoutInflater)
        resultBinding = view
        setContentView(view.root)

        val first = clonedList.first()
        view.backButton.setOnClickListener { goHome() }

        val mergedCompatNotes = buildList {
            if (!warning.isNullOrBlank()) add(warning)
            addAll(first.compatNotes)
        }
        renderNoteList(view.compatList, view.compatCard, mergedCompatNotes)
        renderNoteList(view.identityList, view.identityCard, first.identityNotes)

        if (clonedList.size == 1) {
            view.resultHeadline.setText(R.string.success_headline)
            view.resultSubtitle.setText(R.string.success_subtitle)
            view.singleSummaryCard.visibility = View.VISIBLE
            view.batchListCard.visibility = View.GONE
            view.secondaryActionsRow.visibility = View.VISIBLE

            view.resultName.text = first.newLabel
            view.resultPackage.text = first.newPackage
            view.resultSize.text = formatSize(first.outputFileSize)
            lifecycleScope.launch {
                val icon = withContext(Dispatchers.IO) {
                    AppIcons.load(this@CloneProgressActivity, first.newPackage, dp(48))
                        ?: AppIcons.load(this@CloneProgressActivity, first.originalPackage, dp(48))
                }
                if (isFinishing || isDestroyed) return@launch
                icon?.let(view.resultIcon::setImageDrawable)
            }

            view.installButton.setOnClickListener { installSingleClone(first) }
            view.shareButton.setOnClickListener { shareClone(first) }
            view.viewFileButton.setOnClickListener { viewCloneFile(first) }
        } else {
            view.resultHeadline.text = getString(R.string.success_batch_headline, clonedList.size)
            view.resultSubtitle.setText(R.string.success_batch_subtitle)
            view.singleSummaryCard.visibility = View.GONE
            view.batchListCard.visibility = View.VISIBLE
            view.secondaryActionsRow.visibility = View.GONE

            populateBatchResultRows(view, clonedList)
            view.installButton.setOnClickListener { installNextBatchClone() }
            refreshBatchInstallStates()
        }

        view.createAnotherButton.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
            finish()
        }
    }

    private fun populateBatchResultRows(
        view: ActivityCloneResultBinding,
        clonedList: List<CloneResult>,
    ) {
        view.batchResultList.removeAllViews()
        batchActionButtons.clear()

        clonedList.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) topMargin = dp(10)
                }
            }

            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setBackgroundResource(R.drawable.bg_icon_squircle)
                val pad = dp(6)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = item.newLabel
            }
            lifecycleScope.launch {
                val icon = withContext(Dispatchers.IO) {
                    AppIcons.load(this@CloneProgressActivity, item.newPackage, dp(44))
                        ?: AppIcons.load(this@CloneProgressActivity, item.originalPackage, dp(44))
                }
                if (!isFinishing && !isDestroyed) {
                    icon?.let(iconView::setImageDrawable)
                }
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = dp(10)
                    marginEnd = dp(8)
                }
            }
            val titleTv = TextView(this).apply {
                setTextAppearance(R.style.Text_OpenClone_ItemTitle)
                text = item.newLabel
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val metaTv = TextView(this).apply {
                setTextAppearance(R.style.Text_OpenClone_Caption)
                text = "${item.newPackage} • ${formatSize(item.outputFileSize)}"
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            textCol.addView(titleTv)
            textCol.addView(metaTv)

            val actionBtn = TextView(this).apply {
                setTextAppearance(R.style.Text_OpenClone_Caption)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(R.drawable.bg_pill_clone_touch)
                setTextColor(ContextCompat.getColor(this@CloneProgressActivity, R.color.text_on_white))
                text = getString(R.string.batch_item_install)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(44),
                )
                setOnClickListener { installSingleClone(item) }
            }
            batchActionButtons[item.newPackage] = actionBtn

            row.addView(iconView)
            row.addView(textCol)
            row.addView(actionBtn)
            view.batchResultList.addView(row)
        }
        view.installButton.text = getString(R.string.install_next_batch, 1, clonedList.size)
    }

    private fun refreshBatchInstallStates() {
        val view = resultBinding ?: return
        if (results.size <= 1) return

        var installedCount = 0
        for (item in results) {
            val isInstalled = isPackageInstalledFast(item.newPackage)
            if (isInstalled) {
                installedCount++
                if (activeInstallingPackage == item.newPackage) {
                    activeInstallingPackage = null
                }
            }
            val isCurrentlyInstalling = !isInstalled && activeInstallingPackage == item.newPackage
            batchActionButtons[item.newPackage]?.apply {
                when {
                    isInstalled -> {
                        isEnabled = false
                        setBackgroundResource(R.drawable.bg_chip_inactive_touch)
                        setTextColor(
                            ContextCompat.getColor(
                                this@CloneProgressActivity,
                                R.color.text_secondary,
                            ),
                        )
                        text = getString(R.string.batch_item_installed)
                    }
                    isCurrentlyInstalling -> {
                        isEnabled = false
                        setBackgroundResource(R.drawable.bg_chip_inactive_touch)
                        setTextColor(
                            ContextCompat.getColor(
                                this@CloneProgressActivity,
                                R.color.accent_white,
                            ),
                        )
                        text = getString(R.string.batch_item_installing)
                    }
                    else -> {
                        isEnabled = true
                        setBackgroundResource(R.drawable.bg_pill_clone_touch)
                        setTextColor(
                            ContextCompat.getColor(
                                this@CloneProgressActivity,
                                R.color.text_on_white,
                            ),
                        )
                        text = getString(R.string.batch_item_install)
                    }
                }
            }
        }

        if (installedCount >= results.size) {
            view.installButton.isEnabled = false
            view.installButton.alpha = 0.7f
            view.installButton.setText(R.string.batch_all_installed)
        } else {
            view.installButton.isEnabled = true
            view.installButton.alpha = 1f
            view.installButton.text = getString(
                R.string.install_next_batch,
                installedCount + 1,
                results.size,
            )
        }
    }

    private fun installNextBatchClone() {
        val next = results.firstOrNull { !isPackageInstalledFast(it.newPackage) }
            ?: results.firstOrNull()
            ?: return
        installSingleClone(next)
    }

    private fun isPackageInstalledFast(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

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

    private fun cloneFile(item: CloneResult): File? {
        val file = File(File(filesDir, "clones"), item.outputFileName)
        return file.takeIf { it.exists() }
    }

    private fun installSingleClone(item: CloneResult) {
        val file = cloneFile(item)
        if (file == null) {
            toast(getString(R.string.error_clone_file_missing))
            return
        }
        apkInstaller.install(item.newPackage, file, item.newLabel)
    }

    private fun shareClone(item: CloneResult) {
        val file = cloneFile(item)
        if (file == null) {
            toast(getString(R.string.error_clone_file_missing))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ApkInstaller.APK_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(
                Intent.createChooser(intent, getString(R.string.share_chooser_title)),
            )
        } catch (_: Exception) {
            toast(getString(R.string.no_share_target))
        }
    }

    private fun viewCloneFile(item: CloneResult) {
        val file = cloneFile(item)
        if (file == null) {
            toast(getString(R.string.error_clone_file_missing))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ApkInstaller.APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
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

    private data class ProgressUpdate(val prefix: String, val step: Int, val percent: Int)

    companion object {
        const val EXTRA_ORIGINAL_PACKAGE = "original_package"
        const val EXTRA_ORIGINAL_LABEL = "original_label"
        const val EXTRA_NEW_PACKAGE = "new_package"
        const val EXTRA_NEW_LABEL = "new_label"
        const val EXTRA_BATCH_PACKAGES = "batch_packages"
        const val EXTRA_BATCH_LABELS = "batch_labels"
        const val EXTRA_BATCH_NUMBERS = "batch_numbers"
        const val EXTRA_ICON_PATH = "icon_path"
        const val EXTRA_ICON_COLOR = "icon_color"
        const val EXTRA_ICON_BADGE = "icon_badge"
        const val EXTRA_STRIP_AD_ID = "strip_ad_id"

        private const val STEP_PREPARE = 0
        private const val STEP_FINALIZE = 4

        private const val STATE_DONE = "state_done"
        private const val STATE_ORIGINAL_PACKAGE = "state_original_package"
        private const val STATE_PACKAGES = "state_packages"
        private const val STATE_LABELS = "state_labels"
        private const val STATE_FILES = "state_files"
        private const val STATE_SIZES = "state_sizes"
        private const val STATE_HAD_SPLITS = "state_had_splits"
        private const val STATE_COMPAT_NOTES = "state_compat_notes"
        private const val STATE_IDENTITY_NOTES = "state_identity_notes"
        private const val STATE_BATCH_WARNING = "state_batch_warning"

        private val PROGRESS_STEPS = listOf(
            ProgressUpdate("Menyalin APK asli", 1, 12),
            ProgressUpdate("Membaca dan menggabungkan APK", 1, 26),
            ProgressUpdate("Mengganti package name", 2, 42),
            ProgressUpdate("Mengganti nama aplikasi", 2, 54),
            ProgressUpdate("Mengganti ikon", 2, 62),
            ProgressUpdate("Menyesuaikan referensi DEX", 2, 70),
            ProgressUpdate("Menulis APK baru", 3, 80),
            ProgressUpdate("Menandatangani APK", 3, 92),
        )
    }
}
