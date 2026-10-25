package ir.aria.downloader

import ir.aria.downloader.util.AppLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The diagnostic log must record, read back and clear without touching Android APIs. */
class AppLogTest {

    @Test
    fun recordsReadsAndClears() {
        val dir = Files.createTempDirectory("applog").toFile()
        try {
            AppLog.init(dir)
            AppLog.log("تست", "اولین رویداد")
            AppLog.log("خطا", "دومین رویداد", RuntimeException("boom"))

            val text = AppLog.text()
            assertTrue(text.contains("اولین رویداد"))
            assertTrue(text.contains("RuntimeException"))
            assertTrue(AppLog.currentFile()!!.length() > 0)

            AppLog.clear()
            val afterClear = AppLog.text()
            assertFalse(afterClear.contains("اولین رویداد"))
            assertTrue(afterClear.contains("لاگ پاک شد"))
        } finally {
            dir.deleteRecursively()
            AppLog.init(File(System.getProperty("java.io.tmpdir"), "applog-idle"))
        }
    }
}
