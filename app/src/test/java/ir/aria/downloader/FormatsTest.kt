package ir.aria.downloader

import ir.aria.downloader.util.Formats
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {

    @Test
    fun formatsBytes() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("512 B", Formats.bytes(512))
        assertEquals("1.0 KB", Formats.bytes(1024))
        assertEquals("1.5 MB", Formats.bytes(1536L * 1024))
        assertEquals("2.0 GB", Formats.bytes(2L * 1024 * 1024 * 1024))
        assertEquals("—", Formats.bytes(-1))
    }

    @Test
    fun formatsSpeed() {
        assertEquals("", Formats.speed(0))
        assertEquals("2.0 MB/s", Formats.speed(2L * 1024 * 1024))
    }

    @Test
    fun formatsEta() {
        assertEquals("45s", Formats.eta(45))
        assertEquals("2m 05s", Formats.eta(125))
        assertEquals("1h 01m", Formats.eta(3661))
        assertEquals("", Formats.eta(-1))
    }

    @Test
    fun formatsMediaDuration() {
        assertEquals("", Formats.duration(null))
        assertEquals("0:45", Formats.duration(45))
        assertEquals("3:25", Formats.duration(205))
        assertEquals("1:00:01", Formats.duration(3601))
    }

    @Test
    fun percent() {
        assertEquals(50, Formats.percent(50, 100))
        assertEquals(0, Formats.percent(10, 0))
        assertEquals(100, Formats.percent(200, 100))
    }
}
