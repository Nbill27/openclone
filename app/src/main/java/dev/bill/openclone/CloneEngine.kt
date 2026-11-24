package dev.bill.openclone

import android.content.Context
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.io.File
import java.util.zip.ZipEntry

/**
 * The actual cloning work, in plain steps:
 *
 * 1. Copy the original APK (base + split APKs, if any) into a temp dir.
 * 2. Merge split APKs into one module with ARSCLib's ApkBundle.
 *    Single-APK apps skip the merge and load the base file directly.
 * 3. Rewrite the package name in the manifest and the resource table
 *    (ApkModule.setPackageName does both), rewrite provider authorities
 *    so they do not collide with the original app, and set the new
 *    launcher label as a literal string.
 * 4. Replace the launcher icon files with the user-picked image, when one
 *    was given. Only raster files (.png/.webp/.jpg) are replaced, adaptive
 *    icon XML is left alone.
 * 5. Write the patched APK, dropping the old META-INF/ signatures.
 * 6. Sign the result with the one persistent OpenClone key
 *    (see KeystoreManager) using apksig, v1 + v2 + v3.
 *
 * What this does NOT do, on purpose: it does not touch DEX code, it does
 * not remove signature checks inside the app, and it cannot make an app
 * that pins its own signing certificate (banking apps, many games,
 * Play Integrity) accept a re-signed copy. Those apps will refuse to run
 * or misbehave, and no manifest-level clone can fix that.
 */
class CloneEngine(private val context: Context) {

    /**
     * Clone [app] using [config]. Calls [onProgress] with short human
     * readable status lines. Runs on the caller's thread, call it from
     * a background coroutine.
     */
    fun clone(
        app: InstalledApp,
        config: CloneConfig,
        onProgress: (String) -> Unit,
    ): CloneResult {
        require(isValidPackageName(config.newPackage)) {
            "Package name tidak valid: ${config.newPackage}"
        }
        require(config.newLabel.isNotBlank()) { "Nama clone tidak boleh kosong" }

        val workDir = File(context.cacheDir, "clone-${System.currentTimeMillis()}")
        val outDir = File(context.filesDir, "clones").apply { mkdirs() }
        val unsignedFile = File(workDir, "unsigned.apk")
        val signedFile = File(outDir, "${config.newPackage}.apk")
        try {
            onProgress("Menyalin APK asli...")
            workDir.mkdirs()
            val apkDir = File(workDir, "apk").apply { mkdirs() }
            copyApkFiles(app, apkDir)

            onProgress("Membaca dan menggabungkan APK...")
            val module = loadModule(apkDir)
            val oldPackage = module.packageName
                ?: throw IllegalStateException("Tidak bisa membaca package name dari APK")

            onProgress("Mengganti package name...")
            module.setPackageName(config.newPackage)
            rewriteAuthorities(module, oldPackage, config.newPackage)

            onProgress("Mengganti nama aplikasi...")
            module.androidManifest.setApplicationLabel(config.newLabel)

            if (config.iconBytes != null) {
                onProgress("Mengganti ikon...")
                replaceIcon(module, config.iconBytes)
            }

            onProgress("Menulis APK baru...")
            // The old signatures are invalid once the manifest changed.
            // Drop them, apksig will write fresh v1/v2/v3 signatures.
            try {
                module.zipEntryMap.removeDir("META-INF")
            } catch (_: Exception) {
                // Keep going, the signer will overwrite what it can.
            }
            unsignedFile.parentFile?.mkdirs()
            module.writeApk(unsignedFile)
            module.destroy()

            onProgress("Menandatangani APK...")
            signApk(unsignedFile, signedFile)

            return CloneResult(
                originalPackage = app.packageName,
                newPackage = config.newPackage,
                newLabel = config.newLabel,
                outputFileName = signedFile.name,
                outputFileSize = signedFile.length(),
                hadSplits = app.splitCount > 0,
            )
        } finally {
            workDir.deleteRecursively()
            if (unsignedFile.exists()) unsignedFile.delete()
        }
    }

    private fun copyApkFiles(app: InstalledApp, dir: File) {
        val base = File(app.sourceDir)
        require(base.exists()) { "File APK asli tidak ditemukan: ${app.sourceDir}" }
        base.copyTo(File(dir, "base.apk"), overwrite = true)
        app.splitSourceDirs.forEachIndexed { index, path ->
            val split = File(path)
            if (split.exists()) {
                split.copyTo(File(dir, "split-$index.apk"), overwrite = true)
            }
        }
    }

    private fun loadModule(apkDir: File): ApkModule {
        val files = apkDir.listFiles()?.filter { it.name.endsWith(".apk") }.orEmpty()
        return if (files.size <= 1) {
            ApkModule.loadApkFile(files.first())
        } else {
            val bundle = ApkBundle()
            bundle.loadApkDirectory(apkDir)
            bundle.mergeModules()
        }
    }

    /**
     * Content providers are addressed by authority, and Android forbids
     * two installed apps sharing one. Rewrite every authority that
     * contains the old package name.
     */
    private fun rewriteAuthorities(module: ApkModule, oldPackage: String, newPackage: String) {
        val manifest = module.androidManifest ?: return
        val iterator = manifest.recursiveAttributes()
        while (iterator.hasNext()) {
            val attr = iterator.next()
            val name = try {
                attr.name
            } catch (_: Exception) {
                continue
            }
            if (name != "authorities") continue
            val value = try {
                attr.valueAsString
            } catch (_: Exception) {
                null
            } ?: continue
            if (oldPackage in value) {
                attr.setValueAsString(value.replace(oldPackage, newPackage))
            }
        }
    }

    private fun replaceIcon(module: ApkModule, iconBytes: ByteArray) {
        val manifest = module.androidManifest ?: return
        val iconId = try {
            manifest.iconResourceId
        } catch (_: Exception) {
            0
        }
        if (iconId == 0) return
        val resFiles = try {
            module.listResFiles(iconId, null)
        } catch (_: Exception) {
            return
        }
        for (resFile in resFiles) {
            val path = try {
                resFile.filePath
            } catch (_: Exception) {
                continue
            }
            if (!path.endsWith(".png", ignoreCase = true) &&
                !path.endsWith(".webp", ignoreCase = true) &&
                !path.endsWith(".jpg", ignoreCase = true) &&
                !path.endsWith(".jpeg", ignoreCase = true)
            ) {
                continue
            }
            val original = module.getInputSource(path) ?: continue
            val replacement = ByteInputSource(iconBytes, path)
            replacement.copyAttributes(original)
            replacement.method = original.method
            replacement.isUncompressed = original.isUncompressed
            module.removeInputSource(path)
            module.add(replacement)
        }
    }

    private fun signApk(unsignedFile: File, signedFile: File) {
        val key = KeystoreManager(context).getOrCreateKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "openclone",
            key.privateKey,
            listOf(key.certificate),
        ).build()
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedFile)
            .setOutputApk(signedFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
        signer.sign()
        if (!signedFile.exists() || signedFile.length() == 0L) {
            throw IllegalStateException("Signing gagal, file hasil kosong")
        }
    }

    companion object {
        /** Keep the old signature files out, they are invalid after patching. */
        @Suppress("unused")
        private val SIGNATURE_ENTRIES = setOf("META-INF/")
    }
}
