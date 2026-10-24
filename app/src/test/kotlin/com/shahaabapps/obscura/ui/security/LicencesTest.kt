package com.shahaabapps.obscura.ui.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every component on the licences screen ships its licence text, and the fonts carry theirs. */
class LicencesTest {

    private val assets = File("src/main/assets/licences")

    @Test
    fun everyListedLicenceFileShips() {
        for (component in Components) {
            val file = File(assets, component.file)
            assertTrue("${component.name}: missing ${file.path}", file.isFile && file.length() > 500)
        }
    }

    @Test
    fun fontLicencesNameTheirAuthors() {
        assertTrue(File(assets, "ofl-space-grotesk.txt").readText().startsWith("Copyright 2020 The Space Grotesk Project Authors"))
        assertTrue(File(assets, "ofl-jetbrains-mono.txt").readText().startsWith("Copyright 2020 The JetBrains Mono Project Authors"))
        for (name in listOf("ofl-space-grotesk.txt", "ofl-jetbrains-mono.txt")) {
            assertTrue(name, File(assets, name).readText().contains("SIL OPEN FONT LICENSE Version 1.1"))
        }
    }

    @Test
    fun reflowJoinsWrappedLinesButKeepsStructure() {
        val text = "Permission is hereby granted, free of charge,\nto any person.\n\n---------\nTITLE\n---------\n\n1) First item\nwrapped here.\n2) Second item"
        assertEquals(
            "Permission is hereby granted, free of charge, to any person.\n\n---------\nTITLE\n---------\n\n1) First item wrapped here.\n2) Second item",
            reflow(text),
        )
    }

    @Test
    fun everyBundledFontHasALicence() {
        val fonts = File("src/main/res/font").listFiles().orEmpty().map { it.name }.sorted()
        assertTrue(fonts.toString(), fonts == listOf("jetbrains_mono_regular.ttf", "jetbrains_mono_semibold.ttf", "space_grotesk.ttf"))
    }
}
