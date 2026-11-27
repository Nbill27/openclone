package dev.bill.openclone

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException

/**
 * Shared APK installation helper used by both [CloneProgressActivity] and
 * [HistoryActivity].
 *
 * Enforces single-flight session staging with an instant progress dialog so
 * large multi-hundred-MB APKs (e.g. Facebook ~540 MB) show immediate feedback
 * and never stack concurrent system install dialogs.
 */
class ApkInstaller(
    private val activity: AppCompatActivity,
    private val onStatusUpdate: ((targetPackage: String, status: Int) -> Unit)? = null,
) : InstallStatusReceiver.Listener {

    private data class PendingInstall(
        val targetPackage: String,
        val apkFile: File,
        val displayLabel: String,
    )

    private enum class Stage {
        IDLE,
        STAGING,
        WAITING_CONFIRMATION,
    }

    private var stage: Stage = Stage.IDLE
    private var launchedConfirmationActivity = false
    private var pendingAfterSettings: PendingInstall? = null

    private var stagingDialog: AlertDialog? = null
    private var stagingLabelView: TextView? = null
    private var stagingProgressBar: LinearProgressIndicator? = null

    val isBusy: Boolean
        get() = stage != Stage.IDLE

    fun onResume() {
        InstallStatusReceiver.attachHost(activity, this)
        // If we previously handed off to the system confirmation dialog and the
        // user returned to our Activity, unlock so the next clone can be installed.
        if (stage == Stage.WAITING_CONFIRMATION && launchedConfirmationActivity) {
            launchedConfirmationActivity = false
            stage = Stage.IDLE
        }
        val pending = pendingAfterSettings ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingAfterSettings = null
            install(pending.targetPackage, pending.apkFile, pending.displayLabel)
        }
    }

    fun onPause() {
        if (stage == Stage.WAITING_CONFIRMATION) {
            launchedConfirmationActivity = true
        }
        if (activity.isFinishing) {
            dismissStagingDialog()
            InstallStatusReceiver.detachHost(activity)
        }
    }

    override fun onPendingUserAction(targetPackage: String) {
        activity.runOnUiThread {
            dismissStagingDialog()
            stage = Stage.WAITING_CONFIRMATION
            launchedConfirmationActivity = true
            onStatusUpdate?.invoke(targetPackage, PackageInstaller.STATUS_PENDING_USER_ACTION)
        }
    }

    override fun onInstallTerminalStatus(targetPackage: String, status: Int) {
        activity.runOnUiThread {
            dismissStagingDialog()
            launchedConfirmationActivity = false
            stage = Stage.IDLE
            onStatusUpdate?.invoke(targetPackage, status)
        }
    }

    fun install(
        targetPackage: String,
        apkFile: File,
        displayLabel: String = targetPackage,
    ) {
        if (stage != Stage.IDLE) {
            if (stage == Stage.WAITING_CONFIRMATION) {
                toast(activity.getString(R.string.staging_install_busy))
            }
            return
        }
        if (!apkFile.exists() || apkFile.length() == 0L) {
            toast(activity.getString(R.string.error_clone_file_missing))
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingAfterSettings = PendingInstall(targetPackage, apkFile, displayLabel)
            toast(activity.getString(R.string.install_unknown_sources_prompt))
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
            try {
                activity.startActivity(settingsIntent)
                return
            } catch (_: Exception) {
                pendingAfterSettings = null
            }
        }

        stage = Stage.STAGING
        launchedConfirmationActivity = false
        val label = displayLabel.ifBlank { targetPackage }
        showStagingDialog(label)

        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    commitSession(targetPackage, apkFile) { percent ->
                        updateStagingProgress(label, percent)
                    }
                }
                // Keep stage = WAITING_CONFIRMATION until InstallStatusReceiver
                // launches the confirmation dialog or reports terminal status.
                if (stage == Stage.STAGING) {
                    stage = Stage.WAITING_CONFIRMATION
                }
            } catch (e: CancellationException) {
                dismissStagingDialog()
                stage = Stage.IDLE
                throw e
            } catch (e: Exception) {
                dismissStagingDialog()
                stage = Stage.IDLE
                val msg = e.message.orEmpty()
                if (msg.contains("ENOSPC", ignoreCase = true) ||
                    msg.contains("space", ignoreCase = true) ||
                    msg.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true)
                ) {
                    showFailureDialog(
                        host = activity,
                        message = activity.getString(
                            R.string.install_failed,
                            "${activity.getString(R.string.install_error_storage)} ($msg)",
                        ),
                    )
                } else {
                    val parseError = withContext(Dispatchers.IO) {
                        verifyLocalArchive(activity, apkFile)
                    }
                    if (parseError != null) {
                        showFailureDialog(
                            host = activity,
                            message = activity.getString(R.string.install_failed, parseError),
                            onTryAnyway = { installWithViewer(apkFile) },
                        )
                    } else {
                        installWithViewer(apkFile)
                    }
                }
            }
        }
    }

    private fun showStagingDialog(displayLabel: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismissStagingDialog()

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val subtitle = TextView(activity).apply {
            setTextAppearance(R.style.Text_OpenClone_Detail)
            text = activity.getString(R.string.staging_install_progress, displayLabel, 0)
        }
        val progress = LinearProgressIndicator(activity).apply {
            isIndeterminate = false
            max = 100
            progress = 2
            trackCornerRadius = dp(4)
            trackThickness = dp(6)
            setIndicatorColor(ContextCompat.getColor(activity, R.color.accent_white))
            trackColor = ContextCompat.getColor(activity, R.color.switch_off_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(14)
            }
        }
        container.addView(subtitle)
        container.addView(progress)

        stagingLabelView = subtitle
        stagingProgressBar = progress
        stagingDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.staging_install_title)
            .setView(container)
            .setCancelable(false)
            .create()
        runCatching { stagingDialog?.show() }
    }

    private fun updateStagingProgress(displayLabel: String, percent: Int) {
        val clamped = percent.coerceIn(1, 100)
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            stagingLabelView?.text =
                activity.getString(R.string.staging_install_progress, displayLabel, clamped)
            stagingProgressBar?.setProgressCompat(clamped, true)
        }
    }

    private fun dismissStagingDialog() {
        runCatching { stagingDialog?.dismiss() }
        stagingDialog = null
        stagingLabelView = null
        stagingProgressBar = null
    }

    private fun commitSession(
        targetPackage: String,
        apkFile: File,
        onProgress: (Int) -> Unit,
    ) {
        val installer = activity.packageManager.packageInstaller
        val totalBytes = apkFile.length()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (targetPackage.isNotBlank()) {
            params.setAppPackageName(targetPackage)
        }
        params.setSize(totalBytes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { input ->
                    session.openWrite("base.apk", 0, totalBytes).use { output ->
                        val buffer = ByteArray(SESSION_COPY_BUFFER_BYTES)
                        var copied = 0L
                        var lastPercent = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes > 0L) {
                                val pct = ((copied * 100L) / totalBytes).toInt().coerceIn(1, 99)
                                if (pct >= lastPercent + 3) {
                                    lastPercent = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        onProgress(100)
                        session.fsync(output)
                    }
                }
                val explicitStatusIntent = Intent(activity, InstallStatusReceiver::class.java).apply {
                    putExtra(InstallStatusReceiver.EXTRA_TARGET_PACKAGE, targetPackage)
                }
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val pending = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    explicitStatusIntent,
                    flags,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun installWithViewer(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
            AppRepository.invalidate()
        } catch (e: Exception) {
            toast(
                activity.getString(
                    R.string.install_viewer_missing,
                    readableError(activity, e),
                ),
            )
        }
    }

    private fun toast(message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val SESSION_COPY_BUFFER_BYTES = 1024 * 1024 // 1 MB buffer for fast staging

        internal fun verifyLocalArchive(context: Context, apkFile: File): String? {
            val pm = context.packageManager
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(
                        apkFile.absolutePath,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                }
            }.getOrNull()
            if (info != null) return null

            val detailed = extractPackageParserReason(apkFile)
            return if (!detailed.isNullOrBlank()) {
                "${context.getString(R.string.install_error_invalid_apk)} ($detailed)"
            } else {
                "${context.getString(R.string.install_error_invalid_apk)} (getPackageArchiveInfo=null, size=${apkFile.length()})"
            }
        }

        private fun extractPackageParserReason(apkFile: File): String? {
            return try {
                val parserClass = Class.forName("android.content.pm.PackageParser")
                val parser = parserClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val parseMethod = parserClass.getDeclaredMethod(
                    "parsePackage",
                    File::class.java,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                parseMethod.invoke(parser, apkFile, 0)
                null
            } catch (ite: InvocationTargetException) {
                val cause = ite.targetException ?: ite
                cause.message ?: cause.javaClass.simpleName
            } catch (_: Throwable) {
                null
            }
        }

        fun showFailureDialog(
            host: Activity,
            message: String,
            onTryAnyway: (() -> Unit)? = null,
        ) {
            if (host.isFinishing || host.isDestroyed) return
            val builder = MaterialAlertDialogBuilder(host)
                .setTitle(R.string.install_error_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_close, null)
                .setNeutralButton(R.string.action_copy_error) { _, _ ->
                    val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("OpenClone Error", message))
                    Toast.makeText(host, R.string.error_copied, Toast.LENGTH_SHORT).show()
                }
            if (onTryAnyway != null) {
                builder.setNegativeButton(R.string.action_try_anyway) { _, _ ->
                    onTryAnyway()
                }
            }
            runCatching { builder.show() }
        }

        fun readableInstallFailure(context: Context, status: Int, rawMessage: String): String {
            val detail = rawMessage.trim()
            return when {
                detail.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true) ->
                    "${context.getString(R.string.install_error_permission_conflict)} ($detail)"
                detail.contains("INSTALL_FAILED_CONFLICTING_PROVIDER", ignoreCase = true) ->
                    "${context.getString(R.string.install_error_provider_conflict)} ($detail)"
                detail.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    detail.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true) ->
                    context.getString(R.string.install_error_signature)
                detail.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                    context.getString(R.string.install_error_downgrade)
                detail.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ||
                    status == PackageInstaller.STATUS_FAILURE_STORAGE ->
                    if (detail.isNotEmpty()) {
                        "${context.getString(R.string.install_error_storage)} ($detail)"
                    } else {
                        context.getString(R.string.install_error_storage)
                    }
                status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    if (detail.isNotEmpty()) {
                        "${context.getString(R.string.install_error_conflict)} ($detail)"
                    } else {
                        context.getString(R.string.install_error_conflict)
                    }
                status == PackageInstaller.STATUS_FAILURE_INVALID ->
                    if (detail.isNotEmpty()) {
                        "${context.getString(R.string.install_error_invalid_apk)} ($detail)"
                    } else {
                        context.getString(R.string.install_error_invalid_apk)
                    }
                status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    if (detail.isNotEmpty()) {
                        "${context.getString(R.string.install_error_incompatible)} ($detail)"
                    } else {
                        context.getString(R.string.install_error_incompatible)
                    }
                status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                    if (detail.isNotEmpty()) {
                        "${context.getString(R.string.install_error_blocked)} ($detail)"
                    } else {
                        context.getString(R.string.install_error_blocked)
                    }
                detail.isNotEmpty() -> detail
                else -> "kode $status"
            }
        }

        fun readableError(context: Context, error: Throwable): String {
            val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            return when {
                raw.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true) ->
                    context.getString(R.string.install_error_permission_conflict)
                raw.contains("INSTALL_FAILED_CONFLICTING_PROVIDER", ignoreCase = true) ->
                    context.getString(R.string.install_error_provider_conflict)
                raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ->
                    context.getString(R.string.install_error_signature)
                raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ->
                    context.getString(R.string.install_error_downgrade)
                raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ||
                    raw.contains("ENOSPC", ignoreCase = true) ->
                    context.getString(R.string.install_error_storage)
                raw.contains(KeystoreManager.ERROR_KEY_UNREADABLE, ignoreCase = true) ->
                    context.getString(R.string.keystore_error_unreadable)
                else -> raw
            }
        }
    }
}
