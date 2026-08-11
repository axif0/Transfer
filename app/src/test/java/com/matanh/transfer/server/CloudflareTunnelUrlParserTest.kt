package com.matanh.transfer.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudflareTunnelUrlParserTest {

    @Test
    fun parsesStandardQuickTunnelUrl() {
        val line =
            "2025-08-12T10:00:00Z INF |  https://random-name.trycloudflare.com                        |"
        assertEquals(
            "https://random-name.trycloudflare.com",
            CloudflareTunnelUrlParser.parse(line)
        )
    }

    @Test
    fun parsesUrlSurroundedByOtherText() {
        val line = "Visit https://abc-123.trycloudflare.com now"
        assertEquals("https://abc-123.trycloudflare.com", CloudflareTunnelUrlParser.parse(line))
    }

    @Test
    fun duplicateUrlInLineReturnsFirst() {
        val line =
            "https://one.trycloudflare.com then https://two.trycloudflare.com"
        assertEquals("https://one.trycloudflare.com", CloudflareTunnelUrlParser.parse(line))
    }

    @Test
    fun stripsAnsiColorCodes() {
        val line =
            "\u001B[32mhttps://colored.trycloudflare.com\u001B[0m"
        assertEquals("https://colored.trycloudflare.com", CloudflareTunnelUrlParser.parse(line))
    }

    @Test
    fun toleratesWhitespace() {
        val line = "   https://spaced.trycloudflare.com   \n"
        assertEquals("https://spaced.trycloudflare.com", CloudflareTunnelUrlParser.parse(line))
    }

    @Test
    fun rejectsUnrelatedHttpsUrls() {
        assertNull(CloudflareTunnelUrlParser.parse("https://example.com/path"))
        assertNull(CloudflareTunnelUrlParser.parse("https://cloudflare.com"))
        assertNull(CloudflareTunnelUrlParser.parse("https://trycloudflare.com"))
    }

    @Test
    fun rejectsMalformedUrl() {
        assertNull(CloudflareTunnelUrlParser.parse("http://bad.trycloudflare.com"))
        assertNull(CloudflareTunnelUrlParser.parse("https://.trycloudflare.com"))
        assertNull(CloudflareTunnelUrlParser.parse("ftp://x.trycloudflare.com"))
    }

    @Test
    fun emptyLineReturnsNull() {
        assertNull(CloudflareTunnelUrlParser.parse(""))
        assertNull(CloudflareTunnelUrlParser.parse("   "))
    }

    @Test
    fun cloudflareErrorOutputIgnored() {
        assertNull(
            CloudflareTunnelUrlParser.parse(
                "ERR Failed to dial a quic connection error=\"context canceled\""
            )
        )
    }

    @Test
    fun urlAfterStartupNoise() {
        val noise = listOf(
            "INF Starting metrics server",
            "INF Connection registered",
            "INF +--------------------------------------------------------------------------------------------+",
            "INF |  https://late-url.trycloudflare.com                                                       |",
        )
        var found: String? = null
        for (line in noise) {
            found = CloudflareTunnelUrlParser.parse(line)
            if (found != null) break
        }
        assertEquals("https://late-url.trycloudflare.com", found)
    }

    @Test
    fun extractsOriginEvenWhenPathOrQueryPresentInLine() {
        // Logs may embed the hostname next to other text; we keep the origin only.
        assertEquals(
            "https://x.trycloudflare.com",
            CloudflareTunnelUrlParser.parse("https://x.trycloudflare.com/foo")
        )
        assertEquals(
            "https://x.trycloudflare.com",
            CloudflareTunnelUrlParser.parse("open https://x.trycloudflare.com?q=1")
        )
    }
}
