package io.lightknight.omniread

import io.lightknight.omniread.engine.sync.DriveResumableProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveResumableHeaderTest {
    @Test
    fun buildsValidContentRangeAndParses308Offset() {
        val total = 12_582_912L // 12 MB
        val chunk = 5_242_880L // 5 MB
        val h1 = DriveResumableProtocol.buildContentRangeHeader(0L, chunk - 1, total)
        assertEquals("bytes 0-5242879/12582912", h1)

        val nextOffset = DriveResumableProtocol.parseNextOffsetFrom308("bytes=0-5242879")
        assertEquals(5_242_880L, nextOffset)

        assertEquals(0L, DriveResumableProtocol.parseNextOffsetFrom308(null))
    }
}
