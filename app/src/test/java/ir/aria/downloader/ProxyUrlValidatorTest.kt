package ir.aria.downloader

import ir.aria.downloader.net.ProxyUrlValidator
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyUrlValidatorTest {
    @Test
    fun acceptsHttpAndSocksAndAddsMissingScheme() {
        assertEquals("http://127.0.0.1:8080", ProxyUrlValidator.normalize("127.0.0.1:8080"))
        assertEquals("socks5://127.0.0.1:1080",
            ProxyUrlValidator.normalize(" socks5://127.0.0.1:1080 "))
        assertEquals("https://user:pass@proxy.example:8443",
            ProxyUrlValidator.normalize("https://user:pass@proxy.example:8443/"))
    }

    @Test
    fun blankMeansDirectConnection() {
        assertEquals("", ProxyUrlValidator.normalize("  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedScheme() {
        ProxyUrlValidator.normalize("ftp://127.0.0.1:21")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathsInsteadOfPassingBrokenProxyToPython() {
        ProxyUrlValidator.normalize("http://127.0.0.1:8080/not-a-proxy")
    }
}
