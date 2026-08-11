package com.matanh.transfer.server

/**
 * Observable lifecycle of the optional Cloudflare Quick Tunnel.
 * Independent of [ServerState]; Internet Sharing is never auto-started with LAN.
 */
sealed interface TunnelState {
    data object Stopped : TunnelState
    data object Starting : TunnelState
    data class Running(val publicUrl: String) : TunnelState
    data class Error(val message: String) : TunnelState
}
