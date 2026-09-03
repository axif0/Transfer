package com.matanh.transfer.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRangeTest {

    @Test
    fun parseRange_fullFile_returnsNull() {
        assertNull(parseRangeHeader(null, 1000L))
    }

    @Test
    fun parseRange_suffix() {
        val r = parseRangeHeader("bytes=-500", 1000L)!!
        assertEquals(500L, r.start)
        assertEquals(999L, r.endInclusive)
        assertEquals(500L, r.contentLength())
    }

    @Test
    fun parseRange_openEnded() {
        val r = parseRangeHeader("bytes=100-", 1000L)!!
        assertEquals(100L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun parseRange_closed() {
        val r = parseRangeHeader("bytes=0-99", 1000L)!!
        assertEquals(0L, r.start)
        assertEquals(99L, r.endInclusive)
        assertEquals(100L, r.contentLength())
    }

    @Test
    fun parseRange_endClampedToFileSize() {
        val r = parseRangeHeader("bytes=900-2000", 1000L)!!
        assertEquals(900L, r.start)
        assertEquals(999L, r.endInclusive)
    }

    @Test
    fun parseRange_startBeyondFile_returnsNull() {
        assertNull(parseRangeHeader("bytes=1000-", 1000L))
    }

    @Test
    fun parseRange_multiRange_returnsNull() {
        assertNull(parseRangeHeader("bytes=0-1,2-3", 1000L))
    }

    @Test
    fun etagFor_stable() {
        assertEquals("\"1024-42\"", etagFor(1024L, 42L))
    }
}
