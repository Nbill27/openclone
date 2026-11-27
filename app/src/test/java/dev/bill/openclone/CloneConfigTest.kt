package dev.bill.openclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneConfigTest {

    @Test
    fun `valid package names are accepted`() {
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("com.facebook.aura.clone1"))
        assertTrue(isValidPackageName("dev.bill.openclone"))
        assertTrue(isValidPackageName("a.b"))
        assertTrue(isValidPackageName("com.app2_name.x"))
    }

    @Test
    fun `invalid package names are rejected`() {
        assertFalse(isValidPackageName(""))
        assertFalse(isValidPackageName("singleword"))
        assertFalse(isValidPackageName("com..example"))
        assertFalse(isValidPackageName(".com.example"))
        assertFalse(isValidPackageName("com.example."))
        assertFalse(isValidPackageName("com.2example.app"))
        assertFalse(isValidPackageName("com.ex-ample.app"))
        assertFalse(isValidPackageName("com.example app"))
    }

    @Test
    fun `suggestClonePackage appends clone1 when free`() {
        assertEquals(
            "com.example.app.clone1",
            suggestClonePackage("com.example.app", setOf("com.other.app")),
        )
    }

    @Test
    fun `suggestClonePackage increments until free`() {
        assertEquals(
            "com.example.app.clone3",
            suggestClonePackage(
                "com.example.app",
                setOf("com.example.app.clone1", "com.example.app.clone2"),
            ),
        )
    }

    @Test
    fun `suggestClonePackage ignores unrelated existing packages`() {
        assertEquals(
            "com.example.app.clone1",
            suggestClonePackage("com.example.app", emptySet()),
        )
    }

    @Test
    fun `suggestCloneLabel appends matching clone number suffix`() {
        assertEquals("Muse (Clone 1)", suggestCloneLabel("Muse"))
        assertEquals("BCA mobile (Clone 1)", suggestCloneLabel("BCA mobile", 1))
        assertEquals("Facebook (Clone 2)", suggestCloneLabel("Facebook", 2))
    }

    @Test
    fun `suggestClonePackage keeps counting past nine`() {
        val taken = (1..9).map { "com.example.app.clone$it" }.toSet()
        assertEquals("com.example.app.clone10", suggestClonePackage("com.example.app", taken))
    }

    @Test
    fun `isValidPackageName rejects names that are too long`() {
        assertFalse(isValidPackageName("com." + "a".repeat(200)))
    }

    @Test
    fun `isValidPackageName rejects a digit at the start of any segment`() {
        assertFalse(isValidPackageName("com.example.2app"))
        assertFalse(isValidPackageName("2com.example.app"))
    }

    @Test
    fun `suggestClonePackage output is itself a valid package name`() {
        assertTrue(isValidPackageName(suggestClonePackage("com.example.app", emptySet())))
    }

    @Test
    fun `extractCloneNumber reads clone index from package or label`() {
        assertEquals(3, extractCloneNumber("com.facebook.katana.clone3", "Facebook"))
        assertEquals(5, extractCloneNumber("com.custom.pkg", "Facebook (Clone 5)"))
        assertEquals(2, extractCloneNumber("com.custom.pkg", "Custom", fallback = 2))
    }

    @Test
    fun `buildBatchCloneSpecs skips already installed clones and increments cleanly`() {
        val existing = setOf("com.facebook.katana.clone1", "com.facebook.katana.clone3")
        val specs = buildBatchCloneSpecs(
            originalPackage = "com.facebook.katana",
            originalLabel = "Facebook",
            customLabelInput = "Facebook",
            customPackageInput = "com.facebook.katana.clone2",
            count = 3,
            existingPackages = existing,
        )
        assertEquals(
            listOf(
                BatchCloneSpec(2, "Facebook (Clone 2)", "com.facebook.katana.clone2"),
                BatchCloneSpec(4, "Facebook (Clone 4)", "com.facebook.katana.clone4"),
                BatchCloneSpec(5, "Facebook (Clone 5)", "com.facebook.katana.clone5"),
            ),
            specs,
        )
    }

    @Test
    fun `buildBatchCloneSpecs supports custom base label for multiple clones`() {
        val specs = buildBatchCloneSpecs(
            originalPackage = "com.facebook.katana",
            originalLabel = "Facebook",
            customLabelInput = "FB Toko",
            customPackageInput = "com.facebook.katana.clone1",
            count = 2,
            existingPackages = emptySet(),
        )
        assertEquals(
            listOf(
                BatchCloneSpec(1, "FB Toko 1", "com.facebook.katana.clone1"),
                BatchCloneSpec(2, "FB Toko 2", "com.facebook.katana.clone2"),
            ),
            specs,
        )
    }
}
