package dev.bill.openclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OLD = "com.facebook.katana"
private const val NEW = "com.facebook.katana.clone1"

class ManifestNamingTest {

    @Test
    fun `authority equal to old package becomes new package`() {
        assertEquals(NEW, ManifestNaming.renameAuthorities(OLD, OLD, NEW))
    }

    @Test
    fun `authority under old package gets the prefix swapped`() {
        assertEquals(
            "$NEW.provider.KeyValueProvider",
            ManifestNaming.renameAuthorities("$OLD.provider.KeyValueProvider", OLD, NEW),
        )
        assertEquals(
            "$NEW.work.FileProvider",
            ManifestNaming.renameAuthorities("$OLD.work.FileProvider", OLD, NEW),
        )
    }

    @Test
    fun `authority already under new package is left alone`() {
        assertEquals(
            "$NEW.provider",
            ManifestNaming.renameAuthorities("$NEW.provider", OLD, NEW),
        )
        assertEquals(NEW, ManifestNaming.renameAuthorities(NEW, OLD, NEW))
    }

    @Test
    fun `bare vendor authority is namespaced so clones do not collide`() {
        assertEquals(
            "$NEW.com.facebook.appmanager",
            ManifestNaming.renameAuthorities("com.facebook.appmanager", OLD, NEW),
        )
        assertEquals("$NEW.downloads", ManifestNaming.renameAuthorities("downloads", OLD, NEW))
    }

    @Test
    fun `semicolon separated authorities are rewritten one by one`() {
        assertEquals(
            "$NEW.a;$NEW.com.vendor.b;$NEW.c",
            ManifestNaming.renameAuthorities("$OLD.a;com.vendor.b;c", OLD, NEW),
        )
    }

    @Test
    fun `empty and trailing semicolon authority segments survive`() {
        assertEquals("$NEW.a;;$NEW.b", ManifestNaming.renameAuthorities("$OLD.a;;b", OLD, NEW))
        assertEquals("$NEW.a;", ManifestNaming.renameAuthorities("$OLD.a;", OLD, NEW))
    }

    @Test
    fun `process names equal to or derived from old package are rewritten`() {
        assertEquals(NEW, ManifestNaming.renameProcess(OLD, OLD, NEW))
        assertEquals("$NEW:mqtt", ManifestNaming.renameProcess("$OLD:mqtt", OLD, NEW))
        assertEquals("$NEW.worker", ManifestNaming.renameProcess("$OLD.worker", OLD, NEW))
    }

    @Test
    fun `unrelated or private colon process names are left untouched`() {
        assertNull(ManifestNaming.renameProcess(":mqtt", OLD, NEW))
        assertNull(ManifestNaming.renameProcess("com.other:remote", OLD, NEW))
        assertNull(ManifestNaming.renameProcess("android:ui", OLD, NEW))
    }

    @Test
    fun `taskAffinity is isolated per clone while empty and colon-prefixed names stay valid`() {
        assertNull(ManifestNaming.renameTaskAffinity("", OLD, NEW))
        // Leading-colon affinities are already scoped to newPackage by AOSP buildCompoundName!
        assertNull(ManifestNaming.renameTaskAffinity(":browser", OLD, NEW))
        assertNull(ManifestNaming.renameTaskAffinity(":video.pip", OLD, NEW))
        assertEquals(NEW, ManifestNaming.renameTaskAffinity(OLD, OLD, NEW))
        assertEquals("$NEW.composer", ManifestNaming.renameTaskAffinity("$OLD.composer", OLD, NEW))
        assertEquals(":composer", ManifestNaming.renameTaskAffinity("$OLD:composer", OLD, NEW))
        assertEquals(
            "$NEW.com.facebook.task",
            ManifestNaming.renameTaskAffinity("com.facebook.task", OLD, NEW),
        )
        // Foreign compound or non-letter-front affinities become valid :oc_... names!
        assertEquals(
            ":oc_com_facebook_orca_login",
            ManifestNaming.renameTaskAffinity("com.facebook.orca:login", OLD, NEW),
        )
        assertEquals(
            ":oc_123_task",
            ManifestNaming.renameTaskAffinity("123_task", OLD, NEW),
        )
        assertNull(ManifestNaming.renameTaskAffinity(NEW, OLD, NEW))
    }

    @Test
    fun `same-length vendor mutation avoids collisions when identifiers differ only in last 2 chars`() {
        val used = mutableSetOf<String>()
        val a1 = ManifestNaming.renameAuthorities(
            value = "com.facebook.appmanager.prov1",
            oldPackage = OLD,
            newPackage = NEW,
            sameLengthVendor = true,
            usedTargets = used,
        )
        val a2 = ManifestNaming.renameAuthorities(
            value = "com.facebook.appmanager.prov2",
            oldPackage = OLD,
            newPackage = NEW,
            sameLengthVendor = true,
            usedTargets = used,
        )
        assertEquals("com.facebook.appmanager.prov1".length, a1.length)
        assertEquals("com.facebook.appmanager.prov2".length, a2.length)
        assertFalse("Colliding authorities generated: $a1 == $a2", a1 == a2)
    }

    @Test
    fun `declared permission under old package is renamed`() {
        assertEquals(
            "$NEW.permission.C2D_MESSAGE",
            ManifestNaming.renamePermissionName(
                "$OLD.permission.C2D_MESSAGE",
                OLD,
                NEW,
                mustBeOwned = true,
            ),
        )
    }

    @Test
    fun `platform permissions are never renamed when ownership is required`() {
        assertNull(
            ManifestNaming.renamePermissionName(
                "android.permission.INTERNET",
                OLD,
                NEW,
                mustBeOwned = true,
            ),
        )
        assertNull(
            ManifestNaming.renamePermissionName(
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                OLD,
                NEW,
                mustBeOwned = true,
            ),
        )
        assertNull(
            ManifestNaming.renamePermissionName(
                "com.other.app.permission.SHARE",
                OLD,
                NEW,
                mustBeOwned = true,
            ),
        )
    }

    @Test
    fun `bare and dotted vendor permission declarations are namespaced to prevent duplicate permission`() {
        assertEquals(
            "$NEW.READ_DATA",
            ManifestNaming.renamePermissionName("READ_DATA", OLD, NEW, mustBeOwned = false),
        )
        assertEquals(
            "$NEW.com.facebook.permission.prod.FB_APP_COMMUNICATION",
            ManifestNaming.renamePermissionName(
                "com.facebook.permission.prod.FB_APP_COMMUNICATION",
                OLD,
                NEW,
                mustBeOwned = false,
            ),
        )
    }

    @Test
    fun `uses-permission matching a declared vendor permission is rewritten via lookup map`() {
        val declared = mapOf(
            "com.facebook.permission.prod.FB_APP_COMMUNICATION" to
                "$NEW.com.facebook.permission.prod.FB_APP_COMMUNICATION",
        )
        assertEquals(
            "$NEW.com.facebook.permission.prod.FB_APP_COMMUNICATION",
            ManifestNaming.renamePermissionName(
                "com.facebook.permission.prod.FB_APP_COMMUNICATION",
                OLD,
                NEW,
                mustBeOwned = true,
                declaredPermissions = declared,
            ),
        )
    }

    @Test
    fun `already renamed names are not prefixed a second time`() {
        assertNull(
            ManifestNaming.renamePermissionName(
                "$NEW.permission.C2D_MESSAGE",
                OLD,
                NEW,
                mustBeOwned = true,
            ),
        )
        assertNull(ManifestNaming.renameProcess("$NEW:mqtt", OLD, NEW))
        assertNull(ManifestNaming.renameProcess(NEW, OLD, NEW))
    }

    @Test
    fun `signature and Play Source Stamp files are detected`() {
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/MANIFEST.MF"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.SF"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.RSA"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.DSA"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.EC"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/SIG-FOO"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/cert.sf"))
        assertTrue(ManifestNaming.isSignatureEntry("stamp-cert-sha256"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/stamp-cert-sha256"))
    }

    @Test
    fun `payload files inside META-INF are kept`() {
        assertFalse(ManifestNaming.isSignatureEntry("META-INF/services/java.sql.Driver"))
        assertFalse(ManifestNaming.isSignatureEntry("META-INF/app.kotlin_module"))
        assertFalse(ManifestNaming.isSignatureEntry("META-INF/com/android/build/gradle/app-metadata.properties"))
        assertFalse(ManifestNaming.isSignatureEntry("META-INF/AL2.0"))
        assertFalse(ManifestNaming.isSignatureEntry("classes.dex"))
        assertFalse(ManifestNaming.isSignatureEntry("res/raw/sig.txt"))
    }
}
