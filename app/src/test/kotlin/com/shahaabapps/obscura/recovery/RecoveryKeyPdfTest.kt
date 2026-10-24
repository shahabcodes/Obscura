package com.shahaabapps.obscura.recovery

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Date

/** The recovery-key PDF, checked with Apache PDFBox: a real, independent PDF reader. */
class RecoveryKeyPdfTest {

    private val key = "7KQ2-M9XA-T4RH-WC1E-3PZN-B8DV-Y6JF-0GSU"
    private val vaultId = ByteArray(16) { (it * 17 + 3).toByte() }

    private fun pdf(password: String = "violet harbour ninety") =
        RecoveryKeyPdf.build(key, vaultId, password.toCharArray(), Date(1_790_000_000_000))

    @Test
    fun wrongOrMissingPasswordDoesNotOpenIt() {
        val bytes = pdf()
        for (attempt in listOf("", "violet harbour", "VIOLET HARBOUR NINETY")) {
            try {
                Loader.loadPDF(bytes, attempt).close()
                fail("opened with \"$attempt\"")
            } catch (expected: InvalidPasswordException) {
            }
        }
    }

    @Test
    fun theKeyIsNotReadableInTheFile() {
        val raw = String(pdf(), Charsets.ISO_8859_1)
        for (group in key.split('-')) assertFalse("$group in plain text", raw.contains(group))
        assertFalse(raw.contains("Recovery key"))
        assertTrue(raw.contains("/R 6") && raw.contains("/AESV3"))
    }

    @Test
    fun theRightPasswordOpensItWithTheKeyOnThePage() {
        val document = Loader.loadPDF(pdf(), "violet harbour ninety")
        try {
            assertEquals(1, document.numberOfPages)
            assertTrue(document.isEncrypted)
            assertEquals(6, document.encryption.revision)
            assertEquals(256, document.encryption.length)
            val text = PDFTextStripper().getText(document)
            assertTrue(text, text.contains("7KQ2  M9XA  T4RH  WC1E"))
            assertTrue(text, text.contains("3PZN  B8DV  Y6JF  0GSU"))
            assertTrue(text.contains("Recovery key"))
            assertTrue(text.contains(RecoveryKeyPdf.fingerprint(vaultId)))
            // Print-only: no editing, and no copying the key out as text.
            val access = document.currentAccessPermission
            assertTrue(access.canPrint())
            assertTrue(access.canPrintFaithful())
            assertFalse(access.canModify())
            assertFalse(access.canExtractContent())
            // A picture of the page, for a visual check. Reflection, because the unit-test
            // compile classpath is Android's, which has no java.awt.
            val renderer = Class.forName("org.apache.pdfbox.rendering.PDFRenderer").getConstructor(document.javaClass).newInstance(document)
            val image = renderer.javaClass.getMethod("renderImageWithDPI", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).invoke(renderer, 0, 96f)
            Class.forName("javax.imageio.ImageIO")
                .getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
                .invoke(null, image, "png", File("build/recovery-key-pdf.png"))
        } finally {
            document.close()
        }
    }

    @Test
    fun eachPdfIsDifferentEvenWithTheSamePassword() {
        assertFalse(pdf().contentEquals(pdf()))
    }

    @Test
    fun nonAsciiPasswordsWork() {
        val bytes = RecoveryKeyPdf.build(key, vaultId, "çlé secrète 🔐".toCharArray())
        Loader.loadPDF(bytes, "çlé secrète 🔐").close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAMalformedKey() {
        RecoveryKeyPdf.build("ABCD-EFGH", vaultId, "x".toCharArray())
    }

    @Test
    fun wrapsLongAdviceLines() {
        val lines = RecoveryKeyPdf.wrap("word ".repeat(60).trim(), 11.0, 200.0)
        assertTrue(lines.size > 3)
        assertTrue(lines.all { it.length < 50 })
    }
}
