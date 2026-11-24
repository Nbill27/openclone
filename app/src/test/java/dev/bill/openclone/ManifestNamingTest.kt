package dev.bill.openclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OLD = "com.example.app"
private const val NEW = "com.example.app.klon1"

class ManifestNamingTest {

    @Test
    fun `authority equal to old package becomes new package`() {
        assertEquals(NEW, ManifestNaming.renameAuthorities(OLD, OLD, NEW))
    }

    @Test
    fun `authority under old package gets the prefix swapped`() {
        assertEquals(
            "$NEW.provider",
            ManifestNaming.renameAuthorities("$OLD.provider", OLD, NEW),
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
    fun `process names derived from the old package are rewritten`() {
        assertEquals("$NEW:remote", ManifestNaming.renameProcess("$OLD:remote", OLD, NEW))
        assertEquals("$NEW.worker", ManifestNaming.renameProcess("$OLD.worker", OLD, NEW))
    }

    @Test
    fun `unrelated process names are left untouched`() {
        assertNull(ManifestNaming.renameProcess("com.other:remote", OLD, NEW))
        assertNull(ManifestNaming.renameProcess("android:ui", OLD, NEW))
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
                "android.permission.CAMERA",
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
    fun `bare declared permission name is namespaced when not ownership bound`() {
        assertEquals(
            "$NEW.READ_DATA",
            ManifestNaming.renamePermissionName("READ_DATA", OLD, NEW, mustBeOwned = false),
        )
    }

    @Test
    fun `third party dotted permission declaration is left alone`() {
        assertNull(
            ManifestNaming.renamePermissionName(
                "com.other.app.permission.SHARE",
                OLD,
                NEW,
                mustBeOwned = false,
            ),
        )
    }

    @Test
    fun `already renamed names are not prefixed a second time`() {
        // The new package starts with the old one, so a missing guard would
        // turn "com.example.app.klon1.X" into "com.example.app.klon1.klon1.X".
        assertNull(
            ManifestNaming.renamePermissionName(
                "$NEW.permission.C2D_MESSAGE",
                OLD,
                NEW,
                mustBeOwned = true,
            ),
        )
        assertNull(ManifestNaming.renameProcess("$NEW:remote", OLD, NEW))
    }

    @Test
    fun `signature files are detected`() {
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/MANIFEST.MF"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.SF"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.RSA"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.DSA"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/CERT.EC"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/SIG-FOO"))
        assertTrue(ManifestNaming.isSignatureEntry("META-INF/cert.sf"))
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
