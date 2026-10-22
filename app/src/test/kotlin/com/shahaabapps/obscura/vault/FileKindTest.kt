package com.shahaabapps.obscura.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileKindTest {

    @Test
    fun mimeTypesMapToKinds() {
        assertEquals(FileKind.PHOTO, FileStore.kindOf("image/heic"))
        assertEquals(FileKind.VIDEO, FileStore.kindOf("video/mp4"))
        assertEquals(FileKind.AUDIO, FileStore.kindOf("audio/mp4a-latm"))
        assertEquals(FileKind.DOCUMENT, FileStore.kindOf("application/pdf"))
        assertEquals(FileKind.DOCUMENT, FileStore.kindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(FileKind.DOCUMENT, FileStore.kindOf("text/plain"))
        assertEquals(FileKind.OTHER, FileStore.kindOf("application/zip"))
    }

    @Test
    fun idsAreRandom128BitHex() {
        val a = FileStore.newId()
        assertEquals(32, a.length)
        assertNotEquals(a, FileStore.newId())
    }
}
