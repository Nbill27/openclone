package dev.bill.openclone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Manifest-registered receiver for [PackageInstaller.Session.commit].
 *
 * Notifies the active [Listener] immediately on `STATUS_PENDING_USER_ACTION`,
 * `STATUS_SUCCESS`, `STATUS_FAILURE_ABORTED`, and failure states so staging
 * dialogs dismiss cleanly and batch install rows update live on screen.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    interface Listener {
        fun onPendingUserAction(targetPackage: String)
        fun onInstallTerminalStatus(targetPackage: String, status: Int)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val rawMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            .orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                activeListener?.get()?.onPendingUserAction(targetPackage)
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    val host = activeHost?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    try {
                        if (host != null) {
                            host.startActivity(confirm)
                        } else {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(confirm)
                        }
                    } catch (e: Exception) {
                        activeListener?.get()?.onInstallTerminalStatus(targetPackage, PackageInstaller.STATUS_FAILURE)
                        Toast.makeText(
                            context.applicationContext,
                            context.getString(
                                R.string.install_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    activeListener?.get()?.onInstallTerminalStatus(targetPackage, PackageInstaller.STATUS_FAILURE)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppRepository.invalidate()
                AppIcons.evict(targetPackage)
                activeListener?.get()?.onInstallTerminalStatus(targetPackage, status)
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.install_success),
                    Toast.LENGTH_LONG,
                ).show()
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                activeListener?.get()?.onInstallTerminalStatus(targetPackage, status)
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.install_aborted),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            else -> {
                activeListener?.get()?.onInstallTerminalStatus(targetPackage, status)
                val friendly = ApkInstaller.readableInstallFailure(context, status, rawMessage)
                val fullText = context.getString(R.string.install_failed, friendly)
                val host = activeHost?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                if (host != null) {
                    ApkInstaller.showFailureDialog(host, fullText)
                } else {
                    Toast.makeText(
                        context.applicationContext,
                        fullText,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "dev.bill.openclone.extra.TARGET_PACKAGE"

        @Volatile
        private var activeHost: WeakReference<Activity>? = null

        @Volatile
        private var activeListener: WeakReference<Listener>? = null

        fun attachHost(activity: Activity, listener: Listener? = null) {
            activeHost = WeakReference(activity)
            if (listener != null) {
                activeListener = WeakReference(listener)
            }
        }

        fun detachHost(activity: Activity) {
            if (activeHost?.get() === activity) {
                activeHost = null
                activeListener = null
            }
        }
    }
}
