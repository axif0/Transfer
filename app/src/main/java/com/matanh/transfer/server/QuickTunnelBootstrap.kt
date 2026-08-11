package com.matanh.transfer.server

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Android has no /etc/resolv.conf, so the packaged linux/static Go cloudflared binary
 * cannot resolve DNS (looks up [::1]:53 and dies).
 *
 * Workaround: register the Quick Tunnel + resolve Cloudflare edge hosts via Android/Java DNS,
 * then start cloudflared with explicit `--edge` IPs so it needs zero DNS.
 *
 * See docs/internet-sharing.md
 */
object QuickTunnelBootstrap {

    private val logger = Timber.tag("TransferCloudflareTunnel")

    private const val REGISTER_URL = "https://api.trycloudflare.com/tunnel"
    private val EDGE_HOSTS = listOf(
        "region1.v2.argotunnel.com",
        "region2.v2.argotunnel.com",
    )

    data class Prepared(
        val publicUrl: String,
        val arguments: List<String>,
        val workDir: File,
    )

    fun prepare(context: Context, originPort: Int): Prepared {
        val registration = registerQuickTunnel()
        val workDir = File(context.cacheDir, "cf-tunnel").also { dir ->
            if (!dir.exists()) dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }

        val credsFile = File(workDir, "creds.json")
        credsFile.writeText(
            JSONObject()
                .put("AccountTag", registration.accountTag)
                .put("TunnelID", registration.tunnelId)
                .put("TunnelSecret", registration.secret)
                .toString()
        )

        val configFile = File(workDir, "config.yml")
        // protocol http2: more reliable on some Android radios than QUIC
        configFile.writeText(
            """
            |tunnel: ${registration.tunnelId}
            |credentials-file: ${credsFile.absolutePath}
            |protocol: http2
            |ingress:
            |  - hostname: ${registration.hostname}
            |    service: http://127.0.0.1:$originPort
            |  - service: http_status:404
            """.trimMargin() + "\n"
        )

        val edges = resolveEdgeAddresses()
        if (edges.isEmpty()) {
            throw IllegalStateException("Could not resolve Cloudflare edge addresses")
        }
        logger.i("Resolved ${edges.size} edge endpoints")

        val args = ArrayList<String>().apply {
            add("tunnel")
            add("--config")
            add(configFile.absolutePath)
            add("--edge-ip-version")
            add("4")
            add("--no-autoupdate")
            for (edge in edges.take(4)) {
                add("--edge")
                add(edge)
            }
            add("run")
            add(registration.tunnelId)
        }

        val publicUrl = "https://${registration.hostname}"
        logger.i("Quick Tunnel registered: $publicUrl")
        return Prepared(publicUrl, args, workDir)
    }

    private data class Registration(
        val tunnelId: String,
        val hostname: String,
        val accountTag: String,
        val secret: String,
    )

    private fun registerQuickTunnel(): Registration {
        val conn = (URL(REGISTER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(ByteArray(0)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                logger.e("Quick Tunnel register HTTP $code: $body")
                throw IllegalStateException("Quick Tunnel registration failed ($code)")
            }
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) {
                throw IllegalStateException("Quick Tunnel registration unsuccessful")
            }
            val result = root.getJSONObject("result")
            return Registration(
                tunnelId = result.getString("id"),
                hostname = result.getString("hostname"),
                accountTag = result.getString("account_tag"),
                secret = result.getString("secret"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveEdgeAddresses(): List<String> {
        val out = LinkedHashSet<String>()
        for (host in EDGE_HOSTS) {
            try {
                InetAddress.getAllByName(host)
                    .filterIsInstance<Inet4Address>()
                    .forEach { addr ->
                        out.add("${addr.hostAddress}:7844")
                    }
            } catch (e: Exception) {
                logger.w(e, "Failed resolving edge host $host")
            }
        }
        return out.toList()
    }
}
