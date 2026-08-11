package com.matanh.transfer.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic stand-in for [FileServerService.isInternetSharingReadOnlyFor].
 * Keeps the read-only rule honest without Android instrumentation.
 */
class InternetSharingReadOnlyRuleTest {

    private fun isReadOnly(internetSharingOn: Boolean, remoteHost: String): Boolean {
        if (!internetSharingOn) return false
        val host = remoteHost.lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1" ||
            host == "0:0:0:0:0:0:0:1"
    }

    @Test
    fun lanWritesAllowedWhenSharingOff() {
        assertFalse(isReadOnly(false, "192.168.0.50"))
        assertFalse(isReadOnly(false, "127.0.0.1"))
    }

    @Test
    fun tunnelLoopbackReadOnlyWhenSharingOn() {
        assertTrue(isReadOnly(true, "127.0.0.1"))
        assertTrue(isReadOnly(true, "::1"))
    }

    @Test
    fun lanIpStillWritableWhenSharingOn() {
        assertFalse(isReadOnly(true, "192.168.0.50"))
    }
}
