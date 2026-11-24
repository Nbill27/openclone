package dev.bill.openclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneConfigTest {

    @Test
    fun `valid package names are accepted`() {
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("com.facebook.aura.klon1"))
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
    fun `suggestClonePackage appends klon1 when free`() {
        assertEquals(
            "com.example.app.klon1",
            suggestClonePackage("com.example.app", setOf("com.other.app")),
        )
    }

    @Test
    fun `suggestClonePackage increments until free`() {
        assertEquals(
            "com.example.app.klon3",
            suggestClonePackage(
                "com.example.app",
                setOf("com.example.app.klon1", "com.example.app.klon2"),
            ),
        )
    }

    @Test
    fun `suggestClonePackage keeps original when it already is a clone base`() {
        assertEquals(
            "com.example.app.klon1",
            suggestClonePackage("com.example.app", emptySet()),
        )
    }

    @Test
    fun `suggestCloneLabel appends 2`() {
        assertEquals("Muse 2", suggestCloneLabel("Muse"))
        assertEquals("BCA mobile 2", suggestCloneLabel("BCA mobile"))
    }

    @Test
    fun `suggestClonePackage keeps counting past nine`() {
        val taken = (1..9).map { "com.example.app.klon$it" }.toSet()
        assertEquals("com.example.app.klon10", suggestClonePackage("com.example.app", taken))
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
}

