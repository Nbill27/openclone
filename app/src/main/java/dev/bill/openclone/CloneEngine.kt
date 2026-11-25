package dev.bill.openclone

import android.content.Context
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.ResXmlElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * The actual cloning work, in plain steps:
 *
 * 1. Copy the original APK (base + split APKs, if any) into a temp dir.
 * 2. Merge split APKs into one module with ARSCLib's ApkBundle.
 *    Single-APK apps skip the merge and load the base file directly.
 * 3. Rewrite the package name in the manifest and the resource table
 *    (ApkModule.setPackageName does both), then rename the identifiers
 *    that Android requires to be unique per installed app: declared
 *    permissions, provider authorities, and any component permission or
 *    process name that still carries the old package.
 * 4. Replace the launcher icon files with the user-picked image, when one
 *    was given. Only raster files (.png/.webp/.jpg) are replaced, adaptive
 *    icon XML is left alone.
 * 5. Write the patched APK with resources.arsc stored uncompressed, so
 *    Android 11+ accepts it, dropping the old META-INF/ signatures only.
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
     * readable status lines before every heavy step.
     *
     * This is a [kotlinx.coroutines.suspend] function: it is cancellable at
     * the checkpoints below, must be called from a background dispatcher
     * (the work is CPU and disk bound), and the callback may hop to the main
     * thread itself. Keeping the callback suspending means the caller does
     * not have to fire-and-forget a separate coroutine per progress line.
     */
    suspend fun clone(
        app: InstalledApp,
        config: CloneConfig,
        onProgress: suspend (String) -> Unit,
    ): CloneResult {
        require(isValidPackageName(config.newPackage)) {
            "Package name tidak valid: ${config.newPackage}"
        }
        require(config.newLabel.isNotBlank()) { "Nama clone tidak boleh kosong" }

        val workDir = File(context.cacheDir, "clone-${System.currentTimeMillis()}")
        val outDir = File(context.filesDir, "clones").apply { mkdirs() }
        val unsignedFile = File(workDir, "unsigned.apk")
        val finalFile = File(outDir, "${config.newPackage}.apk")
        val tempSignedFile = File(outDir, "${config.newPackage}.apk.tmp")
        var module: ApkModule? = null
        try {
            onProgress("Menyalin APK asli...")
            ensureActive()
            workDir.mkdirs()
            val apkDir = File(workDir, "apk").apply { mkdirs() }
            copyApkFiles(app, apkDir)

            onProgress("Membaca dan menggabungkan APK...")
            ensureActive()
            val loaded = loadModule(apkDir)
            module = loaded
            val oldPackage = loaded.packageName
                ?: throw IllegalStateException("Tidak bisa membaca package name dari APK")
            val compatNotes = collectCompatNotes(loaded, app)

            onProgress("Mengganti package name...")
            ensureActive()
            loaded.setPackageName(config.newPackage)
            rewriteManifestReferences(loaded, oldPackage, config.newPackage)

            onProgress("Mengganti nama aplikasi...")
            ensureActive()
            loaded.androidManifest.setApplicationLabel(config.newLabel)

            if (config.iconBytes != null) {
                onProgress("Mengganti ikon...")
                ensureActive()
                replaceIcon(loaded, config.iconBytes)
            }

            onProgress("Menulis APK baru...")
            ensureActive()
            // Optional identity step: drop the AD_ID permission so the clone
            // cannot be linked to the original install by its advertising ID.
            if (config.stripAdId) {
                stripAdvertisingId(loaded)
            }
            // The old signatures are invalid once the manifest changed.
            // Drop only the signature files: META-INF/services and
            // *.kotlin_module must stay, ServiceLoader and Kotlin need them.
            stripSignatureEntries(loaded)
            // Android 11+ refuses a compressed or unaligned resources.arsc.
            // ARSCLib aligns the entry but does not force it to STORED.
            loaded.zipEntryMap.getInputSource(RESOURCES_ARSC)?.isUncompressed = true
            unsignedFile.parentFile?.mkdirs()
            loaded.writeApk(unsignedFile)
            ensureActive()

            onProgress("Menandatangani APK...")
            ensureActive()
            // Sign into a temp file and swap it in, so a crash mid-sign never
            // leaves a truncated APK where a previous good clone used to be.
            tempSignedFile.delete()
            signApk(unsignedFile, tempSignedFile)
            ensureActive()
            publishSignedApk(tempSignedFile, finalFile)

            return CloneResult(
                originalPackage = app.packageName,
                newPackage = config.newPackage,
                newLabel = config.newLabel,
                outputFileName = finalFile.name,
                outputFileSize = finalFile.length(),
                hadSplits = app.splitCount > 0,
                compatNotes = compatNotes,
                identityNotes = buildIdentityNotes(config),
            )
        } finally {
            try {
                module?.destroy()
            } catch (_: Exception) {
                // Nothing useful to do on the cleanup path.
            }
            workDir.deleteRecursively()
            if (unsignedFile.exists()) unsignedFile.delete()
            if (tempSignedFile.exists()) tempSignedFile.delete()
        }
    }

    private suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
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
        require(files.isNotEmpty()) { "Tidak ada file APK untuk diproses" }
        return if (files.size == 1) {
            ApkModule.loadApkFile(files.first())
        } else {
            val bundle = ApkBundle()
            bundle.loadApkDirectory(apkDir)
            bundle.mergeModules()
        }
    }

    /**
     * Reads the source manifest for the handful of declarations that decide
     * whether a re-signed, re-packaged copy can still work. Every hit becomes
     * a note on the result screen: none of these can be repaired at manifest
     * level, and pretending otherwise would just hide a broken clone.
     */
    private fun collectCompatNotes(module: ApkModule, app: InstalledApp): List<String> {
        val notes = mutableListOf<String>()
        val manifest = try {
            module.androidManifest
        } catch (_: Exception) {
            null
        }
        if (manifest != null) {
            val root = try {
                manifest.manifestElement
            } catch (_: Exception) {
                null
            }
            if (root != null && attributeValue(root, ATTR_SHARED_USER_ID) != null) {
                notes += context.getString(R.string.compat_shared_user_id)
            }
            if (declaresSignaturePermission(manifest)) {
                notes += context.getString(R.string.compat_signature_permission)
            }
            val extractNativeLibs = try {
                manifest.isExtractNativeLibs()
            } catch (_: Exception) {
                null
            }
            if (extractNativeLibs == false) {
                notes += context.getString(R.string.compat_extract_native_libs)
            }
        }
        if (app.splitCount > 0) {
            notes += context.getString(R.string.compat_splits_merged)
        }
        return notes
    }

    /** True when the app declares a permission protected by its own signature. */
    private fun declaresSignaturePermission(manifest: AndroidManifestBlock): Boolean {
        val elements = try {
            manifest.recursiveElements()
        } catch (_: Exception) {
            return false
        }
        while (elements.hasNext()) {
            val element = try {
                elements.next()
            } catch (_: Exception) {
                null
            } ?: continue
            val elementName = try {
                element.name
            } catch (_: Exception) {
                null
            } ?: continue
            if (elementName != ELEMENT_PERMISSION) continue
            val protectionLevel = attributeValue(element, ATTR_PROTECTION_LEVEL) ?: continue
            if (protectionLevel.split('|').any { it.trim() == PROTECTION_SIGNATURE }) {
                return true
            }
        }
        return false
    }

    private fun attributeValue(element: ResXmlElement, attributeName: String): String? {
        val attributes = try {
            element.getAttributes()
        } catch (_: Exception) {
            return null
        }
        while (attributes.hasNext()) {
            val attr = try {
                attributes.next()
            } catch (_: Exception) {
                null
            } ?: continue
            val name = try {
                attr.name
            } catch (_: Exception) {
                null
            } ?: continue
            if (name != attributeName) continue
            return try {
                attr.valueAsString
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Drops every `uses-permission` declaration for the advertising ID.
     * On Android 13+ (targetSdk 33+) an app without this permission receives a
     * zeroed advertising ID, which is the only part of the ad identity that is
     * controllable per app without hooking the framework.
     */
    private fun stripAdvertisingId(module: ApkModule) {
        val manifest = try {
            module.androidManifest
        } catch (_: Exception) {
            return
        }
        try {
            manifest.removeElementsIf { element ->
                val elementName = try {
                    element.name
                } catch (_: Exception) {
                    null
                }
                elementName in USES_PERMISSION_ELEMENTS &&
                    attributeValue(element, ATTR_NAME) == AD_ID_PERMISSION
            }
        } catch (_: Exception) {
            // Leaving the permission in place keeps the clone installable.
        }
    }

    /**
     * What the user can actually verify after installing. Deliberately factual:
     * the Android ID already differs (it is derived from the signing key), the
     * advertising ID only differs when the permission was stripped, and the
     * device / network identity cannot be changed per app at all.
     */
    private fun buildIdentityNotes(config: CloneConfig): List<String> = listOf(
        context.getString(R.string.identity_android_id),
        context.getString(
            if (config.stripAdId) R.string.identity_ad_id_stripped
            else R.string.identity_ad_id_kept,
        ),
        context.getString(R.string.identity_device_profile),
        context.getString(R.string.identity_network),
    )

    /**
     * Package name alone is not enough for two copies of an app to coexist.
     * Android also requires unique declared permissions, unique provider
     * authorities, and component permissions that point at the declared
     * ones. Every one of those still carries the old package at this point.
     */
    private fun rewriteManifestReferences(
        module: ApkModule,
        oldPackage: String,
        newPackage: String,
    ) {
        val manifest = try {
            module.androidManifest
        } catch (_: Exception) {
            null
        } ?: return

        val elements = manifest.recursiveElements()
        while (elements.hasNext()) {
            val element = elements.next()
            val elementName = try {
                element.name
            } catch (_: Exception) {
                null
            } ?: continue
            if (elementName in DECLARED_PERMISSION_ELEMENTS) {
                renameDeclaredPermission(element, oldPackage, newPackage)
            }
            rewriteElementAttributes(element, elementName, oldPackage, newPackage)
        }
    }

    private fun renameDeclaredPermission(
        element: ResXmlElement,
        oldPackage: String,
        newPackage: String,
    ) {
        val attributes = element.getAttributes()
        while (attributes.hasNext()) {
            val attr = attributes.next()
            val attrName = try {
                attr.name
            } catch (_: Exception) {
                null
            } ?: continue
            if (attrName != ATTR_NAME) continue
            val value = try {
                attr.valueAsString
            } catch (_: Exception) {
                null
            } ?: continue
            val renamed = ManifestNaming.renamePermissionName(
                value,
                oldPackage,
                newPackage,
                mustBeOwned = false,
            ) ?: continue
            attr.setValueAsString(renamed)
        }
    }

    private fun rewriteElementAttributes(
        element: ResXmlElement,
        elementName: String,
        oldPackage: String,
        newPackage: String,
    ) {
        val attributes = element.getAttributes()
        while (attributes.hasNext()) {
            val attr = attributes.next()
            val attrName = try {
                attr.name
            } catch (_: Exception) {
                null
            } ?: continue
            val value = try {
                attr.valueAsString
            } catch (_: Exception) {
                null
            } ?: continue
            when {
                attrName == ATTR_AUTHORITIES -> {
                    attr.setValueAsString(
                        ManifestNaming.renameAuthorities(value, oldPackage, newPackage),
                    )
                }
                attrName == ATTR_PROCESS -> {
                    ManifestNaming.renameProcess(value, oldPackage, newPackage)
                        ?.let { attr.setValueAsString(it) }
                }
                attrName in COMPONENT_PERMISSION_ATTRS ||
                    (attrName == ATTR_NAME && elementName in USES_PERMISSION_ELEMENTS) -> {
                    ManifestNaming.renamePermissionName(
                        value,
                        oldPackage,
                        newPackage,
                        mustBeOwned = true,
                    )?.let { attr.setValueAsString(it) }
                }
            }
        }
    }

    private fun stripSignatureEntries(module: ApkModule) {
        val archive = module.zipEntryMap
        val signatures = archive.listInputSources()
            .map { it.name }
            .filter { ManifestNaming.isSignatureEntry(it) }
        signatures.forEach { name ->
            try {
                archive.remove(name)
            } catch (_: Exception) {
                // The signer rewrites the rest; a single failure is not fatal.
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

    private fun publishSignedApk(tempFile: File, finalFile: File) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw IllegalStateException("Signing gagal, file hasil kosong")
        }
        if (tempFile.renameTo(finalFile)) return
        // renameTo can fail when the destination exists on some file systems.
        tempFile.copyTo(finalFile, overwrite = true)
        tempFile.delete()
    }

    private fun signApk(unsignedFile: File, outputFile: File) {
        val key = KeystoreManager(context).getOrCreateKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "openclone",
            key.privateKey,
            listOf(key.certificate),
        ).build()
        val signer = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedFile)
            .setOutputApk(outputFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
        signer.sign()
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("Signing gagal, file hasil kosong")
        }
    }

    companion object {
        private const val RESOURCES_ARSC = "resources.arsc"
        private const val ATTR_NAME = "name"
        private const val ATTR_AUTHORITIES = "authorities"
        private const val ATTR_PROCESS = "process"
        private const val ATTR_SHARED_USER_ID = "sharedUserId"
        private const val ATTR_PROTECTION_LEVEL = "protectionLevel"
        private const val ELEMENT_PERMISSION = "permission"
        private const val PROTECTION_SIGNATURE = "signature"
        private const val AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID"

        /** Elements that declare a permission, so their name must be unique. */
        private val DECLARED_PERMISSION_ELEMENTS = setOf(
            "permission",
            "permission-tree",
            "permission-group",
        )

        /** Elements that consume a permission, possibly the app's own. */
        private val USES_PERMISSION_ELEMENTS = setOf(
            "uses-permission",
            "uses-permission-sdk-23",
        )

        /** Component attributes that point at a permission by name. */
        private val COMPONENT_PERMISSION_ATTRS = setOf(
            "permission",
            "readPermission",
            "writePermission",
        )
    }
}
