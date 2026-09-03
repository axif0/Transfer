package com.matanh.transfer.server

sealed class ServerState {
    object Starting : ServerState()
    data class Running(val hosts: NetworkInfo, val port: Int, val tunnelOnly: Boolean = false) : ServerState()
    object UserStopped : ServerState()
    object AwaitNetwork: ServerState()
    data class Error(val message: String) : ServerState()
}