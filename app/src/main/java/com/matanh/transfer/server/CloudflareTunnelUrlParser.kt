package com.matanh.transfer.server

import java.net.URI

/**
 * Extracts Cloudflare Quick Tunnel public URLs from arbitrary cloudflared log lines.
 * Restricts matches to `*.trycloudflare.com` HTTPS hosts only.
 */
object CloudflareTunnelUrlParser {

    // Strip common ANSI CSI sequences so colorized logs still parse.
    private val ansiEscapeRegex = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    private val tryCloudflareUrlRegex =
        Regex("""https://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.trycloudflare\.com""")

    /**
     * @return first valid Quick Tunnel URL found in [line], or null
     */
    fun parse(line: String): String? {
        if (line.isBlank()) return null
        val cleaned = ansiEscapeRegex.replace(line, "")
        val match = tryCloudflareUrlRegex.find(cleaned) ?: return null
        return validate(match.value)
    }

    private fun validate(candidate: String): String? {
        return try {
            val uri = URI(candidate)
            if (uri.scheme != "https") return null
            val host = uri.host ?: return null
            if (!host.endsWith(".trycloudflare.com", ignoreCase = true)) return null
            if (host.equals("trycloudflare.com", ignoreCase = true)) return null
            // Reject path/query noise; Quick Tunnel URLs are origin-only.
            if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
            if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return null
            "https://$host"
        } catch (_: Exception) {
            null
        }
    }
}
