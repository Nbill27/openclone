package dev.bill.openclone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.android.apksig.ApkSigner
import com.reandroid.apk.AndroidManifestBlockMerger
import com.reandroid.apk.AndroidManifestBlockSplitSanitizer
import com.reandroid.apk.ApkModule
import com.reandroid.apk.DexFileInputSource
import com.reandroid.archive.BlockInputSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.writer.ZipAligner
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * High-speed APK cloning pipeline:
 *
 * 1. Open source base + split APKs directly read-only (zero temp copy) with
 *    default framework loading disabled so ARSCLib never unpacks a 30 MB
 *    Android framework table on the phone.
 * 2. Merge splits directly into `base.apk`'s `ApkModule` so `base.apk`'s
 *    `TableBlock` (`TypeStringPool`, spec-only `@id`/`@drawable` types,
 *    `typeIdOffset`, and `PackageBlock` metadata) is 100% preserved while
 *    keeping raw uncompressed `lib/` `.so` and `classes*.dex` entries zero-copy.
 * 3. Match manifest attributes by integer resource ID (`0x0101xxxx`) FIRST
 *    before resolving any `@string/...` reference, strip `android:splitName`
 *    (`0x01010549`) & `<dist:module>`, and normalize `0x80xxxxxx` feature-split
 *    attributes so Android's unsigned `BackTrackingAttributeFinder` never fails.
 * 4. Rewrite package identity, declared permissions (including vendor names),
 *    `<application>` provider authorities (skipping `<queries>`), processes,
 *    task affinities, launcher labels/icons, and `AccountAuthenticator` XMLs.
 * 5. In compatibility mode, patch vendor authorities, `content://` URIs,
 *    custom permissions, and `accountType` literals directly in-place in
 *    `classes*.dex` via `O(log N)` binary search (~10 ms per 10 MB DEX).
 * 6. Write with `resources.arsc` uncompressed + 16 KB `.so` alignment, then
 *    sign with V2 + V3 (and V1 only when `minSdkVersion < 24`).
 */
class CloneEngine(private val context: Context) {

    internal data class LoadedBundle(
        val module: ApkModule,
        val splitModules: List<ApkModule> = emptyList(),
        val mergedSplitNames: Set<String> = emptySet(),
        val hasSplits: Boolean = false,
    )

    internal data class RewriteSummary(
        val renamedAuthorities: Map<String, String>,
        val renamedPermissions: Map<String, String>,
        val renamedAccountTypes: Map<String, String>,
    )

    suspend fun clone(
        app: InstalledApp,
        config: CloneConfig,
        onProgress: suspend (String) -> Unit,
    ): CloneResult {
        require(isValidPackageName(config.newPackage)) {
            "Package name tidak valid: ${config.newPackage}"
        }
        require(config.newLabel.isNotBlank()) { "Nama clone tidak boleh kosong" }

        val workDir = File(context.cacheDir, "clone-${System.currentTimeMillis()}").apply { mkdirs() }
        val outDir = File(context.filesDir, "clones").apply { mkdirs() }
        val unsignedFile = File(workDir, "unsigned.apk")
        val finalFile = File(outDir, "${config.newPackage}.apk")
        val tempSignedFile = File(outDir, "${config.newPackage}.apk.tmp")
        var loadedBundle: LoadedBundle? = null
        try {
            onProgress("Membaca dan menggabungkan APK...")
            ensureActive()
            val pair = loadModuleFast(app, workDir)
            loadedBundle = pair
            val loaded = pair.module
            val oldPackage = loaded.packageName
                ?: throw IllegalStateException("Tidak bisa membaca package name dari APK")
            val compatNotes = collectCompatNotes(loaded, app, config)

            onProgress("Mengganti package name...")
            ensureActive()
            val summary = rewritePackageAndManifest(
                module = loaded,
                oldPackage = oldPackage,
                newPackage = config.newPackage,
                compatMode = config.compatMode,
                mergedSplitNames = pair.mergedSplitNames,
                hasSplits = pair.hasSplits,
            )

            onProgress("Mengganti nama aplikasi...")
            ensureActive()
            applyCloneLabel(loaded, config.newLabel)

            if (config.iconBytes != null) {
                onProgress("Mengganti ikon...")
                ensureActive()
                val normalizedIcon = normalizePngBytes(config.iconBytes)
                replaceIcon(loaded, normalizedIcon)
                runCatching {
                    File(outDir, "${config.newPackage}.png").writeBytes(normalizedIcon)
                    AppIcons.evict(config.newPackage)
                }
            }

            if (config.stripAdId) {
                stripAdvertisingId(loaded)
            }

            if (config.compatMode) {
                ensureActive()
                val rules = DexPatcher.buildRules(
                    renamedAuthorities = summary.renamedAuthorities,
                    renamedPermissions = summary.renamedPermissions,
                    renamedAccountTypes = summary.renamedAccountTypes,
                )
                if (!rules.isEmpty) {
                    DexPatcher.patchModule(loaded, rules) { current, total ->
                        ensureActive()
                        onProgress("Menyesuaikan referensi DEX ($current/$total)...")
                    }
                }
            }

            val manifestMinSdk = runCatching { loaded.androidManifest?.minSdkVersion }.getOrNull()

            onProgress("Menulis APK baru...")
            ensureActive()
            writeUnsignedApk(loaded, unsignedFile)
            ensureActive()

            onProgress("Menandatangani APK...")
            ensureActive()
            tempSignedFile.delete()
            signApk(unsignedFile, tempSignedFile, manifestMinSdk)
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
            runCatching { loadedBundle?.module?.destroy() }
            loadedBundle?.splitModules?.forEach { split ->
                runCatching { split.destroy() }
            }
            workDir.deleteRecursively()
            if (unsignedFile.exists()) unsignedFile.delete()
            if (tempSignedFile.exists()) tempSignedFile.delete()
        }
    }

    private suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }

    /**
     * Opens the installed APK files directly read-only to avoid copying 200+ MB
     * into cache storage first; falls back to copying only if direct open fails.
     */
    private fun loadModuleFast(app: InstalledApp, workDir: File): LoadedBundle {
        val base = File(app.sourceDir)
        require(base.exists()) { "File APK asli tidak ditemukan: ${app.sourceDir}" }
        val splits = app.splitSourceDirs.map { File(it) }.filter { it.exists() }
        return try {
            loadFromFiles(listOf(base) + splits)
        } catch (_: Exception) {
            val apkDir = File(workDir, "apk").apply { mkdirs() }
            val copied = ArrayList<File>(1 + splits.size)
            val baseCopy = File(apkDir, "base.apk")
            base.copyTo(baseCopy, overwrite = true)
            copied += baseCopy
            splits.forEachIndexed { idx, split ->
                val splitCopy = File(apkDir, "split-$idx.apk")
                split.copyTo(splitCopy, overwrite = true)
                copied += splitCopy
            }
            loadFromFiles(copied)
        }
    }

    private fun collectCompatNotes(
        module: ApkModule,
        app: InstalledApp,
        config: CloneConfig,
    ): List<String> {
        val notes = mutableListOf<String>()
        val manifest = runCatching { module.androidManifest }.getOrNull()
        if (manifest != null) {
            val root = runCatching { manifest.manifestElement }.getOrNull()
            if (root != null && findAttr(root, ID_SHARED_USER_ID, ATTR_SHARED_USER_ID) != null) {
                notes += context.getString(R.string.compat_shared_user_id)
            }
            if (declaresExternalSignatureDependency(manifest)) {
                notes += context.getString(R.string.compat_signature_permission)
            }
            val extractNativeLibs = runCatching { manifest.isExtractNativeLibs() }.getOrNull()
            if (extractNativeLibs == false && !config.compatMode) {
                notes += context.getString(R.string.compat_extract_native_libs)
            }
        }
        if (app.splitCount > 0) {
            notes += context.getString(R.string.compat_splits_merged)
        }
        return notes
    }

    private fun declaresExternalSignatureDependency(manifest: AndroidManifestBlock): Boolean {
        val elements = runCatching { manifest.recursiveElements() }.getOrNull() ?: return false
        while (elements.hasNext()) {
            val element = runCatching { elements.next() }.getOrNull() ?: continue
            val elementName = runCatching { element.name }.getOrNull() ?: continue
            if (elementName != ELEMENT_PERMISSION) continue
            val attr = findAttr(element, ID_PROTECTION_LEVEL, ATTR_PROTECTION_LEVEL) ?: continue
            val raw = runCatching { attr.valueAsString }.getOrNull()
            val intFlags = runCatching { attr.data }.getOrDefault(0)
            if (raw != null && raw.split('|').any { it.trim() == PROTECTION_SIGNATURE }) {
                return true
            }
            if ((intFlags and PROTECTION_FLAG_SIGNATURE) == PROTECTION_FLAG_SIGNATURE) {
                return true
            }
        }
        return false
    }

    private fun buildIdentityNotes(config: CloneConfig): List<String> = listOf(
        context.getString(R.string.identity_android_id),
        context.getString(
            if (config.stripAdId) R.string.identity_ad_id_stripped
            else R.string.identity_ad_id_kept,
        ),
        context.getString(R.string.identity_device_profile),
        context.getString(R.string.identity_network),
    )

    private fun publishSignedApk(tempFile: File, finalFile: File) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw IllegalStateException("Signing gagal, file hasil kosong")
        }
        if (tempFile.renameTo(finalFile)) return
        tempFile.copyTo(finalFile, overwrite = true)
        tempFile.delete()
    }

    private fun signApk(unsignedFile: File, outputFile: File, manifestMinSdk: Int?) {
        val key = KeystoreManager(context).getOrCreateKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "openclone",
            key.privateKey,
            listOf(key.certificate),
        ).build()
        // Android 7.0+ (API 24+) verifies V2/V3 directly and ignores V1 JAR
        // manifests. Skipping V1 when minSdk >= 24 avoids generating 10+ MB
        // META-INF/MANIFEST.MF files across 40,000+ entries on apps like Facebook.
        val enableV1 = manifestMinSdk == null || manifestMinSdk < 24
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedFile)
            .setOutputApk(outputFile)
            .setV1SigningEnabled(enableV1)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("Signing gagal, file hasil kosong")
        }
    }

    private fun normalizePngBytes(rawBytes: ByteArray): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return rawBytes
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_ICON_PX &&
                bounds.outHeight / (sample * 2) >= MAX_ICON_PX
            ) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(
                rawBytes,
                0,
                rawBytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return rawBytes
            val scaled = if (decoded.width > MAX_ICON_PX || decoded.height > MAX_ICON_PX) {
                Bitmap.createScaledBitmap(decoded, MAX_ICON_PX, MAX_ICON_PX, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            val out = ByteArrayOutputStream()
            val ok = scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            scaled.recycle()
            if (ok && out.size() > 0) out.toByteArray() else rawBytes
        } catch (_: Throwable) {
            rawBytes
        }
    }

    companion object {
        internal const val RESOURCES_ARSC = "resources.arsc"

        internal const val ID_LABEL = 0x01010001
        internal const val ID_ICON = 0x01010002
        internal const val ID_NAME = 0x01010003
        internal const val ID_MANAGE_SPACE_ACTIVITY = 0x01010004
        internal const val ID_PERMISSION = 0x01010006
        internal const val ID_READ_PERMISSION = 0x01010007
        internal const val ID_WRITE_PERMISSION = 0x01010008
        internal const val ID_PROTECTION_LEVEL = 0x01010009
        internal const val ID_PERMISSION_GROUP = 0x0101000a
        internal const val ID_SHARED_USER_ID = 0x0101000b
        internal const val ID_ENABLED = 0x0101000e
        internal const val ID_PROCESS = 0x01010011
        internal const val ID_TASK_AFFINITY = 0x01010012
        internal const val ID_AUTHORITIES = 0x01010018
        internal const val ID_VALUE = 0x01010024
        internal const val ID_RESOURCE = 0x01010025
        internal const val ID_TARGET_ACTIVITY = 0x01010202
        internal const val ID_SHARED_USER_LABEL = 0x01010261
        internal const val ID_BACKUP_AGENT = 0x0101027f
        internal const val ID_ACCOUNT_TYPE = 0x0101028f
        internal const val ID_CONTENT_AUTHORITY = 0x01010290
        internal const val ID_PARENT_ACTIVITY_NAME = 0x010103a7
        internal const val ID_ROUND_ICON = 0x0101052c
        internal const val ID_SPLIT_NAME = 0x01010549
        internal const val ID_ISOLATED_SPLITS = 0x0101054b
        internal const val ID_IS_FEATURE_SPLIT = 0x0101055b
        internal const val ID_APP_COMPONENT_FACTORY = 0x0101057a
        internal const val ID_IS_SPLIT_REQUIRED = 0x01010591
        internal const val ID_ZYGOTE_PRELOAD_NAME = 0x0101059d
        internal const val ID_USE_EMBEDDED_DEX = 0x0101059e
        internal const val ID_KNOWN_CERTS = 0x0101062a
        internal const val ID_SHARED_USER_MAX_SDK = 0x0101064d
        internal const val ID_REQUIRED_SPLIT_TYPES = 0x0101064e
        internal const val ID_SPLIT_TYPES = 0x0101064f
        internal const val ID_KNOWN_ACTIVITY_EMBEDDING_CERTS = 0x0101066a

        private const val ATTR_NAME = "name"
        private const val ATTR_ENABLED = "enabled"
        private const val ATTR_AUTHORITIES = "authorities"
        private const val ATTR_PROCESS = "process"
        private const val ATTR_TASK_AFFINITY = "taskAffinity"
        private const val ATTR_SHARED_USER_ID = "sharedUserId"
        private const val ATTR_SHARED_USER_LABEL = "sharedUserLabel"
        private const val ATTR_SHARED_USER_MAX_SDK = "sharedUserMaxSdkVersion"
        private const val ATTR_PROTECTION_LEVEL = "protectionLevel"
        private const val ATTR_KNOWN_CERTS = "knownCerts"
        private const val ATTR_KNOWN_ACTIVITY_EMBEDDING_CERTS = "knownActivityEmbeddingCerts"
        private const val ATTR_SPLIT_NAME = "splitName"
        private const val ATTR_USE_EMBEDDED_DEX = "useEmbeddedDex"
        private const val ATTR_ACCOUNT_TYPE = "accountType"
        private const val ATTR_CONTENT_AUTHORITY = "contentAuthority"
        private const val ATTR_RESOURCE = "resource"
        private const val ATTR_LABEL = "label"
        private const val ATTR_ICON = "icon"
        private const val ATTR_ROUND_ICON = "roundIcon"

        private const val ELEMENT_MANIFEST = "manifest"
        private const val ELEMENT_PERMISSION = "permission"
        private const val ELEMENT_PROVIDER = "provider"
        private const val ELEMENT_QUERIES = "queries"
        private const val ELEMENT_META_DATA = "meta-data"
        private const val ELEMENT_DIST_MODULE = "module"
        private const val DIST_NAMESPACE_URI = "http://schemas.android.com/apk/distribution"
        private const val DIST_PREFIX = "dist"

        private const val PROTECTION_SIGNATURE = "signature"
        private const val PROTECTION_KNOWN_SIGNER = "knownSigner"
        private const val PROTECTION_FLAG_SIGNATURE = 0x00000002
        private const val PROTECTION_FLAG_KNOWN_SIGNER = 0x04000000
        private const val AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID"

        private const val META_ACCOUNT_AUTHENTICATOR = "android.accounts.AccountAuthenticator"
        private const val META_SYNC_ADAPTER = "android.content.SyncAdapter"
        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        private const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"

        private const val ALIGNMENT_PAGE_16KB = 16384
        private const val MAX_ICON_PX = 512

        private data class AttrSpec(val id: Int, val name: String)

        private val COMMON_CLASS_ATTRS = listOf(AttrSpec(ID_NAME, ATTR_NAME))

        private val EXTRA_CLASS_ATTRS = mapOf(
            "application" to listOf(
                AttrSpec(ID_BACKUP_AGENT, "backupAgent"),
                AttrSpec(ID_MANAGE_SPACE_ACTIVITY, "manageSpaceActivity"),
                AttrSpec(ID_APP_COMPONENT_FACTORY, "appComponentFactory"),
                AttrSpec(ID_ZYGOTE_PRELOAD_NAME, "zygotePreloadName"),
            ),
            "activity" to listOf(
                AttrSpec(ID_PARENT_ACTIVITY_NAME, "parentActivityName"),
            ),
            "activity-alias" to listOf(
                AttrSpec(ID_TARGET_ACTIVITY, "targetActivity"),
            ),
        )

        private val CLASS_NAME_ELEMENTS = setOf(
            "application",
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider",
            "instrumentation",
        )

        private val TASK_AFFINITY_ELEMENTS = setOf(
            "application",
            "activity",
            "activity-alias",
        )

        private val DECLARED_PERMISSION_ELEMENTS = setOf(
            "permission",
            "permission-tree",
            "permission-group",
        )

        private val USES_PERMISSION_ELEMENTS = setOf(
            "uses-permission",
            "uses-permission-sdk-23",
            "uses-permission-sdk-m",
        )

        private val COMPONENT_PERMISSION_SPECS = listOf(
            AttrSpec(ID_PERMISSION, "permission"),
            AttrSpec(ID_READ_PERMISSION, "readPermission"),
            AttrSpec(ID_WRITE_PERMISSION, "writePermission"),
            AttrSpec(ID_PERMISSION_GROUP, "permissionGroup"),
        )

        private val OBSOLETE_MANIFEST_ELEMENTS = setOf(
            "uses-split",
            "original-package",
            "adopt-permissions",
            "key-sets",
        )

        private val SPLIT_ATTR_SPECS = listOf(
            AttrSpec(0, "split"),
            AttrSpec(ID_SPLIT_NAME, ATTR_SPLIT_NAME),
            AttrSpec(ID_IS_SPLIT_REQUIRED, "isSplitRequired"),
            AttrSpec(ID_ISOLATED_SPLITS, "isolatedSplits"),
            AttrSpec(ID_IS_FEATURE_SPLIT, "isFeatureSplit"),
            AttrSpec(ID_REQUIRED_SPLIT_TYPES, "requiredSplitTypes"),
            AttrSpec(ID_SPLIT_TYPES, "splitTypes"),
            AttrSpec(ID_KNOWN_ACTIVITY_EMBEDDING_CERTS, ATTR_KNOWN_ACTIVITY_EMBEDDING_CERTS),
        )

        private val SPLIT_AND_STAMP_META_NAMES = setOf(
            "com.android.vending.splits",
            "com.android.vending.splits.required",
            "com.android.stamp.source",
            "com.android.stamp.type",
            "com.android.vending.derived.apk.id",
        )

        /**
         * Loads a single APK or merges a base + split APK bundle directly into
         * `baseModule` without rebuilding `baseModule`'s `TableBlock` from scratch.
         *
         * Why we do NOT call `ApkBundle.mergeModules()`:
         * 1. `ApkBundle.mergeModules()` creates an empty `TableBlock` and calls
         *    `PackageBlock.merge(base)`, which skips spec-only resource types
         *    (like `@id` or density-stripped `@drawable` in `base.apk`) and
         *    replaces their `TypeStringPool` slots with `"type-0"`, `"type-1"`.
         * 2. `ApkBundle.getBaseModule()` requires a plain `MAIN` activity in
         *    `base.apk`, falling back to `getLargestTableModule()` which can
         *    pick a split APK instead of `base.apk`, wiping `<application>`!
         * 3. `OverlayableList.merge()` merges unrelated `<overlayable>` chunks
         *    when `actor == ""`.
         * 4. `ApkModule.mergeDexFiles()` drops `STORED` flags on split DEX files.
         */
        internal fun loadFromFiles(files: List<File>): LoadedBundle {
            require(files.isNotEmpty()) { "Tidak ada file APK untuk diproses" }
            if (files.size == 1) {
                val single = ApkModule.loadApkFile(files.first())
                single.setLoadDefaultFramework(false)
                return LoadedBundle(module = single)
            }

            val loadedModules = ArrayList<ApkModule>(files.size)
            try {
                files.forEachIndexed { idx, file ->
                    val name = if (idx == 0) "base" else "split_$idx"
                    val mod = ApkModule.loadApkFile(file, name)
                    mod.setLoadDefaultFramework(false)
                    loadedModules += mod
                }
                val baseModule = loadedModules.firstOrNull { mod ->
                    runCatching { mod.hasAndroidManifest() && !mod.androidManifest.isSplit }
                        .getOrDefault(false)
                } ?: loadedModules.first()
                val splitModules = loadedModules.filter { it !== baseModule }
                val mergedSplitNames = mergeSplitsIntoBase(baseModule, splitModules)
                return LoadedBundle(
                    module = baseModule,
                    splitModules = splitModules,
                    mergedSplitNames = mergedSplitNames,
                    hasSplits = splitModules.isNotEmpty(),
                )
            } catch (t: Throwable) {
                loadedModules.forEach { mod -> runCatching { mod.destroy() } }
                throw t
            }
        }

        private fun mergeSplitsIntoBase(
            baseModule: ApkModule,
            splitModules: List<ApkModule>,
        ): Set<String> {
            val mergedSplitNames = LinkedHashSet<String>()
            val storedPaths = collectStoredBinaryPaths(baseModule).toMutableSet()

            if (baseModule.hasTableBlock()) {
                runCatching {
                    val bt = baseModule.getTableBlock(false)
                    bt?.packageArray?.listItems()?.forEach { pkg ->
                        pkg.overlayableList.clearChildes()
                    }
                }
            }

            val baseManifest = runCatching {
                if (baseModule.hasAndroidManifest()) baseModule.androidManifest else null
            }.getOrNull()
            val manifestMerger = baseManifest?.let {
                AndroidManifestBlockMerger().apply { initializeBase(it) }
            }

            for (splitMod in splitModules) {
                runCatching { splitMod.split }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(mergedSplitNames::add)

                mergeDexFilesPreservingStored(baseModule, splitMod, storedPaths)
                mergeTablePreservingSpecs(baseModule, splitMod)
                mergeEntriesPreservingStored(baseModule, splitMod, storedPaths)

                if (splitMod.hasAndroidManifest()) {
                    val splitManifest = runCatching { splitMod.androidManifest }.getOrNull()
                    if (baseManifest != null && splitManifest != null) {
                        runCatching {
                            if (splitManifest.isFusingInclude) {
                                val fused = splitManifest.fusedModuleNames
                                if (!fused.isNullOrEmpty()) {
                                    baseManifest.addFusedModuleNames(*fused)
                                }
                                val splitId = splitManifest.split
                                if (!splitId.isNullOrEmpty()) {
                                    baseManifest.addFusedModuleNames(splitId)
                                }
                            }
                        }
                        runCatching { manifestMerger?.merge(splitManifest) }
                    }
                }
            }

            if (baseModule.hasTableBlock()) {
                runCatching {
                    val table = baseModule.getTableBlock(false)
                    table?.sortPackages()
                    table?.refresh()
                }
            }

            for (path in storedPaths) {
                baseModule.uncompressedFiles.addPath(path)
                baseModule.zipEntryMap.getInputSource(path)?.isUncompressed = true
            }
            runCatching { baseModule.zipEntryMap.autoSortApkFiles() }
            return mergedSplitNames
        }

        private fun mergeDexFilesPreservingStored(
            baseModule: ApkModule,
            splitModule: ApkModule,
            storedPaths: MutableSet<String>,
        ) {
            val comingList = runCatching { splitModule.listDexFiles() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return
            val existList = runCatching { baseModule.listDexFiles() }.getOrNull().orEmpty()
            var nextIdx = if (existList.isEmpty()) {
                0
            } else {
                val lastNum = existList.last().dexNumber
                if (lastNum == 0) 2 else lastNum + 1
            }
            for (coming in comingList) {
                val underlying = coming.inputSource
                val wasStored = coming.isUncompressed ||
                    underlying.isUncompressed ||
                    splitModule.uncompressedFiles.isUncompressed(coming.alias)
                val targetName = if (nextIdx == 0) "classes.dex" else "classes$nextIdx.dex"
                val added = DexFileInputSource(targetName, underlying)
                added.isUncompressed = wasStored
                baseModule.zipEntryMap.add(added)
                if (wasStored) {
                    storedPaths += targetName
                }
                nextIdx = if (nextIdx == 0) 2 else nextIdx + 1
            }
        }

        private fun mergeTablePreservingSpecs(baseModule: ApkModule, splitModule: ApkModule) {
            if (!splitModule.hasTableBlock()) return
            val splitTable = runCatching { splitModule.getTableBlock(false) }.getOrNull() ?: return
            if (!baseModule.hasTableBlock()) {
                baseModule.tableBlock = TableBlock()
            }
            val baseTable = runCatching { baseModule.getTableBlock(false) }.getOrNull() ?: return

            baseTable.stringPool.merge(splitTable.stringPool)
            for (comingPkg in splitTable.packageArray.listItems()) {
                if (comingPkg == null) continue
                val pkgId = comingPkg.id
                val existingPkg = baseTable.packageArray.getPackageBlockById(pkgId)
                val isNewPkg = existingPkg == null
                val targetPkg = existingPkg ?: baseTable.packageArray.getOrCreate(pkgId)
                val preservedName = targetPkg.name

                if (isNewPkg) {
                    runCatching {
                        targetPkg.headerBlock.typeIdOffset = comingPkg.headerBlock.typeIdOffset
                    }
                    targetPkg.name = comingPkg.name
                }

                // Copy all TypeStrings up front so spec-only / empty types in
                // splits never create synthetic "type-0" placeholders.
                val comingTypePool = comingPkg.typeStringPool
                for (i in 0 until comingTypePool.size()) {
                    val ts = comingTypePool.get(i) ?: continue
                    val typeId = comingTypePool.idOf(ts)
                    val typeName = ts.get()
                    if (typeId > 0 && !typeName.isNullOrEmpty() &&
                        targetPkg.typeStringPool.getById(typeId) == null
                    ) {
                        targetPkg.typeStringPool.getOrCreate(typeId, typeName)
                    }
                }

                targetPkg.libraryBlock.merge(comingPkg.libraryBlock)
                targetPkg.specStringPool.merge(comingPkg.specStringPool)
                for (comingPair in comingPkg.specTypePairArray.listItems()) {
                    if (comingPair == null) continue
                    val targetPair = targetPkg.specTypePairArray.getOrCreate(comingPair.typeId)
                    targetPair.specBlock.merge(comingPair.specBlock)
                    if (!comingPair.isEmpty) {
                        targetPair.typeBlockArray.merge(comingPair.typeBlockArray)
                    }
                }
                targetPkg.stagedAliasList.merge(comingPkg.stagedAliasList)
                targetPkg.overlayableList.clearChildes()

                if (!isNewPkg && !preservedName.isNullOrEmpty()) {
                    targetPkg.name = preservedName
                }
            }
        }

        private fun mergeEntriesPreservingStored(
            baseModule: ApkModule,
            splitModule: ApkModule,
            storedPaths: MutableSet<String>,
        ) {
            val existAliases = baseModule.zipEntryMap.toAliasMap()
            val sources = runCatching { splitModule.zipEntryMap.listInputSources() }
                .getOrNull().orEmpty()
            for (src in sources) {
                val alias = src.alias ?: src.name ?: continue
                if (alias == AndroidManifestBlock.FILE_NAME || alias == RESOURCES_ARSC) continue
                if (DexFileInputSource.isDexName(alias) || DexFileInputSource.isDexName(src.name)) {
                    continue
                }
                if (existAliases.containsKey(alias) || existAliases.containsKey(src.name)) {
                    continue
                }
                val wasStored = src.isUncompressed ||
                    splitModule.uncompressedFiles.isUncompressed(alias)
                baseModule.zipEntryMap.add(src)
                if (wasStored) {
                    src.isUncompressed = true
                    storedPaths += alias
                }
            }
            baseModule.uncompressedFiles.merge(splitModule.uncompressedFiles)
        }

        internal fun matchesAttr(
            attr: ResXmlAttribute,
            expectedId: Int,
            expectedName: String,
        ): Boolean {
            val id = runCatching { attr.nameId }.getOrDefault(0)
            if (id != 0) {
                return expectedId != 0 && id == expectedId
            }
            val name = runCatching { attr.name }.getOrNull()
            return name == expectedName
        }

        internal fun findAttr(
            element: ResXmlElement,
            expectedId: Int,
            expectedName: String,
        ): ResXmlAttribute? {
            val attributes = runCatching { element.getAttributes() }.getOrNull() ?: return null
            while (attributes.hasNext()) {
                val attr = runCatching { attributes.next() }.getOrNull() ?: continue
                if (matchesAttr(attr, expectedId, expectedName)) return attr
            }
            return null
        }

        internal fun readAttrString(module: ApkModule, attr: ResXmlAttribute): String? {
            val type = runCatching { attr.valueType }.getOrNull()
            if (type == ValueType.STRING) {
                return runCatching { attr.valueAsString }.getOrNull()
            }
            if (type == ValueType.REFERENCE) {
                val resId = runCatching { attr.data }.getOrDefault(0)
                // Skip framework resources (0x01xxxxxx) — app string refs live in 0x7f / app pkg.
                if (resId != 0 && (resId ushr 24) != 0x01) {
                    resolveResourceStringBounded(module, resId)?.let { return it }
                }
            }
            return null
        }

        /**
         * Bounded, cycle-safe local resource string lookup (max depth 4) that
         * never loads Android framework tables or recurses on cyclic styles.
         */
        private fun resolveResourceStringBounded(module: ApkModule, startResId: Int): String? {
            if (!module.hasTableBlock()) return null
            val table = runCatching { module.getTableBlock(false) }.getOrNull() ?: return null
            var currentId = startResId
            val visited = HashSet<Int>(6)
            var depth = 0
            while (currentId != 0 && depth < 4 && visited.add(currentId)) {
                val resourceEntry = runCatching { table.getLocalResource(currentId) }.getOrNull()
                    ?: return null
                var nextRef = 0
                for (entry in resourceEntry) {
                    val rv = runCatching { entry.resValue }.getOrNull() ?: continue
                    val vt = runCatching { rv.valueType }.getOrNull() ?: continue
                    if (vt == ValueType.STRING) {
                        val str = runCatching { rv.valueAsString }.getOrNull()
                        if (!str.isNullOrEmpty()) return str
                    } else if (nextRef == 0 && vt == ValueType.REFERENCE) {
                        nextRef = runCatching { rv.data }.getOrDefault(0)
                    }
                }
                currentId = nextRef
                depth++
            }
            return null
        }

        internal fun rewritePackageAndManifest(
            module: ApkModule,
            oldPackage: String,
            newPackage: String,
            compatMode: Boolean,
            mergedSplitNames: Set<String> = emptySet(),
            hasSplits: Boolean = false,
        ): RewriteSummary {
            module.setLoadDefaultFramework(false)
            // Note: AndroidManifestBlockSplitSanitizer calls module.setExtractNativeLibs(...)
            // which would re-compress STORED .so files unless neutralized!
            val storedBinaryBefore = collectStoredBinaryPaths(module)
            runCatching { AndroidManifestBlockSplitSanitizer().sanitize(module) }
            // Reset ApkModule's internal extractNativeLibs override back to no-op
            // and restore STORED flag on originally uncompressed .so and .dex files!
            restoreZeroCopyBinaries(module, storedBinaryBefore)

            sanitizeResidualSplitAndSigningMetadata(
                module = module,
                mergedSplitNames = mergedSplitNames,
                hasSplits = hasSplits,
            )

            expandRelativeClassNames(module, oldPackage)
            rewritePackageNameAndTable(module, oldPackage, newPackage)

            val summary = rewriteManifestReferences(
                module = module,
                oldPackage = oldPackage,
                newPackage = newPackage,
                sameLengthVendor = compatMode,
            )

            // Only flip the XML boolean when compressed .so files are present,
            // directly on AndroidManifestBlock so ApkModule NEVER re-compresses
            // STORED .so files during writeApk()!
            if (hasCompressedNativeLibs(module)) {
                runCatching { module.androidManifest?.setExtractNativeLibs(true) }
            }
            finalizeManifest(module)
            return summary
        }

        private fun rewritePackageNameAndTable(
            module: ApkModule,
            oldPackage: String,
            newPackage: String,
        ) {
            module.setPackageName(newPackage)
            if (!module.hasTableBlock()) return
            val table = runCatching { module.getTableBlock(false) }.getOrNull() ?: return
            var changed = false
            for (pkg in table.packageArray.listItems()) {
                if (pkg == null) continue
                val currentName = pkg.name.orEmpty()
                when {
                    currentName == oldPackage || (pkg.id == 0x7f && currentName.isEmpty()) -> {
                        pkg.name = newPackage
                        changed = true
                    }
                    currentName.startsWith("$oldPackage.") -> {
                        pkg.name = newPackage + currentName.removePrefix(oldPackage)
                        changed = true
                    }
                }
                if (!pkg.overlayableList.isEmpty) {
                    pkg.overlayableList.clearChildes()
                    changed = true
                }
                for (libInfo in pkg.libraryBlock.libraryInfoArray.listItems()) {
                    if (libInfo == null) continue
                    val libName = libInfo.name.orEmpty()
                    when {
                        libName == oldPackage -> {
                            libInfo.name = newPackage
                            changed = true
                        }
                        libName.startsWith("$oldPackage.") -> {
                            libInfo.name = newPackage + libName.removePrefix(oldPackage)
                            changed = true
                        }
                    }
                }
            }
            if (changed) {
                runCatching { table.refresh() }
            }
        }

        private fun collectStoredBinaryPaths(module: ApkModule): Set<String> {
            val out = HashSet<String>()
            val sources = runCatching { module.zipEntryMap.listInputSources() }.getOrNull()
                ?: return out
            for (src in sources) {
                val p = src.alias ?: src.name ?: continue
                val isBinary = (p.startsWith("lib/") && p.endsWith(".so")) ||
                    DexFileInputSource.isDexName(p)
                if (isBinary && src.isUncompressed) {
                    out += p
                }
            }
            return out
        }

        private fun restoreZeroCopyBinaries(module: ApkModule, storedPaths: Set<String>) {
            // Neutralize ApkModule.extractNativeLibs field so updateUncompressedFiles()
            // does not touch native library compression flags!
            runCatching {
                val field = ApkModule::class.java.getDeclaredField("extractNativeLibs")
                field.isAccessible = true
                field.setInt(module, 0)
            }
            for (path in storedPaths) {
                module.uncompressedFiles.addPath(path)
                module.zipEntryMap.getInputSource(path)?.isUncompressed = true
            }
        }

        private fun hasCompressedNativeLibs(module: ApkModule): Boolean {
            val sources = runCatching { module.zipEntryMap.listInputSources() }.getOrNull()
                ?: return false
            return sources.any { src ->
                val path = src.alias ?: src.name ?: ""
                path.startsWith("lib/") && path.endsWith(".so") && !src.isUncompressed
            }
        }

        private fun hasCompressedDexFiles(module: ApkModule): Boolean {
            val sources = runCatching { module.zipEntryMap.listInputSources() }.getOrNull()
                ?: return false
            return sources.any { src ->
                val path = src.alias ?: src.name ?: ""
                DexFileInputSource.isDexName(path) && !src.isUncompressed
            }
        }

        private fun removeElementsRecursively(
            doc: ResXmlDocument,
            predicate: (ResXmlElement) -> Boolean,
        ) {
            val targets = mutableListOf<ResXmlElement>()
            val iter = runCatching { doc.recursiveElements() }.getOrNull() ?: return
            while (iter.hasNext()) {
                val element = runCatching { iter.next() }.getOrNull() ?: continue
                if (runCatching { predicate(element) }.getOrDefault(false)) {
                    targets += element
                }
            }
            for (i in targets.indices.reversed()) {
                runCatching { targets[i].removeSelf() }
            }
        }

        private fun sanitizeResidualSplitAndSigningMetadata(
            module: ApkModule,
            mergedSplitNames: Set<String>,
            hasSplits: Boolean,
        ) {
            val manifest = runCatching { module.androidManifest }.getOrNull() ?: return
            removeElementsRecursively(manifest) { element ->
                val tag = runCatching { element.name }.getOrNull()
                if (tag in OBSOLETE_MANIFEST_ELEMENTS) return@removeElementsRecursively true
                if (tag == ELEMENT_DIST_MODULE) {
                    val prefix = runCatching { element.prefix }.getOrNull()
                    val uri = runCatching { element.uri }.getOrNull()
                    val parentName = runCatching { element.parentElement?.name }.getOrNull()
                    if (prefix == DIST_PREFIX ||
                        uri == DIST_NAMESPACE_URI ||
                        parentName == ELEMENT_MANIFEST
                    ) {
                        return@removeElementsRecursively true
                    }
                }
                if (tag == ELEMENT_META_DATA) {
                    val nameAttr = findAttr(element, ID_NAME, ATTR_NAME)
                    val metaName = nameAttr?.let { readAttrString(module, it) }
                    if (metaName in SPLIT_AND_STAMP_META_NAMES) {
                        return@removeElementsRecursively true
                    }
                }
                false
            }
            val elements = runCatching { manifest.recursiveElements() }.getOrNull() ?: return
            while (elements.hasNext()) {
                val element = runCatching { elements.next() }.getOrNull() ?: continue
                val tag = runCatching { element.name }.getOrNull()

                // If an AAB <provider> belongs to an on-demand dynamic feature
                // module that was NOT installed on the phone (so its DEX is not in
                // mergedSplitNames), mark it disabled before stripping splitName so
                // ActivityThread.installContentProviders does not crash at startup.
                if (tag == ELEMENT_PROVIDER && !isInsideQueries(element)) {
                    val splitNameAttr = findAttr(element, ID_SPLIT_NAME, ATTR_SPLIT_NAME)
                    val targetSplit = splitNameAttr?.let { readAttrString(module, it)?.trim() }
                    if (!targetSplit.isNullOrEmpty() &&
                        (hasSplits || mergedSplitNames.isEmpty()) &&
                        targetSplit !in mergedSplitNames
                    ) {
                        runCatching {
                            element.getOrCreateAndroidAttribute(ATTR_ENABLED, ID_ENABLED)
                                .setValueAsBoolean(false)
                        }
                    }
                }

                val toRemove = mutableListOf<ResXmlAttribute>()
                val attrs = runCatching { element.getAttributes() }.getOrNull() ?: continue
                while (attrs.hasNext()) {
                    val attr = runCatching { attrs.next() }.getOrNull() ?: continue
                    if (SPLIT_ATTR_SPECS.any { matchesAttr(attr, it.id, it.name) }) {
                        toRemove += attr
                        continue
                    }
                    // Work around ARSCLib's signed-int ResXmlAttribute.compareTo bug:
                    // dynamic feature attributes in 0x80000000..0xffffffff are < 0 in
                    // signed int, so ARSCLib sorts them BEFORE 0x01010003 (android:name),
                    // breaking Android's C++ unsigned BackTrackingAttributeFinder!
                    val rawId = runCatching { attr.nameId }.getOrDefault(0)
                    if (rawId < 0) {
                        val rawName = runCatching { attr.name }.getOrNull()
                        if (!rawName.isNullOrEmpty()) {
                            runCatching { attr.setName(rawName, 0) }
                        } else {
                            toRemove += attr
                        }
                    }
                }
                toRemove.forEach { attr -> runCatching { element.removeAttribute(attr) } }
            }
        }

        private fun normalizeHighPackageAttrIds(doc: ResXmlDocument) {
            val elements = runCatching { doc.recursiveElements() }.getOrNull() ?: return
            while (elements.hasNext()) {
                val element = runCatching { elements.next() }.getOrNull() ?: continue
                val toRemove = mutableListOf<ResXmlAttribute>()
                val attrs = runCatching { element.getAttributes() }.getOrNull() ?: continue
                while (attrs.hasNext()) {
                    val attr = runCatching { attrs.next() }.getOrNull() ?: continue
                    val rawId = runCatching { attr.nameId }.getOrDefault(0)
                    if (rawId < 0) {
                        val rawName = runCatching { attr.name }.getOrNull()
                        if (!rawName.isNullOrEmpty()) {
                            runCatching { attr.setName(rawName, 0) }
                        } else {
                            toRemove += attr
                        }
                    }
                }
                toRemove.forEach { attr -> runCatching { element.removeAttribute(attr) } }
            }
        }

        private fun finalizeManifest(module: ApkModule) {
            val manifest = runCatching { module.androidManifest }.getOrNull() ?: return
            normalizeHighPackageAttrIds(manifest)
            runCatching { manifest.removeUnusedNamespaces() }
            runCatching { manifest.removeUndefinedAttributes() }
            runCatching { manifest.refresh() }
        }

        internal fun expandRelativeClassNames(module: ApkModule, oldPackage: String) {
            val manifest = runCatching { module.androidManifest }.getOrNull() ?: return
            val elements = runCatching { manifest.recursiveElements() }.getOrNull() ?: return
            while (elements.hasNext()) {
                val element = runCatching { elements.next() }.getOrNull() ?: continue
                val elementName = runCatching { element.name }.getOrNull() ?: continue
                if (elementName !in CLASS_NAME_ELEMENTS) continue

                val specs = COMMON_CLASS_ATTRS + EXTRA_CLASS_ATTRS[elementName].orEmpty()
                val attributes = runCatching { element.getAttributes() }.getOrNull() ?: continue
                while (attributes.hasNext()) {
                    val attr = runCatching { attributes.next() }.getOrNull() ?: continue
                    if (specs.none { matchesAttr(attr, it.id, it.name) }) continue
                    val value = readAttrString(module, attr) ?: continue
                    val expanded = expandClassName(value, oldPackage) ?: continue
                    runCatching { attr.setValueAsString(expanded) }
                }
            }
        }

        private fun expandClassName(value: String, oldPackage: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("@") || trimmed.startsWith("?")) return null
            if (trimmed.startsWith("$oldPackage.")) return null
            return when {
                trimmed.startsWith('.') -> oldPackage + trimmed
                !trimmed.contains('.') -> "$oldPackage.$trimmed"
                else -> null
            }
        }

        private fun removeSharedUserId(manifest: AndroidManifestBlock) {
            val root = runCatching { manifest.manifestElement }.getOrNull() ?: return
            val targets = mutableListOf<ResXmlAttribute>()
            val attributes = runCatching { root.getAttributes() }.getOrNull() ?: return
            while (attributes.hasNext()) {
                val attr = runCatching { attributes.next() }.getOrNull() ?: continue
                if (matchesAttr(attr, ID_SHARED_USER_ID, ATTR_SHARED_USER_ID) ||
                    matchesAttr(attr, ID_SHARED_USER_LABEL, ATTR_SHARED_USER_LABEL) ||
                    matchesAttr(attr, ID_SHARED_USER_MAX_SDK, ATTR_SHARED_USER_MAX_SDK)
                ) {
                    targets += attr
                }
            }
            targets.forEach { attr -> runCatching { root.removeAttribute(attr) } }
        }

        private fun rewriteManifestReferences(
            module: ApkModule,
            oldPackage: String,
            newPackage: String,
            sameLengthVendor: Boolean,
        ): RewriteSummary {
            val manifest = runCatching { module.androidManifest }.getOrNull()
                ?: return RewriteSummary(emptyMap(), emptyMap(), emptyMap())

            removeSharedUserId(manifest)

            val renamedPermissions = LinkedHashMap<String, String>()
            val renamedAuthorities = LinkedHashMap<String, String>()
            val renamedAccountTypes = LinkedHashMap<String, String>()

            // Pass 1: declared <permission>, <permission-tree>, <permission-group>
            val seenDeclared = HashSet<Pair<String, String>>()
            val usedPermTargets = HashSet<String>()
            val duplicateElements = mutableListOf<ResXmlElement>()
            val pass1 = runCatching { manifest.recursiveElements() }.getOrNull()
            while (pass1 != null && pass1.hasNext()) {
                val element = runCatching { pass1.next() }.getOrNull() ?: continue
                val elementName = runCatching { element.name }.getOrNull() ?: continue
                if (elementName !in DECLARED_PERMISSION_ELEMENTS) continue

                val nameAttr = findAttr(element, ID_NAME, ATTR_NAME) ?: continue
                val oldValue = readAttrString(module, nameAttr)?.trim()
                if (oldValue.isNullOrEmpty()) continue

                val existingMapped = renamedPermissions[oldValue]
                val newValue = existingMapped ?: (
                    ManifestNaming.renamePermissionName(
                        value = oldValue,
                        oldPackage = oldPackage,
                        newPackage = newPackage,
                        mustBeOwned = false,
                        sameLengthVendor = sameLengthVendor,
                        usedTargets = usedPermTargets,
                    ) ?: oldValue
                    )

                if (!seenDeclared.add(elementName to newValue)) {
                    duplicateElements += element
                    continue
                }
                usedPermTargets += newValue
                if (newValue != oldValue) {
                    renamedPermissions[oldValue] = newValue
                    runCatching { nameAttr.setValueAsString(newValue) }
                }
                clearKnownSignerRestriction(element)
            }
            duplicateElements.forEach { dup -> runCatching { dup.removeSelf() } }

            // Pass 2: check attribute IDs FIRST (O(1) int check) before reading strings!
            val seenAuthorities = HashSet<String>()
            val usedAuthTargets = HashSet<String>()
            val duplicateProviders = mutableListOf<ResXmlElement>()
            val authenticatorResIds = LinkedHashSet<Int>()
            val syncAdapterResIds = LinkedHashSet<Int>()

            val pass2 = runCatching { manifest.recursiveElements() }.getOrNull()
            while (pass2 != null && pass2.hasNext()) {
                val element = runCatching { pass2.next() }.getOrNull() ?: continue
                val elementName = runCatching { element.name }.getOrNull() ?: continue
                val inQueries = isInsideQueries(element)

                if (elementName == ELEMENT_META_DATA && !inQueries) {
                    val metaName = findAttr(element, ID_NAME, ATTR_NAME)
                        ?.let { readAttrString(module, it) }
                    if (metaName == META_ACCOUNT_AUTHENTICATOR || metaName == META_SYNC_ADAPTER) {
                        val resId = findAttr(element, ID_RESOURCE, ATTR_RESOURCE)
                            ?.takeIf { runCatching { it.valueType }.getOrNull() == ValueType.REFERENCE }
                            ?.let { runCatching { it.data }.getOrDefault(0) } ?: 0
                        if (resId != 0) {
                            if (metaName == META_ACCOUNT_AUTHENTICATOR) {
                                authenticatorResIds += resId
                            } else {
                                syncAdapterResIds += resId
                            }
                        }
                    }
                    continue
                }

                val isProvider = !inQueries && elementName == ELEMENT_PROVIDER
                val isUsesPerm = elementName in USES_PERMISSION_ELEMENTS
                val isTaskAffinityEl = elementName in TASK_AFFINITY_ELEMENTS

                val attributes = runCatching { element.getAttributes() }.getOrNull() ?: continue
                while (attributes.hasNext()) {
                    val attr = runCatching { attributes.next() }.getOrNull() ?: continue

                    when {
                        isProvider && matchesAttr(attr, ID_AUTHORITIES, ATTR_AUTHORITIES) -> {
                            val value = readAttrString(module, attr) ?: continue
                            val uniqueRewritten = value.split(';')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .mapNotNull { rawAuth ->
                                    val newAuth = renamedAuthorities[rawAuth]
                                        ?: ManifestNaming.renameAuthorities(
                                            value = rawAuth,
                                            oldPackage = oldPackage,
                                            newPackage = newPackage,
                                            sameLengthVendor = sameLengthVendor,
                                            usedTargets = usedAuthTargets,
                                        )
                                    renamedAuthorities[rawAuth] = newAuth
                                    usedAuthTargets += newAuth
                                    if (seenAuthorities.add(newAuth)) newAuth else null
                                }
                            if (uniqueRewritten.isEmpty()) {
                                duplicateProviders += element
                            } else {
                                runCatching {
                                    attr.setValueAsString(uniqueRewritten.joinToString(";"))
                                }
                            }
                        }

                        matchesAttr(attr, ID_PROCESS, ATTR_PROCESS) -> {
                            val value = readAttrString(module, attr) ?: continue
                            ManifestNaming.renameProcess(value, oldPackage, newPackage)
                                ?.let { renamed -> runCatching { attr.setValueAsString(renamed) } }
                        }

                        isTaskAffinityEl && matchesAttr(attr, ID_TASK_AFFINITY, ATTR_TASK_AFFINITY) -> {
                            val value = readAttrString(module, attr) ?: continue
                            ManifestNaming.renameTaskAffinity(value, oldPackage, newPackage)
                                ?.let { renamed -> runCatching { attr.setValueAsString(renamed) } }
                        }

                        (isUsesPerm && matchesAttr(attr, ID_NAME, ATTR_NAME)) ||
                            COMPONENT_PERMISSION_SPECS.any { matchesAttr(attr, it.id, it.name) } -> {
                            val value = readAttrString(module, attr) ?: continue
                            ManifestNaming.renamePermissionName(
                                value = value,
                                oldPackage = oldPackage,
                                newPackage = newPackage,
                                mustBeOwned = true,
                                declaredPermissions = renamedPermissions,
                            )?.let { renamed -> runCatching { attr.setValueAsString(renamed) } }
                        }
                    }
                }
            }
            duplicateProviders.forEach { dup -> runCatching { dup.removeSelf() } }

            rewriteAccountAndSyncXmls(
                module = module,
                xmlResIds = authenticatorResIds + syncAdapterResIds,
                oldPackage = oldPackage,
                newPackage = newPackage,
                sameLengthVendor = sameLengthVendor,
                renamedAuthorities = renamedAuthorities,
                renamedAccountTypes = renamedAccountTypes,
            )

            return RewriteSummary(
                renamedAuthorities = renamedAuthorities,
                renamedPermissions = renamedPermissions,
                renamedAccountTypes = renamedAccountTypes,
            )
        }

        private fun clearKnownSignerRestriction(permissionElement: ResXmlElement) {
            findAttr(permissionElement, ID_KNOWN_CERTS, ATTR_KNOWN_CERTS)?.let { certAttr ->
                runCatching { permissionElement.removeAttribute(certAttr) }
            }
            val protAttr = findAttr(
                permissionElement,
                ID_PROTECTION_LEVEL,
                ATTR_PROTECTION_LEVEL,
            ) ?: return
            val vt = runCatching { protAttr.valueType }.getOrNull()
            if (vt == ValueType.STRING) {
                val raw = runCatching { protAttr.valueAsString }.getOrNull() ?: return
                val filtered = raw.split('|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != PROTECTION_KNOWN_SIGNER }
                if (filtered.isNotEmpty() && filtered.size != raw.split('|').size) {
                    runCatching { protAttr.setValueAsString(filtered.joinToString("|")) }
                }
            } else if (vt == ValueType.HEX || vt == ValueType.DEC) {
                val flags = runCatching { protAttr.data }.getOrDefault(0)
                if ((flags and PROTECTION_FLAG_KNOWN_SIGNER) != 0) {
                    val cleaned = (flags and PROTECTION_FLAG_KNOWN_SIGNER.inv())
                        .let { if (it == 0) PROTECTION_FLAG_SIGNATURE else it }
                    runCatching { protAttr.data = cleaned }
                }
            }
        }

        private fun isInsideQueries(element: ResXmlElement): Boolean {
            var cursor: ResXmlElement? = runCatching { element.parentElement }.getOrNull()
            while (cursor != null) {
                val current = cursor
                if (runCatching { current.name }.getOrNull() == ELEMENT_QUERIES) return true
                cursor = runCatching { current.parentElement }.getOrNull()
            }
            return false
        }

        private fun rewriteAccountAndSyncXmls(
            module: ApkModule,
            xmlResIds: Set<Int>,
            oldPackage: String,
            newPackage: String,
            sameLengthVendor: Boolean,
            renamedAuthorities: Map<String, String>,
            renamedAccountTypes: MutableMap<String, String>,
        ) {
            if (xmlResIds.isEmpty() || !module.hasTableBlock()) return
            val table = runCatching { module.getTableBlock(false) }.getOrNull() ?: return
            val visitedPaths = HashSet<String>()
            val usedAccountTargets = HashSet<String>()
            for (resId in xmlResIds) {
                val resEntry = runCatching { table.getLocalResource(resId) }.getOrNull() ?: continue
                for (entry in resEntry) {
                    val rv = runCatching { entry.resValue }.getOrNull() ?: continue
                    if (runCatching { rv.valueType }.getOrNull() != ValueType.STRING) continue
                    val path = runCatching { rv.valueAsString }.getOrNull() ?: continue
                    if (path.isEmpty() || !visitedPaths.add(path)) continue
                    val inputSource = module.getInputSource(path) ?: continue
                    val doc: ResXmlDocument = runCatching {
                        module.loadResXmlDocument(inputSource)
                    }.getOrNull() ?: continue

                    var changed = false
                    val elements = runCatching { doc.recursiveElements() }.getOrNull() ?: continue
                    while (elements.hasNext()) {
                        val el = runCatching { elements.next() }.getOrNull() ?: continue
                        findAttr(el, ID_ACCOUNT_TYPE, ATTR_ACCOUNT_TYPE)?.let { attr ->
                            val oldAcc = readAttrString(module, attr)?.trim()
                            if (!oldAcc.isNullOrEmpty()) {
                                val newAcc = renamedAccountTypes[oldAcc]
                                    ?: ManifestNaming.renameAuthorities(
                                        value = oldAcc,
                                        oldPackage = oldPackage,
                                        newPackage = newPackage,
                                        sameLengthVendor = sameLengthVendor,
                                        usedTargets = usedAccountTargets,
                                    )
                                usedAccountTargets += newAcc
                                if (newAcc != oldAcc) {
                                    renamedAccountTypes[oldAcc] = newAcc
                                    runCatching { attr.setValueAsString(newAcc) }
                                    changed = true
                                }
                            }
                        }
                        findAttr(el, ID_CONTENT_AUTHORITY, ATTR_CONTENT_AUTHORITY)?.let { attr ->
                            val oldAuth = readAttrString(module, attr)?.trim()
                            if (!oldAuth.isNullOrEmpty()) {
                                val newAuth = renamedAuthorities[oldAuth]
                                    ?: if (oldAuth == oldPackage || oldAuth.startsWith("$oldPackage.")) {
                                        ManifestNaming.renameAuthorities(
                                            value = oldAuth,
                                            oldPackage = oldPackage,
                                            newPackage = newPackage,
                                            sameLengthVendor = sameLengthVendor,
                                        )
                                    } else {
                                        null
                                    }
                                if (newAuth != null && newAuth != oldAuth) {
                                    runCatching { attr.setValueAsString(newAuth) }
                                    changed = true
                                }
                            }
                        }
                    }
                    if (changed) {
                        normalizeHighPackageAttrIds(doc)
                        runCatching { doc.refresh() }
                        val updatedSource = BlockInputSource(inputSource, doc)
                        module.add(updatedSource)
                    }
                }
            }
        }

        internal fun applyCloneLabel(module: ApkModule, newLabel: String) {
            val manifest = runCatching { module.androidManifest }.getOrNull() ?: return
            runCatching { manifest.setApplicationLabel(newLabel) }

            for (launcherElement in findLauncherComponents(module, manifest)) {
                findAttr(launcherElement, ID_LABEL, ATTR_LABEL)?.let { labelAttr ->
                    runCatching { labelAttr.setValueAsString(newLabel) }
                }
            }
        }

        private fun findLauncherComponents(
            module: ApkModule,
            manifest: AndroidManifestBlock,
        ): List<ResXmlElement> {
            val appElement = runCatching { manifest.applicationElement }.getOrNull()
                ?: return emptyList()
            val results = mutableListOf<ResXmlElement>()
            val children = runCatching { appElement.elements }.getOrNull() ?: return emptyList()
            while (children.hasNext()) {
                val component = runCatching { children.next() }.getOrNull() ?: continue
                val tag = runCatching { component.name }.getOrNull() ?: continue
                if (tag != "activity" && tag != "activity-alias") continue
                if (hasLauncherIntentFilter(module, component)) {
                    results += component
                }
            }
            return results
        }

        private fun hasLauncherIntentFilter(module: ApkModule, component: ResXmlElement): Boolean {
            val filters = runCatching { component.elements }.getOrNull() ?: return false
            while (filters.hasNext()) {
                val filter = runCatching { filters.next() }.getOrNull() ?: continue
                if (runCatching { filter.name }.getOrNull() != "intent-filter") continue
                var hasMain = false
                var hasLauncher = false
                val items = runCatching { filter.elements }.getOrNull() ?: continue
                while (items.hasNext()) {
                    val item = runCatching { items.next() }.getOrNull() ?: continue
                    val itemTag = runCatching { item.name }.getOrNull() ?: continue
                    val itemName = findAttr(item, ID_NAME, ATTR_NAME)
                        ?.let { readAttrString(module, it) } ?: continue
                    if (itemTag == "action" && itemName == ACTION_MAIN) {
                        hasMain = true
                    } else if (itemTag == "category" &&
                        (itemName == CATEGORY_LAUNCHER || itemName == CATEGORY_LEANBACK_LAUNCHER)
                    ) {
                        hasLauncher = true
                    }
                }
                if (hasMain && hasLauncher) return true
            }
            return false
        }

        internal fun stripAdvertisingId(module: ApkModule) {
            val manifest = runCatching { module.androidManifest }.getOrNull() ?: return
            removeElementsRecursively(manifest) { element ->
                val elementName = runCatching { element.name }.getOrNull()
                if (elementName !in USES_PERMISSION_ELEMENTS) return@removeElementsRecursively false
                val permName = findAttr(element, ID_NAME, ATTR_NAME)
                    ?.let { readAttrString(module, it) }
                permName == AD_ID_PERMISSION
            }
        }

        internal fun replaceIcon(module: ApkModule, iconBytes: ByteArray) {
            val manifest = runCatching { module.androidManifest }.getOrNull() ?: return
            val iconIds = LinkedHashSet<Int>()
            runCatching { manifest.iconResourceId }.getOrDefault(0)
                .takeIf { it != 0 }?.let(iconIds::add)
            runCatching { manifest.roundIconResourceId }.getOrDefault(0)
                .takeIf { it != 0 }?.let(iconIds::add)

            for (launcher in findLauncherComponents(module, manifest)) {
                for ((id, name) in listOf(ID_ICON to ATTR_ICON, ID_ROUND_ICON to ATTR_ROUND_ICON)) {
                    val attr = findAttr(launcher, id, name) ?: continue
                    if (runCatching { attr.valueType }.getOrNull() == ValueType.REFERENCE) {
                        val resId = runCatching { attr.data }.getOrDefault(0)
                        if (resId != 0) iconIds += resId
                    }
                }
            }
            if (iconIds.isEmpty() || !module.hasTableBlock()) return
            val table = runCatching { module.getTableBlock(false) }.getOrNull() ?: return

            var modifiedTable = false
            for (iconId in iconIds) {
                val resEntry = runCatching { table.getLocalResource(iconId) }.getOrNull()
                    ?: continue
                val rasterPaths = LinkedHashSet<String>()
                val xmlEntries = ArrayList<Pair<com.reandroid.arsc.value.Entry, String>>()
                for (entry in resEntry) {
                    val rv = runCatching { entry.resValue }.getOrNull() ?: continue
                    if (runCatching { rv.valueType }.getOrNull() != ValueType.STRING) continue
                    val path = runCatching { rv.valueAsString }.getOrNull() ?: continue
                    when {
                        path.endsWith(".png", ignoreCase = true) ||
                            path.endsWith(".webp", ignoreCase = true) ||
                            path.endsWith(".jpg", ignoreCase = true) ||
                            path.endsWith(".jpeg", ignoreCase = true) -> rasterPaths += path
                        path.endsWith(".xml", ignoreCase = true) -> xmlEntries += entry to path
                    }
                }
                for (path in rasterPaths) {
                    val original = module.getInputSource(path) ?: continue
                    val replacement = ByteInputSource(iconBytes, path)
                    replacement.copyAttributes(original)
                    replacement.method = original.method
                    replacement.isUncompressed = original.isUncompressed
                    module.removeInputSource(path)
                    module.add(replacement)
                }
                if (rasterPaths.isNotEmpty()) {
                    for ((entry, xmlPath) in xmlEntries) {
                        runCatching {
                            entry.isNull = true
                            module.removeInputSource(xmlPath)
                            modifiedTable = true
                        }
                    }
                } else if (xmlEntries.isNotEmpty()) {
                    xmlEntries.forEachIndexed { idx, (entry, oldPath) ->
                        val original = module.getInputSource(oldPath) ?: return@forEachIndexed
                        val newPath = oldPath.removeSuffix(".xml").removeSuffix(".XML") + "_oc$idx.png"
                        runCatching {
                            entry.resValue?.valueAsString = newPath
                            module.removeInputSource(oldPath)
                            val replacement = ByteInputSource(iconBytes, newPath)
                            replacement.copyAttributes(original)
                            module.add(replacement)
                            modifiedTable = true
                        }
                    }
                }
            }
            if (modifiedTable) {
                runCatching { table.refresh() }
            }
        }

        internal fun stripSignatureEntries(module: ApkModule) {
            runCatching { module.apkSignatureBlock = null }
            val archive = module.zipEntryMap
            val signatures = archive.listInputSources()
                .map { it.name }
                .filter { ManifestNaming.isSignatureEntry(it) }
            signatures.forEach { name ->
                runCatching { archive.remove(name) }
            }
        }

        private fun syncEmbeddedDexFlag(module: ApkModule) {
            val appElement = runCatching { module.androidManifest?.applicationElement }.getOrNull()
                ?: return
            val embeddedDexAttr = findAttr(appElement, ID_USE_EMBEDDED_DEX, ATTR_USE_EMBEDDED_DEX)
                ?: return
            if (hasCompressedDexFiles(module)) {
                runCatching { appElement.removeAttribute(embeddedDexAttr) }
            }
        }

        internal fun writeUnsignedApk(module: ApkModule, unsignedFile: File) {
            stripSignatureEntries(module)
            syncEmbeddedDexFlag(module)
            finalizeManifest(module)
            module.uncompressedFiles.addPath(RESOURCES_ARSC)
            module.zipEntryMap.getInputSource(RESOURCES_ARSC)?.isUncompressed = true

            unsignedFile.parentFile?.mkdirs()
            val writer = module.createApkFileWriter(unsignedFile)
            writer.zipAligner?.setFileAlignment(ZipAligner.PREDICATE_NATIVE_LIBS, ALIGNMENT_PAGE_16KB)
            writer.write()
        }
    }
}
