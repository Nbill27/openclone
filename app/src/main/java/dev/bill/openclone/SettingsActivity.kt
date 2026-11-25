package dev.bill.openclone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.bill.openclone.databinding.ActivitySettingsBinding
import dev.bill.openclone.databinding.DialogKeystorePasswordBinding
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar 7 "Pengaturan" (DESAIN.md section 4): grouped preference rows for
 * general and cloning options, the signing-key backup group, and the only
 * route to Layar 8 "Tentang".
 *
 * Two rows are informational rather than free choices: OpenClone ships a
 * dark-only theme, and a clone is useless unless it is signed, so both
 * switches snap back with an explanation instead of silently doing nothing.
 *
 * The keystore group is the safety net for the single key that signs every
 * clone: without a backup, losing the key means every installed clone can
 * only be reinstalled from scratch.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var keystoreManager: KeystoreManager

    /** Guards switch listeners while the UI is being seeded. */
    private var seedingSwitches = false

    /** Password chosen before the SAF export picker returns. */
    private var pendingExportPassword: CharArray? = null

    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_PKCS12)) { uri ->
            val password = pendingExportPassword
            pendingExportPassword = null
            if (uri == null || password == null) return@registerForActivityResult
            exportKeystore(uri, password)
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            confirmReplaceKey(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keystoreManager = KeystoreManager(this)

        binding.backButton.setOnClickListener { finish() }
        binding.bottomNav.select(BottomNavView.Tab.SETTINGS)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val clonesDir = File(filesDir, CLONES_DIR)

        binding.storageValue.text = clonesDir.absolutePath
        binding.storageRow.setOnClickListener { showStorageInfo(clonesDir) }
        binding.aboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.keystoreStatusRow.setOnClickListener { showKeyDetails() }
        binding.exportKeystoreRow.setOnClickListener { startExport() }
        binding.importKeystoreRow.setOnClickListener { startImport() }

        seedSwitch(binding.darkModeSwitch, prefs.getBoolean(KEY_DARK_MODE, true))
        seedSwitch(binding.compatSwitch, prefs.getBoolean(KEY_COMPAT, false))
        seedSwitch(binding.autoSignSwitch, prefs.getBoolean(KEY_AUTO_SIGN, true))

        binding.darkModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (seedingSwitches) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_DARK_MODE, checked).apply()
            if (!checked) {
                // Dark is the only theme, so revert instead of pretending.
                seedSwitch(binding.darkModeSwitch, true)
                toast(getString(R.string.settings_dark_only))
            }
        }

        binding.compatSwitch.setOnCheckedChangeListener { _, checked ->
            if (seedingSwitches) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_COMPAT, checked).apply()
        }

        binding.autoSignSwitch.setOnCheckedChangeListener { _, checked ->
            if (seedingSwitches) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_AUTO_SIGN, checked).apply()
            if (!checked) {
                seedSwitch(binding.autoSignSwitch, true)
                toast(getString(R.string.settings_sign_required))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKeystoreStatus()
    }

    /**
     * Sets a switch without firing its listener. The listener is installed
     * after seeding, but the revert paths reuse this helper at runtime.
     */
    private fun seedSwitch(switch: android.widget.CompoundButton, checked: Boolean) {
        seedingSwitches = true
        switch.isChecked = checked
        seedingSwitches = false
    }

    private fun showStorageInfo(clonesDir: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_storage)
            .setMessage(getString(R.string.settings_storage_body, clonesDir.absolutePath))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    // ---------------------------------------------------------------- keystore

    private fun refreshKeystoreStatus() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { keystoreManager.describeKey() }
            if (isFinishing || isDestroyed) return@launch
            binding.keystoreStatus.text = if (info == null) {
                getString(R.string.settings_keystore_status_none)
            } else {
                getString(
                    R.string.settings_keystore_value_format,
                    dateFormat.format(Date(info.validFrom)),
                    dateFormat.format(Date(info.validUntil)),
                )
            }
        }
    }

    private fun showKeyDetails() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { keystoreManager.describeKey() }
            if (isFinishing || isDestroyed) return@launch
            if (info == null) {
                toast(getString(R.string.keystore_no_key_yet))
                return@launch
            }
            val body = getString(
                R.string.keystore_detail_body,
                getString(R.string.keystore_detail_subject), info.subject,
                getString(R.string.keystore_detail_algorithm), "${info.algorithm} ${info.keySize} bit",
                getString(R.string.keystore_detail_valid), dateFormat.format(Date(info.validUntil)),
                getString(R.string.keystore_detail_fingerprint), info.sha256Fingerprint,
            )
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.keystore_details_title)
                .setMessage(body)
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    private fun startExport() {
        promptPassword(R.string.keystore_password_title_export) { password ->
            pendingExportPassword = password
            createDocument.launch(keystoreManager.suggestedExportName())
        }
    }

    private fun exportKeystore(uri: Uri, password: CharArray) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val out = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("OUTPUT_STREAM_UNAVAILABLE")
                    out.use { keystoreManager.exportTo(it, password) }
                }.exceptionOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (error == null) {
                toast(getString(R.string.keystore_export_success))
            } else {
                toast(getString(R.string.keystore_export_failed, keystoreError(error)))
            }
        }
    }

    private fun startImport() {
        openDocument.launch(arrayOf(MIME_PKCS12, "application/octet-stream", "*/*"))
    }

    private fun confirmReplaceKey(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.keystore_import_confirm_title)
            .setMessage(R.string.keystore_import_confirm_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                promptPassword(R.string.keystore_password_title_import) { password ->
                    importKeystore(uri, password)
                }
            }
            .show()
    }

    private fun importKeystore(uri: Uri, password: CharArray) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("INPUT_STREAM_UNAVAILABLE")
                    input.use { keystoreManager.importFrom(it, password) }
                }.exceptionOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (error == null) {
                toast(getString(R.string.keystore_import_success))
                refreshKeystoreStatus()
            } else {
                toast(getString(R.string.keystore_import_failed, keystoreError(error)))
            }
        }
    }

    /**
     * Shared password prompt for both directions. The positive button is
     * re-bound so a short password keeps the dialog open with an inline
     * error instead of closing and losing the typed text.
     */
    private fun promptPassword(titleRes: Int, onConfirm: (CharArray) -> Unit) {
        val dialogBinding = DialogKeystorePasswordBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = dialogBinding.passwordInput.text?.toString().orEmpty()
                if (typed.length < KeystoreManager.MIN_PASSWORD_LENGTH) {
                    dialogBinding.passwordLayout.error = getString(R.string.keystore_error_short)
                    return@setOnClickListener
                }
                dialogBinding.passwordLayout.error = null
                dialog.dismiss()
                onConfirm(typed.toCharArray())
            }
        }
        dialog.show()
    }

    /** Maps the manager's error tokens to user-facing copy. */
    private fun keystoreError(error: Throwable): String = when (error.message) {
        "PASSWORD_TOO_SHORT" -> getString(R.string.keystore_error_short)
        "INVALID_PASSWORD_OR_FILE" -> getString(R.string.keystore_error_wrong)
        "NO_PRIVATE_KEY", "NO_CERTIFICATE" -> getString(R.string.keystore_error_nokey)
        else -> getString(R.string.keystore_error_unknown)
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS_NAME = "openclone_settings"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_COMPAT = "compat_mode"
        const val KEY_AUTO_SIGN = "auto_sign"

        /** Mirrors the folder created by [CloneEngine]. */
        const val CLONES_DIR = "clones"

        const val MIME_PKCS12 = "application/x-pkcs12"
    }
}
