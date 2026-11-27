package dev.bill.openclone

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.reandroid.dex.ins.Opcode
import com.reandroid.dex.key.MethodKey
import com.reandroid.dex.key.TypeKey
import com.reandroid.dex.model.DexFile
import com.reandroid.dex.sections.SectionType
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CloneEngineIntegrationTest {

    @Test
    fun `rewrites Redex-style stripped attributes, Facebook vendor permissions, @string authorities, authenticator XML, and DEX literals in-place`() = runBlocking {
        val oldPkg = "com.facebook.katana"
        val newPkg = "com.facebook.katana.clone1"

        val module = ApkModule()
        module.setLoadDefaultFramework(false)
        val table = TableBlock.createEmpty()
        val pkgBlock = table.getOrCreatePackage(0x7f, oldPkg)

        // Create a @string resource used as a provider authority reference.
        val authResEntry = pkgBlock.getOrCreate("", "string", "fb_file_auth")
        authResEntry.setValueAsString("$oldPkg.fileprovider")
        val authResId = authResEntry.resourceId

        // Create an AccountAuthenticator XML resource (`res/xml/authenticator.xml`).
        val xmlResEntry = pkgBlock.getOrCreate("", "xml", "authenticator")
        val authXmlPath = "res/xml/authenticator.xml"
        xmlResEntry.setValueAsString(authXmlPath)
        val authXmlResId = xmlResEntry.resourceId

        val authXmlDoc = ResXmlDocument()
        val authRoot = authXmlDoc.getOrCreateElement("account-authenticator")
        authRoot.getOrCreateAndroidAttribute("accountType", CloneEngine.ID_ACCOUNT_TYPE)
            .setValueAsString("com.facebook.auth.login")
        authRoot.getOrCreateAndroidAttribute("contentAuthority", CloneEngine.ID_CONTENT_AUTHORITY)
            .setValueAsString("$oldPkg.provider.Contacts")
        authXmlDoc.refresh()
        module.add(ByteInputSource(authXmlDoc.bytes, authXmlPath))

        module.tableBlock = table

        val manifest = AndroidManifestBlock()
        manifest.packageName = oldPkg
        manifest.manifestElement.getOrCreateAndroidAttribute(
            "sharedUserId",
            CloneEngine.ID_SHARED_USER_ID,
        ).setValueAsString("com.facebook.uid")

        // Dotted vendor permission NOT starting with com.facebook.katana, with stripped attr name!
        val permEl = manifest.manifestElement.newElement("permission")
        permEl.getOrCreateAndroidAttribute("", CloneEngine.ID_NAME).apply {
            setName("", CloneEngine.ID_NAME)
            setValueAsString("com.facebook.permission.prod.FB_APP_COMMUNICATION")
        }
        val usesPermEl = manifest.manifestElement.newElement("uses-permission")
        usesPermEl.getOrCreateAndroidAttribute("", CloneEngine.ID_NAME).apply {
            setName("", CloneEngine.ID_NAME)
            setValueAsString("com.facebook.permission.prod.FB_APP_COMMUNICATION")
        }
        val internetPermEl = manifest.manifestElement.newElement("uses-permission")
        internetPermEl.getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("android.permission.INTERNET")

        // External <queries><provider> must NOT be rewritten!
        val queriesEl = manifest.manifestElement.newElement("queries")
        val externalProvider = queriesEl.newElement("provider")
        externalProvider.getOrCreateAndroidAttribute("authorities", CloneEngine.ID_AUTHORITIES)
            .setValueAsString("com.whatsapp.provider.media")

        val appEl = manifest.orCreateApplicationElement
        appEl.getOrCreateAndroidAttribute("process", CloneEngine.ID_PROCESS)
            .setValueAsString(oldPkg)
        appEl.getOrCreateAndroidAttribute("taskAffinity", CloneEngine.ID_TASK_AFFINITY)
            .setValueAsString(oldPkg)

        // Launcher activity with relative name (stripped attr name!) and explicit label.
        val launcherAct = appEl.newElement("activity")
        launcherAct.getOrCreateAndroidAttribute("", CloneEngine.ID_NAME).apply {
            setName("", CloneEngine.ID_NAME)
            setValueAsString(".LoginActivity")
        }
        launcherAct.getOrCreateAndroidAttribute("label", CloneEngine.ID_LABEL)
            .setValueAsString("Facebook")
        val filter = launcherAct.newElement("intent-filter")
        filter.newElement("action")
            .getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("android.intent.action.MAIN")
        filter.newElement("category")
            .getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("android.intent.category.LAUNCHER")

        // Provider 1: stripped attribute name (`""`, ID 0x01010018) + vendor authority
        val provider1 = appEl.newElement("provider")
        provider1.getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("com.facebook.katana.KeyValueProvider")
        provider1.getOrCreateAndroidAttribute("", CloneEngine.ID_AUTHORITIES).apply {
            setName("", CloneEngine.ID_AUTHORITIES)
            setValueAsString("$oldPkg.provider.Contacts;com.facebook.appmanager")
        }

        // Provider 2: @string/... reference authority (`ValueType.REFERENCE`)
        val provider2 = appEl.newElement("provider")
        provider2.getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("androidx.core.content.FileProvider")
        provider2.getOrCreateAndroidAttribute("authorities", CloneEngine.ID_AUTHORITIES)
            .setTypeAndData(ValueType.REFERENCE, authResId)

        // Authenticator service referencing @xml/authenticator
        val authService = appEl.newElement("service")
        authService.getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString(".AuthService")
        val metaEl = authService.newElement("meta-data")
        metaEl.getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("android.accounts.AccountAuthenticator")
        metaEl.getOrCreateAndroidAttribute("resource", CloneEngine.ID_RESOURCE)
            .setTypeAndData(ValueType.REFERENCE, authXmlResId)

        val stampMeta = appEl.newElement("meta-data")
        stampMeta.getOrCreateAndroidAttribute("name", CloneEngine.ID_NAME)
            .setValueAsString("com.android.stamp.source")
        stampMeta.getOrCreateAndroidAttribute("value", CloneEngine.ID_VALUE)
            .setValueAsString("https://play.google.com/store")

        module.setManifest(manifest)
        module.add(ByteInputSource("dummy".toByteArray(), "stamp-cert-sha256"))

        // Build a real classes.dex with neighbor strings around vendor literals.
        val dex = DexFile.createDefault()
        val dexClass = dex.orCreateFirst.getOrCreateClass(TypeKey("Lcom/facebook/katana/LoginActivity;"))
        dexClass.setSuperClass(TypeKey("Landroid/app/Activity;"))
        val method = dexClass.getOrCreateDirectMethod(
            MethodKey.parse("Lcom/facebook/katana/LoginActivity;->init()V"),
        )
        method.addAccessFlag(com.reandroid.dex.common.AccessFlag.STATIC)
        method.localRegistersCount = 1
        val insns = method.definition.orCreateInstructionList
        insns.createStringAt(insns.count, 0, "com.facebook.appmanagba")
        insns.createStringAt(insns.count, 0, "com.facebook.appmanager")
        insns.createStringAt(insns.count, 0, "com.facebook.appmanages")
        insns.createStringAt(insns.count, 0, "content://com.facebook.appmanager/feed")
        insns.createStringAt(insns.count, 0, "com.facebook.permission.prod.FB_APP_COMMUNICATION")
        insns.createStringAt(insns.count, 0, "com.facebook.auth.login")
        insns.createNext(Opcode.RETURN_VOID)
        dex.refreshFull()
        module.add(ByteInputSource(dex.bytes, "classes.dex"))
        dex.close()

        module.uncompressedFiles.removePath(CloneEngine.RESOURCES_ARSC)
        module.zipEntryMap.getInputSource(CloneEngine.RESOURCES_ARSC)?.isUncompressed = false

        val summary = CloneEngine.rewritePackageAndManifest(
            module = module,
            oldPackage = oldPkg,
            newPackage = newPkg,
            compatMode = true,
        )
        CloneEngine.applyCloneLabel(module, "Facebook (Clone 1)")
        val rules = DexPatcher.buildRules(
            renamedAuthorities = summary.renamedAuthorities,
            renamedPermissions = summary.renamedPermissions,
            renamedAccountTypes = summary.renamedAccountTypes,
        )
        val patchReport = DexPatcher.patchModule(module, rules) { _, _ -> }
        assertEquals(4, patchReport.patched)
        assertEquals("Setiap literal yang ketemu harus bisa ditulis ulang", 0, patchReport.rejected)

        val tempDir = java.nio.file.Files.createTempDirectory("openclone-test").toFile()
        try {
            val unsignedApk = File(tempDir, "unsigned.apk")
            val signedApk = File(tempDir, "signed.apk")
            CloneEngine.writeUnsignedApk(module, unsignedApk)
            signForTest(unsignedApk, signedApk)

            val verifyResult = ApkVerifier.Builder(signedApk).build().verify()
            assertTrue("Signed APK failed verification: ${verifyResult.errors}", verifyResult.isVerified)

            ZipFile(signedApk).use { zip ->
                val arscEntry = zip.getEntry(CloneEngine.RESOURCES_ARSC)
                assertNotNull(arscEntry)
                assertEquals(ZipEntry.STORED, arscEntry.method)
                assertNull(zip.getEntry("stamp-cert-sha256"))
            }

            ApkModule.loadApkFile(signedApk).use { reloaded ->
                reloaded.setLoadDefaultFramework(false)
                val m = reloaded.androidManifest
                assertEquals(newPkg, m.packageName)
                assertNull(
                    CloneEngine.findAttr(
                        m.manifestElement,
                        CloneEngine.ID_SHARED_USER_ID,
                        "sharedUserId",
                    ),
                )
                assertEquals("Facebook (Clone 1)", m.applicationLabelString)
                val app = m.applicationElement
                assertEquals(
                    newPkg,
                    CloneEngine.findAttr(app, CloneEngine.ID_PROCESS, "process")?.valueAsString,
                )
                assertEquals(
                    newPkg,
                    CloneEngine.findAttr(app, CloneEngine.ID_TASK_AFFINITY, "taskAffinity")?.valueAsString,
                )

                val act = app.getElements { it.name == "activity" }.next()
                assertEquals(
                    "$oldPkg.LoginActivity",
                    CloneEngine.findAttr(act, CloneEngine.ID_NAME, "name")?.valueAsString,
                )
                assertEquals(
                    "Facebook (Clone 1)",
                    CloneEngine.findAttr(act, CloneEngine.ID_LABEL, "label")?.valueAsString,
                )

                // Vendor permission mutated to exact same length (`...FB_APP_COMMUNICATIC1`)!
                val expectedPerm = "com.facebook.permission.prod.FB_APP_COMMUNICATIC1"
                val declaredPerm = m.manifestElement.getElements { it.name == "permission" }.next()
                assertEquals(
                    expectedPerm,
                    CloneEngine.findAttr(declaredPerm, CloneEngine.ID_NAME, "name")?.valueAsString,
                )
                val usesPerms = mutableListOf<String>()
                m.manifestElement.getElements { it.name == "uses-permission" }.forEachRemaining { el ->
                    CloneEngine.findAttr(el, CloneEngine.ID_NAME, "name")?.valueAsString?.let(usesPerms::add)
                }
                assertTrue(expectedPerm in usesPerms)
                assertTrue("android.permission.INTERNET" in usesPerms)

                val qProv = m.manifestElement.getElements { it.name == "queries" }.next()
                    .getElements { it.name == "provider" }.next()
                assertEquals(
                    "com.whatsapp.provider.media",
                    CloneEngine.findAttr(qProv, CloneEngine.ID_AUTHORITIES, "authorities")?.valueAsString,
                )

                val appProviders = mutableListOf<String>()
                app.getElements { it.name == "provider" }.forEachRemaining { el ->
                    CloneEngine.findAttr(el, CloneEngine.ID_AUTHORITIES, "authorities")
                        ?.valueAsString
                        ?.let(appProviders::add)
                }
                assertEquals(
                    listOf(
                        "$newPkg.provider.Contacts;com.facebook.appmanagc1",
                        "$newPkg.fileprovider",
                    ),
                    appProviders,
                )

                val patchedAuthDoc = reloaded.loadResXmlDocument(authXmlPath)
                val patchedAuthRoot = patchedAuthDoc.documentElement
                assertEquals(
                    "com.facebook.auth.logc1",
                    CloneEngine.findAttr(patchedAuthRoot, CloneEngine.ID_ACCOUNT_TYPE, "accountType")
                        ?.valueAsString,
                )
                assertEquals(
                    "$newPkg.provider.Contacts",
                    CloneEngine.findAttr(
                        patchedAuthRoot,
                        CloneEngine.ID_CONTENT_AUTHORITY,
                        "contentAuthority",
                    )?.valueAsString,
                )

                // Verify binary-patched DEX parses cleanly and contains all mutated literals!
                val patchedDexBytes = reloaded.getInputSource("classes.dex").openStream().readBytes()
                val patchedDexFile = File(tempDir, "patched_check.dex").apply { writeBytes(patchedDexBytes) }
                val dexdump = requireBuildTool("dexdump")
                val dexdumpOutput = runCmd(dexdump, "-c", patchedDexFile.absolutePath)
                assertFalse("DEX verification error: $dexdumpOutput", dexdumpOutput.contains("Failure to verify"))

                DexFile.read(patchedDexBytes).use { verifyDex ->
                    val strings = HashSet<String>()
                    for (layout in verifyDex) {
                        val sec = layout.getSection(SectionType.STRING_ID) ?: continue
                        for (item in sec) {
                            item.string?.let(strings::add)
                        }
                    }
                    assertTrue("Lcom/facebook/katana/LoginActivity;" in strings)
                    assertTrue("com.facebook.appmanagc1" in strings)
                    assertTrue("content://com.facebook.appmanagc1/feed" in strings)
                    assertTrue(expectedPerm in strings)
                    assertTrue("com.facebook.auth.logc1" in strings)
                    assertFalse("com.facebook.auth.login" in strings)
                }
            }
        } finally {
            module.destroy()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `clones real 5-DEX 20MB APK end-to-end quickly and passes ApkVerifier`() = runBlocking {
        val candidateApk = sequenceOf(File("../apk"), File("apk"))
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.asSequence().orEmpty() }
            .firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?: error("Tidak ada APK di folder apk/ untuk diuji end-to-end.")

        val startMs = System.currentTimeMillis()
        val tempDir = java.nio.file.Files.createTempDirectory("openclone-real-apk").toFile()
        try {
            val loaded = ApkModule.loadApkFile(candidateApk)
            loaded.setLoadDefaultFramework(false)
            try {
                val oldPkg = loaded.packageName
                val newPkg = "$oldPkg.clone1"
                val summary = CloneEngine.rewritePackageAndManifest(
                    module = loaded,
                    oldPackage = oldPkg,
                    newPackage = newPkg,
                    compatMode = true,
                )
                CloneEngine.applyCloneLabel(loaded, "OpenClone (Clone 1)")
                val onePxPng = byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                    0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
                    0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
                    0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
                    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
                    0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
                    0x42, 0x60, 0x82.toByte(),
                )
                CloneEngine.replaceIcon(loaded, onePxPng)

                val rules = DexPatcher.buildRules(
                    renamedAuthorities = summary.renamedAuthorities +
                        mapOf("org.bouncycastle.provider" to "org.bouncycastle.providec1"),
                    renamedPermissions = summary.renamedPermissions,
                    renamedAccountTypes = summary.renamedAccountTypes,
                )
                DexPatcher.patchModule(loaded, rules) { _, _ -> }

                val unsigned = File(tempDir, "unsigned.apk")
                val signed = File(tempDir, "signed.apk")
                CloneEngine.writeUnsignedApk(loaded, unsigned)
                signForTest(unsigned, signed)

                val elapsedMs = System.currentTimeMillis() - startMs
                println("Full 5-DEX APK clone + RSA keygen + sign elapsed: ${elapsedMs}ms")

                val verified = ApkVerifier.Builder(signed).build().verify()
                assertTrue("Cloned real APK failed verification: ${verified.errors}", verified.isVerified)
            } finally {
                loaded.destroy()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `merges Facebook-style multi-split AAB (0x7f + 0x80 dynamic feature + spec-only drawable + colon taskAffinity + splitName) and passes aapt2, apksigner, and zipalign`() = runBlocking {
        val aapt2 = requireBuildTool("aapt2")
        val androidJar = requireAndroidJar()

        val oldPkg = "com.facebook.katana"
        val newPkg = "com.facebook.katana.clone1"
        val tempDir = java.nio.file.Files.createTempDirectory("openclone-multisplit").toFile()
        try {
            val onePxPng = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
                0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
                0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
                0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
                0x42, 0x60, 0x82.toByte(),
            )

            // 1. Compile base.apk with xxhdpi-split drawable (leaving spec-only drawable in base!)
            val baseResDir = File(tempDir, "base_res").apply {
                File(this, "values").mkdirs()
                File(this, "drawable-xxhdpi").mkdirs()
                File(this, "drawable-hdpi").mkdirs()
            }
            File(baseResDir, "values/values.xml").writeText(
                """
                <resources>
                    <attr name="fbBaseAttr" format="string"/>
                    <item type="id" name="fb_root_view"/>
                    <string name="app_name">Facebook</string>
                    <string-array name="fb_certs">
                        <item>00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff</item>
                    </string-array>
                </resources>
                """.trimIndent(),
            )
            File(baseResDir, "drawable-xxhdpi/ic_logo.png").writeBytes(onePxPng)
            File(baseResDir, "drawable-hdpi/ic_only_uninstalled_density.png").writeBytes(onePxPng)

            val baseManifestXml = File(tempDir, "BaseManifest.xml")
            baseManifestXml.writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:dist="http://schemas.android.com/apk/distribution"
                    package="$oldPkg"
                    android:versionCode="450000000"
                    android:versionName="450.0.0"
                    android:isolatedSplits="true">
                    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
                    <dist:module dist:instant="false"/>
                    <permission
                        android:name="com.facebook.permission.prod.FB_APP_COMMUNICATION"
                        android:protectionLevel="signature|knownSigner"
                        android:knownCerts="@array/fb_certs"/>
                    <permission android:name="com.facebook.permission.prod.FB_SYNC_1" android:protectionLevel="signature"/>
                    <permission android:name="com.facebook.permission.prod.FB_SYNC_2" android:protectionLevel="signature"/>
                    <uses-permission android:name="com.facebook.permission.prod.FB_APP_COMMUNICATION"/>
                    <application
                        android:name=".FbApp"
                        android:label="@string/app_name"
                        android:icon="@drawable/ic_logo"
                        android:useEmbeddedDex="true"
                        android:zygotePreloadName=".FbZygotePreload">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true"
                            android:taskAffinity=":browser">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                        <activity
                            android:name=".ComposerActivity"
                            android:exported="false"
                            android:taskAffinity="$oldPkg:composer"/>
                        <provider
                            android:name="com.facebook.appmanager.Prov1"
                            android:authorities="com.facebook.appmanager.prov1"
                            android:exported="false"/>
                        <provider
                            android:name="com.facebook.appmanager.Prov2"
                            android:authorities="com.facebook.appmanager.prov2"
                            android:exported="false"/>
                        <provider
                            android:name="com.facebook.ondemand.UninstalledProvider"
                            android:authorities="com.facebook.ondemand.uninstalled"
                            android:splitName="feature_uninstalled"
                            android:enabled="true"
                            android:exported="false"/>
                    </application>
                </manifest>
                """.trimIndent(),
            )

            val baseResZip = File(tempDir, "base_res.zip")
            val baseApk = File(tempDir, "base.apk")
            val splitXxhdpiApk = File(tempDir, "split_xxhdpi.apk")
            val splitHdpiUninstalledApk = File(tempDir, "split_hdpi.apk")
            runCmd(aapt2, "compile", "--dir", baseResDir.absolutePath, "-o", baseResZip.absolutePath)
            runCmd(
                aapt2, "link",
                "-I", androidJar.absolutePath,
                "--manifest", baseManifestXml.absolutePath,
                // aapt2 splits the argument with the platform path separator,
                // so this is ';' on Windows and ':' on Linux/macOS.
                "--split", "${splitXxhdpiApk.absolutePath}${File.pathSeparator}xxhdpi",
                "--split", "${splitHdpiUninstalledApk.absolutePath}${File.pathSeparator}hdpi",
                "-o", baseApk.absolutePath,
                baseResZip.absolutePath,
            )

            // Add compressed classes.dex to base.apk and STORED .so to split_xxhdpi.apk.
            // The copy back must happen AFTER the module is closed: on Windows the
            // source APK stays locked while ApkModule holds it open.
            val baseDex = buildTinyDex("Lcom/facebook/katana/MainActivity;")
            val rewrittenBase = File(tempDir, "base_with_dex.apk")
            ApkModule.loadApkFile(baseApk).use { m ->
                val dexSrc = ByteInputSource(baseDex, "classes.dex").apply { isUncompressed = false }
                m.add(dexSrc)
                m.writeApk(rewrittenBase)
            }
            rewrittenBase.copyTo(baseApk, overwrite = true)

            val rewrittenSplit = File(tempDir, "split_xxhdpi_with_so.apk")
            ApkModule.loadApkFile(splitXxhdpiApk).use { m ->
                val soBytes = ByteArray(8192) { (it and 0x7F).toByte() }
                val soSrc = ByteInputSource(soBytes, "lib/arm64-v8a/libfbjni.so").apply {
                    isUncompressed = true
                }
                m.add(soSrc)
                m.uncompressedFiles.addPath("lib/arm64-v8a/libfbjni.so")
                m.writeApk(rewrittenSplit)
            }
            rewrittenSplit.copyTo(splitXxhdpiApk, overwrite = true)

            // 2. Compile dynamic feature split `feature_rtc` with packageId 0x80!
            val featResDir = File(tempDir, "feat_res").apply { File(this, "values").mkdirs() }
            File(featResDir, "values/values.xml").writeText(
                """
                <resources>
                    <attr name="rtcCustomAttr" format="string"/>
                    <string name="rtc_title">Messenger Rooms</string>
                </resources>
                """.trimIndent(),
            )
            val featManifestXml = File(tempDir, "FeatManifest.xml")
            featManifestXml.writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:app="http://schemas.android.com/apk/res-auto"
                    xmlns:dist="http://schemas.android.com/apk/distribution"
                    package="$oldPkg"
                    split="feature_rtc"
                    android:isFeatureSplit="true">
                    <dist:module dist:onDemand="false">
                        <dist:fusing dist:include="true"/>
                    </dist:module>
                    <application>
                        <activity
                            android:name="com.facebook.rtc.RtcCallActivity"
                            android:label="@string/rtc_title"
                            android:exported="false"
                            android:launchMode="singleTop"
                            android:splitName="feature_rtc"
                            app:rtcCustomAttr="enabled"/>
                        <provider
                            android:name="com.facebook.rtc.RtcProvider"
                            android:authorities="com.facebook.rtc.provider"
                            android:splitName="feature_rtc"
                            android:enabled="true"
                            android:exported="false"/>
                    </application>
                </manifest>
                """.trimIndent(),
            )
            val featResZip = File(tempDir, "feat_res.zip")
            val splitRtcApk = File(tempDir, "split_rtc.apk")
            runCmd(aapt2, "compile", "--dir", featResDir.absolutePath, "-o", featResZip.absolutePath)
            runCmd(
                aapt2, "link",
                "-I", androidJar.absolutePath,
                "-I", baseApk.absolutePath,
                "--manifest", featManifestXml.absolutePath,
                "--package-id", "0x80",
                "--allow-reserved-package-id",
                "-o", splitRtcApk.absolutePath,
                featResZip.absolutePath,
            )
            val rtcDex = buildTinyDex("Lcom/facebook/rtc/RtcCallActivity;")
            val rewrittenRtc = File(tempDir, "split_rtc_with_dex.apk")
            ApkModule.loadApkFile(splitRtcApk).use { m ->
                val dexSrc = ByteInputSource(rtcDex, "classes.dex").apply { isUncompressed = true }
                m.add(dexSrc)
                m.uncompressedFiles.addPath("classes.dex")
                m.writeApk(rewrittenRtc)
            }
            rewrittenRtc.copyTo(splitRtcApk, overwrite = true)

            // 3. Merge & clone (note: splitHdpiUninstalledApk is intentionally NOT installed!)
            val bundle = CloneEngine.loadFromFiles(listOf(baseApk, splitXxhdpiApk, splitRtcApk))
            val unsignedOut = File(tempDir, "clone_unsigned.apk")
            val signedOut = File(tempDir, "clone_signed.apk")
            try {
                val merged = bundle.module
                val summary = CloneEngine.rewritePackageAndManifest(
                    module = merged,
                    oldPackage = oldPkg,
                    newPackage = newPkg,
                    compatMode = true,
                    mergedSplitNames = bundle.mergedSplitNames,
                    hasSplits = bundle.hasSplits,
                )
                CloneEngine.applyCloneLabel(merged, "Facebook (Clone 1)")
                val rules = DexPatcher.buildRules(
                    renamedAuthorities = summary.renamedAuthorities,
                    renamedPermissions = summary.renamedPermissions,
                    renamedAccountTypes = summary.renamedAccountTypes,
                )
                DexPatcher.patchModule(merged, rules) { _, _ -> }
                CloneEngine.writeUnsignedApk(merged, unsignedOut)
                signForTest(unsignedOut, signedOut, v1Enabled = false)
            } finally {
                bundle.module.destroy()
                bundle.splitModules.forEach { it.destroy() }
            }

            // 4. Verify with AOSP aapt2, apksigner, and zipalign!
            val badging = runCmd(aapt2, "dump", "badging", signedOut.absolutePath)
            assertTrue("Missing cloned package in badging:\n$badging", badging.contains("name='$newPkg'"))
            assertTrue("Missing cloned label in badging:\n$badging", badging.contains("Facebook (Clone 1)"))
            assertTrue(
                "Missing launchable activity in badging:\n$badging",
                badging.contains("launchable-activity: name='$oldPkg.MainActivity'"),
            )

            val resourcesDump = runCmd(aapt2, "dump", "resources", signedOut.absolutePath)
            assertFalse("Corrupted placeholder type name in resources.arsc:\n$resourcesDump", resourcesDump.contains("type-"))
            assertTrue("Missing 0x7f drawable type in resources.arsc:\n$resourcesDump", resourcesDump.contains("drawable/ic_logo"))
            assertTrue("Missing 0x80 feature resources in resources.arsc:\n$resourcesDump", resourcesDump.contains("string/rtc_title"))

            val apksignerBin = requireBuildTool("apksigner")
            runCmd(apksignerBin, "verify", "--min-sdk-version", "26", signedOut.absolutePath)
            val zipalignBin = requireBuildTool("zipalign")
            runCmd(zipalignBin, "-c", "4", signedOut.absolutePath)

            ZipFile(signedOut).use { zf ->
                assertEquals(ZipEntry.STORED, zf.getEntry("resources.arsc").method)
                assertEquals(ZipEntry.STORED, zf.getEntry("lib/arm64-v8a/libfbjni.so").method)
                assertEquals(ZipEntry.STORED, zf.getEntry("classes2.dex").method)
            }

            // 5. Inspect binary AndroidManifest.xml invariants!
            ApkModule.loadApkFile(signedOut).use { verifyMod ->
                verifyMod.setLoadDefaultFramework(false)
                val m = verifyMod.androidManifest
                val app = m.applicationElement
                assertEquals("$oldPkg.FbZygotePreload", CloneEngine.findAttr(app, CloneEngine.ID_ZYGOTE_PRELOAD_NAME, "zygotePreloadName")?.valueAsString)
                assertNull("useEmbeddedDex should be stripped when classes.dex is compressed", CloneEngine.findAttr(app, CloneEngine.ID_USE_EMBEDDED_DEX, "useEmbeddedDex"))

                // Every element's non-zero attribute resource IDs MUST be in strict
                // unsigned ascending order and precede all id==0 attributes!
                m.recursiveElements().forEachRemaining { el ->
                    assertNull("Residual splitName on <${el.name}>", CloneEngine.findAttr(el, CloneEngine.ID_SPLIT_NAME, "splitName"))
                    assertFalse("Residual <dist:module>", el.name == "module")
                    var seenZeroId = false
                    var prevUnsignedId = 0
                    el.attributes.forEachRemaining { attr ->
                        val id = attr.nameId
                        if (id == 0) {
                            seenZeroId = true
                        } else {
                            assertFalse(
                                "Resource-ID attribute ${Integer.toHexString(id)} appeared AFTER id==0 attribute on <${el.name}>",
                                seenZeroId,
                            )
                            assertTrue(
                                "Unsigned attribute order violated on <${el.name}>: 0x${Integer.toHexString(prevUnsignedId)} > 0x${Integer.toHexString(id)}",
                                prevUnsignedId == 0 || Integer.compareUnsigned(prevUnsignedId, id) <= 0,
                            )
                            prevUnsignedId = id
                        }
                    }
                }

                // Verify taskAffinity values
                val activities = mutableMapOf<String, ResXmlElement>()
                app.getElements { it.name == "activity" }.forEachRemaining { el ->
                    val n = CloneEngine.findAttr(el, CloneEngine.ID_NAME, "name")?.valueAsString.orEmpty()
                    activities[n] = el
                }
                assertEquals(
                    ":browser",
                    CloneEngine.findAttr(activities.getValue("$oldPkg.MainActivity"), CloneEngine.ID_TASK_AFFINITY, "taskAffinity")?.valueAsString,
                )
                assertEquals(
                    ":composer",
                    CloneEngine.findAttr(activities.getValue("$oldPkg.ComposerActivity"), CloneEngine.ID_TASK_AFFINITY, "taskAffinity")?.valueAsString,
                )
                assertTrue("Merged feature activity missing", "com.facebook.rtc.RtcCallActivity" in activities)

                // Verify providers: Prov1 & Prov2 both survived without collision, RtcProvider stayed enabled, UninstalledProvider disabled!
                val providers = mutableMapOf<String, ResXmlElement>()
                app.getElements { it.name == "provider" }.forEachRemaining { el ->
                    val n = CloneEngine.findAttr(el, CloneEngine.ID_NAME, "name")?.valueAsString.orEmpty()
                    providers[n] = el
                }
                assertTrue("com.facebook.appmanager.Prov1" in providers)
                assertTrue("com.facebook.appmanager.Prov2" in providers)
                val rtcProvEnabled = CloneEngine.findAttr(providers.getValue("com.facebook.rtc.RtcProvider"), CloneEngine.ID_ENABLED, "enabled")?.valueAsBoolean
                assertEquals(true, rtcProvEnabled)
                val uninstalledProvEnabled = CloneEngine.findAttr(providers.getValue("com.facebook.ondemand.UninstalledProvider"), CloneEngine.ID_ENABLED, "enabled")?.valueAsBoolean
                assertEquals(false, uninstalledProvEnabled)

                // Verify knownCerts & knownSigner flag cleared on <permission>
                m.manifestElement.getElements { it.name == "permission" }.forEachRemaining { perm ->
                    assertNull(CloneEngine.findAttr(perm, CloneEngine.ID_KNOWN_CERTS, "knownCerts"))
                    val prot = CloneEngine.findAttr(perm, CloneEngine.ID_PROTECTION_LEVEL, "protectionLevel")
                    if (prot != null && prot.valueType != ValueType.STRING) {
                        assertEquals(0, prot.data and 0x04000000)
                    }
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun buildTinyDex(classDescriptor: String): ByteArray {
        val dex = DexFile.createDefault()
        val c = dex.orCreateFirst.getOrCreateClass(TypeKey(classDescriptor))
        c.setSuperClass(TypeKey("Ljava/lang/Object;"))
        val m = c.getOrCreateDirectMethod(MethodKey.parse("$classDescriptor->ping()V"))
        m.addAccessFlag(com.reandroid.dex.common.AccessFlag.STATIC)
        m.localRegistersCount = 0
        m.definition.orCreateInstructionList.createNext(Opcode.RETURN_VOID)
        dex.refreshFull()
        val bytes = dex.bytes
        dex.close()
        return bytes
    }

    /**
     * Resolves the Android SDK the same way the Gradle build does, so these
     * tests run on CI (`/usr/local/lib/android/sdk`), on a Windows machine with
     * `ANDROID_HOME`, and on any checkout that has `local.properties`.
     *
     * A missing SDK is a hard failure on purpose: the previous version returned
     * early and reported success without verifying anything.
     */
    private fun requireSdk(): File {
        val fromLocalProperties = sequenceOf(File("../local.properties"), File("local.properties"))
            .firstOrNull { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.trim().startsWith("sdk.dir=") }
            ?.trim()
            ?.removePrefix("sdk.dir=")
            ?.replace("\\\\", "\\")
            ?.replace("\\:", ":")
            ?.trim()
        return sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("android.home"),
            fromLocalProperties,
        ).filterNotNull()
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: error(
                "Android SDK tidak ditemukan. Set ANDROID_HOME atau ANDROID_SDK_ROOT " +
                    "(atau sdk.dir di local.properties) sebelum menjalankan test integrasi.",
            )
    }

    /** Newest installed build-tools entry that actually ships [name]. */
    private fun requireBuildTool(name: String): File {
        val buildToolsDir = File(requireSdk(), "build-tools")
        // Prefer the build-tools that match compileSdk so local runs behave like CI.
        val preferred = listOf("34.0.0")
        val dirs = buildToolsDir.listFiles().orEmpty().filter { it.isDirectory }
        val ordered = dirs.sortedByDescending { versionKey(it.name) }
        val searchOrder = preferred.mapNotNull { want -> dirs.firstOrNull { it.name == want } } + ordered
        val candidates = searchOrder.distinct().flatMap { dir ->
            listOf("$name.exe", "$name.bat", "$name.cmd", name).map { File(dir, it) }
        }
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "$name tidak ditemukan di ${buildToolsDir.absolutePath}. " +
                    "Pasang build-tools lewat sdkmanager.",
            )
    }

    private fun requireAndroidJar(): File {
        val platformsDir = File(requireSdk(), "platforms")
        val installed = platformsDir.listFiles().orEmpty().filter { it.isDirectory }
        val ordered = installed.sortedByDescending { versionKey(it.name) }
        val preferred = installed.firstOrNull { it.name == "android-34" } ?: ordered.firstOrNull()
        val jar = preferred?.let { File(it, "android.jar") }
        return jar?.takeIf { it.isFile }
            ?: error("android.jar tidak ditemukan di ${platformsDir.absolutePath}.")
    }

    /** `android-34` -> `34`, `36.0.0` -> `36.0.0` padded so string order matches version order. */
    private fun versionKey(dirName: String): String {
        val digits = Regex("""\d+(\.\d+)*""").find(dirName)?.value ?: return ""
        return digits.split('.').joinToString(".") { it.padStart(4, '0') }
    }

    /** Windows cannot exec a `.bat` directly, so batch tools go through `cmd /c`. */
    private fun commandOf(tool: File, vararg args: String): Array<String> {
        val isBatch = tool.name.endsWith(".bat", ignoreCase = true) ||
            tool.name.endsWith(".cmd", ignoreCase = true)
        return if (isBatch) {
            arrayOf("cmd", "/c", tool.absolutePath) + args
        } else {
            arrayOf(tool.absolutePath) + args
        }
    }

    private fun runCmd(tool: File, vararg args: String): String =
        runCmd(*commandOf(tool, *args))

    private fun runCmd(vararg command: String): String {
        val proc = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals("Command failed (${command.joinToString(" ")}):\n$out", 0, code)
        return out
    }

    private fun signForTest(unsignedApk: File, signedApk: File, v1Enabled: Boolean = true) {
        val keyGen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
        val keyPair = keyGen.generateKeyPair()
        val now = Date()
        val later = Date(now.time + 365L * 24 * 3600 * 1000)
        val issuer = X500Name("CN=Test")
        val certHolder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.ONE,
            now,
            later,
            issuer,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val cert = JcaX509CertificateConverter().getCertificate(certHolder)
        val config = ApkSigner.SignerConfig.Builder("test", keyPair.private, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(v1Enabled)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
    }
}
