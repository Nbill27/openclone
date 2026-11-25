package dev.bill.openclone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.bill.openclone.databinding.ActivityAboutBinding

/**
 * Layar 8 "Tentang" (DESAIN.md section 4): brand mark, version pill, and the
 * GitHub / license / credits links.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.versionLabel.text = getString(R.string.version_label_format, versionName())

        binding.githubRow.setOnClickListener { openRepository() }
        binding.licenseRow.setOnClickListener {
            showTextDialog(R.string.about_license, R.string.about_license_body)
        }
        binding.creditsRow.setOnClickListener {
            showTextDialog(R.string.about_credits, R.string.about_credits_body)
        }
    }

    private fun versionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            getString(R.string.version_label)
        }

    private fun openRepository() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.about_link_unavailable))
        }
    }

    private fun showTextDialog(titleRes: Int, bodyRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REPOSITORY_URL = "https://github.com/Nbill27/openclone"
    }
}
