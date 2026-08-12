package com.matanh.transfer.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic stand-in for tunnel write policy.
 * Keeps the read-only / allow-upload rule honest without Android instrumentation.
 */
class InternetSharingReadOnlyRuleTest {

    private fun isTunnelClient(internetSharingOn: Boolean, remoteHost: String): Boolean {
        if (!internetSharingOn) return false
        val host = remoteHost.lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1" ||
            host == "0:0:0:0:0:0:0:1"
    }

    private fun canUpload(
        internetSharingOn: Boolean,
        allowUpload: Boolean,
        remoteHost: String,
    ): Boolean {
        val tunnel = isTunnelClient(internetSharingOn, remoteHost)
        return !tunnel || allowUpload
    }

    private fun canDelete(internetSharingOn: Boolean, remoteHost: String): Boolean =
        !isTunnelClient(internetSharingOn, remoteHost)

    @Test
    fun lanWritesAllowedWhenSharingOff() {
        assertTrue(canUpload(false, false, "192.168.0.50"))
        assertTrue(canUpload(false, false, "127.0.0.1"))
        assertTrue(canDelete(false, "127.0.0.1"))
    }

    @Test
    fun tunnelLoopbackNoUploadWhenToggleOff() {
        assertFalse(canUpload(true, false, "127.0.0.1"))
        assertFalse(canUpload(true, false, "::1"))
        assertFalse(canDelete(true, "127.0.0.1"))
    }

    @Test
    fun tunnelLoopbackUploadWhenToggleOn() {
        assertTrue(canUpload(true, true, "127.0.0.1"))
        assertFalse(canDelete(true, "127.0.0.1"))
    }

    @Test
    fun lanIpStillWritableWhenSharingOn() {
        assertTrue(canUpload(true, false, "192.168.0.50"))
        assertTrue(canDelete(true, "192.168.0.50"))
    }
}
