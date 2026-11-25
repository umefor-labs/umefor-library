package io.lightknight.omniread

import io.lightknight.omniread.core.NaturalSort
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {
    @Test
    fun sortsNumericSuffixesNaturally() {
        val input = listOf("page_10.jpg", "page_2.jpg", "page_1.jpg", "page_01.jpg", "page_11.jpg")
        val sorted = input.sortedWith(NaturalSort)
        assertEquals(
            listOf("page_1.jpg", "page_01.jpg", "page_2.jpg", "page_10.jpg", "page_11.jpg"),
            sorted
        )
    }

    @Test
    fun sortsFolderChapterNamesNaturally() {
        val input = listOf("Chapter 100", "Chapter 2", "Chapter 10", "Chapter 1")
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 10", "Chapter 100"),
            input.sortedWith(NaturalSort)
        )
    }
}
