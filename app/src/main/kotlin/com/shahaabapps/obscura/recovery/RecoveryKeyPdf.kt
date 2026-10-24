package com.shahaabapps.obscura.recovery

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A one-page A4 PDF with the recovery key, encrypted with the standard PDF AES-256 security
 * handler (revision 6, ISO 32000-2). Every PDF viewer can open it, but only with the password
 * chosen when saving; without it the page is AES-256 ciphertext. Printing is allowed, editing
 * is not.
 *
 * Written by hand because Android's PdfDocument can't encrypt. Plain JVM code, so it is
 * unit-tested against a real PDF reader.
 */
object RecoveryKeyPdf {

    /**
     * @param recoveryKey the key as shown in the app: 8 groups of 4, joined by dashes.
     * @param vaultId shown as a short fingerprint, so the page can be matched to its vault.
     * @param password opens the PDF. The caller wipes it.
     */
    fun build(recoveryKey: String, vaultId: ByteArray, password: CharArray, created: Date = Date(), random: SecureRandom = SecureRandom()): ByteArray {
        val groups = recoveryKey.split('-')
        require(groups.size == 8 && groups.all { it.length == 4 }) { "Expected 8 groups of 4" }
        val fileKey = ByteArray(32).also(random::nextBytes)
        val security = StandardSecurity(passwordBytes(password), fileKey, random)
        val content = deflate(page(groups, fingerprint(vaultId), created).toByteArray(Charsets.ISO_8859_1))
        val stream = security.encrypt(content)
        val id = ByteArray(16).also(random::nextBytes)

        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Extensions << /ADBE << /BaseVersion /1.7 /ExtensionLevel 8 >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R /F3 6 0 R >> >> /Contents 7 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>",
            null, // the content stream, written below
            security.dictionary(),
        )
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.7\n%âãÏÓ\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, body ->
            offsets += out.size()
            write("${index + 1} 0 obj\n")
            if (body != null) {
                write(body)
            } else {
                write("<< /Length ${stream.size} /Filter /FlateDecode >>\nstream\n")
                out.write(stream)
                write("\nendstream")
            }
            write("\nendobj\n")
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Encrypt 8 0 R /ID [<${hex(id)}> <${hex(id)}>] >>\nstartxref\n$xref\n%%EOF\n")
        fileKey.fill(0)
        return out.toByteArray()
    }

    /** Short, non-secret vault fingerprint, for example "3F9A-07C2". */
    fun fingerprint(vaultId: ByteArray): String = hex(vaultId.copyOf(4)).uppercase().chunked(4).joinToString("-")

    // ---- Page layout (A4 in points, origin at the bottom left) ----

    private const val INK = "0.043 0.043 0.047"
    private const val SUN = "1 0.882 0.302"
    private const val PINK = "1 0.478 0.714"
    private const val COBALT = "0.231 0.357 1"
    private const val MUTED = "0.42 0.42 0.4"

    private fun page(groups: List<String>, fingerprint: String, created: Date): String = buildString {
        // Header band with the Prism shapes.
        append("$INK rg 0 752 595 90 re f\n")
        append("$SUN rg ${circle(535.0, 797.0, 26.0)} f\n")
        append("$PINK rg 470 778 36 36 re f\n")
        append("$COBALT rg 424 778 m 460 778 l 442 812 l h f\n")
        text("F2", 26.0, 48.0, 787.0, "1 1 1", "Obscura")
        text("F1", 10.0, 48.0, 770.0, "0.75 0.75 0.72", "OFFLINE ENCRYPTED VAULT")

        text("F2", 34.0, 48.0, 690.0, INK, "Recovery key")
        text("F1", 13.0, 48.0, 666.0, MUTED, "Opens your vault if you forget your passphrase, PIN and fingerprint.")

        // The key, in a heavy frame with a sun-coloured tab.
        append("$SUN rg 48 618 120 22 re f\n")
        text("F2", 10.0, 58.0, 625.0, INK, "RECOVERY KEY")
        append("$INK RG 3 w 48 470 499 148 re S\n")
        val rowWidth = 22 * 0.6 * 30 // Courier: every glyph is 0.6 em wide
        val x = 48 + (499 - rowWidth) / 2
        text("F3", 30.0, x, 562.0, INK, groups.take(4).joinToString("  "))
        text("F3", 30.0, x, 508.0, INK, groups.drop(4).joinToString("  "))

        val date = DateFormat.getDateInstance(DateFormat.LONG, Locale.UK).format(created)
        text("F2", 10.0, 48.0, 446.0, INK, "VAULT")
        text("F1", 11.0, 100.0, 446.0, INK, fingerprint)
        text("F2", 10.0, 220.0, 446.0, INK, "CREATED")
        text("F1", 11.0, 288.0, 446.0, INK, date)

        text("F2", 16.0, 48.0, 400.0, INK, "Keep it safe")
        var y = 376.0
        for (item in ADVICE) {
            append("$SUN rg 48 ${fmt(y - 2)} 8 8 re f\n")
            for (line in wrap(item, 11.0, 470.0)) {
                text("F1", 11.0, 66.0, y, INK, line)
                y -= 16
            }
            y -= 8
        }

        text("F2", 16.0, 48.0, y - 14, INK, "To use it")
        y -= 38
        for (line in wrap(HOW_TO, 11.0, 488.0)) {
            text("F1", 11.0, 48.0, y, INK, line)
            y -= 16
        }

        append("$INK RG 0.5 w 48 58 m 547 58 l S\n")
        text("F1", 8.5, 48.0, 44.0, MUTED, "Made by Obscura on your phone. No copy of this key was kept or sent anywhere: the app has no internet access.")
        text("F1", 8.5, 48.0, 32.0, MUTED, "This PDF is encrypted with AES-256 and opens only with the password chosen when it was saved.")
    }

    private val ADVICE = listOf(
        "Print this page and store it somewhere only you can reach, such as a safe or a locked drawer. Don't keep it on the phone that holds the vault.",
        "Anyone who has this key and your phone or a backup of your vault can open it. Never share it or photograph it.",
        "Obscura can't show this key again. If it's lost, create a new one in Security, Recovery key. The old key then stops working.",
        "Keep the PDF password separate from this page, and never use your vault passphrase for it.",
    )

    private const val HOW_TO = "On Obscura's lock screen, tap \"Forgot? Use recovery key\" and type the 8 groups of 4 characters. " +
        "Dashes and spaces are optional, and O and 0, I and 1 are treated the same. Then choose a new passphrase in Security."

    private fun StringBuilder.text(font: String, size: Double, x: Double, y: Double, color: String, value: String) {
        append("BT $color rg /$font ${fmt(size)} Tf ${fmt(x)} ${fmt(y)} Td (${escape(value)}) Tj ET\n")
    }

    /** A circle as four Bezier quarter arcs. */
    private fun circle(cx: Double, cy: Double, r: Double): String {
        val k = 0.5523 * r
        return "${fmt(cx + r)} ${fmt(cy)} m " +
            "${fmt(cx + r)} ${fmt(cy + k)} ${fmt(cx + k)} ${fmt(cy + r)} ${fmt(cx)} ${fmt(cy + r)} c " +
            "${fmt(cx - k)} ${fmt(cy + r)} ${fmt(cx - r)} ${fmt(cy + k)} ${fmt(cx - r)} ${fmt(cy)} c " +
            "${fmt(cx - r)} ${fmt(cy - k)} ${fmt(cx - k)} ${fmt(cy - r)} ${fmt(cx)} ${fmt(cy - r)} c " +
            "${fmt(cx + k)} ${fmt(cy - r)} ${fmt(cx + r)} ${fmt(cy - k)} ${fmt(cx + r)} ${fmt(cy)} c h"
    }

    /** Greedy word wrap using approximate Helvetica glyph widths (in em). */
    internal fun wrap(text: String, size: Double, width: Double): List<String> {
        fun em(c: Char) = when (c) {
            ' ', 'i', 'j', 'l', '.', ',', '\'', '!', '|', ':', ';', 'I' -> 0.28
            'f', 't', 'r', '(', ')', '"', '-' -> 0.36
            'm', 'w', 'M', 'W' -> 0.85
            in 'A'..'Z' -> 0.68
            else -> 0.56
        }
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (candidate.sumOf(::em) * size > width && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    private fun fmt(value: Double) = if (value == Math.floor(value)) value.toLong().toString() else "%.2f".format(Locale.US, value)

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** The password as the standard requires: UTF-8, at most 127 bytes. */
    private fun passwordBytes(password: CharArray): ByteArray {
        val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val out = ByteArray(minOf(bytes.remaining(), 127))
        bytes.get(out)
        return out
    }

    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Standard security handler, revision 6 (AES-256). The owner password is random and thrown
     * away, so nobody can lift the print-only permissions with it.
     */
    private class StandardSecurity(user: ByteArray, private val fileKey: ByteArray, private val random: SecureRandom) {
        private val u: ByteArray
        private val ue: ByteArray
        private val o: ByteArray
        private val oe: ByteArray
        private val perms: ByteArray

        init {
            val owner = ByteArray(32).also(random::nextBytes)
            val (uValidation, uKey) = salt() to salt()
            u = hash(user, uValidation, EMPTY) + uValidation + uKey
            ue = aesNoIv(hash(user, uKey, EMPTY), fileKey)
            val (oValidation, oKey) = salt() to salt()
            o = hash(owner, oValidation, u) + oValidation + oKey
            oe = aesNoIv(hash(owner, oKey, u), fileKey)
            val p = ByteArray(16)
            for (i in 0 until 4) p[i] = (PERMISSIONS shr (8 * i)).toByte()
            for (i in 4 until 8) p[i] = 0xFF.toByte()
            "Tadb".forEachIndexed { i, c -> p[8 + i] = c.code.toByte() }
            System.arraycopy(ByteArray(4).also(random::nextBytes), 0, p, 12, 4)
            perms = Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
                doFinal(p)
            }
            owner.fill(0)
        }

        fun dictionary(): String =
            "<< /Filter /Standard /V 5 /R 6 /Length 256 /CF << /StdCF << /Type /CryptFilter /CFM /AESV3 /AuthEvent /DocOpen /Length 32 >> >> " +
                "/StmF /StdCF /StrF /StdCF /P $PERMISSIONS /EncryptMetadata true " +
                "/O <${hex(o)}> /U <${hex(u)}> /OE <${hex(oe)}> /UE <${hex(ue)}> /Perms <${hex(perms)}> >>"

        /** AESV3: a random 16-byte IV, then AES-256-CBC with PKCS#7 padding under the file key. */
        fun encrypt(data: ByteArray): ByteArray {
            val iv = ByteArray(16).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
            return iv + cipher.doFinal(data)
        }

        private fun salt() = ByteArray(8).also(random::nextBytes)

        private fun aesNoIv(key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(data)
        }

        companion object {
            private val EMPTY = ByteArray(0)

            /** Print (normal and high quality) and accessibility allowed; changes and copying not. */
            const val PERMISSIONS = -4096 or 4 or 64 or 128 or 512 or 2048

            /** Algorithm 2.B of ISO 32000-2: the slow, salted hash behind every revision 6 key. */
            fun hash(password: ByteArray, salt: ByteArray, userKey: ByteArray): ByteArray {
                var k = MessageDigest.getInstance("SHA-256").digest(password + salt + userKey)
                var round = 0
                while (true) {
                    val block = password + k + userKey
                    val k1 = ByteArray(block.size * 64)
                    for (i in 0 until 64) System.arraycopy(block, 0, k1, i * block.size, block.size)
                    val e = Cipher.getInstance("AES/CBC/NoPadding").run {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                        doFinal(k1)
                    }
                    val algorithm = when (e.take(16).sumOf { it.toInt() and 0xFF } % 3) {
                        0 -> "SHA-256"
                        1 -> "SHA-384"
                        else -> "SHA-512"
                    }
                    k = MessageDigest.getInstance(algorithm).digest(e)
                    round++
                    if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
                }
                return k.copyOf(32)
            }
        }
    }
}
