package com.shahaabapps.obscura.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class LikePatternTest {
    @Test
    fun plainTextIsWrapped() = assertEquals("%beach%", likePattern("beach"))

    @Test
    fun whitespaceIsTrimmed() = assertEquals("%beach%", likePattern("  beach "))

    @Test
    fun wildcardsAreEscaped() = assertEquals("%50\\% off\\_sale%", likePattern("50% off_sale"))

    @Test
    fun backslashesAreEscapedFirst() = assertEquals("%a\\\\b%", likePattern("a\\b"))
}
