package com.matanh.transfer.util

object Constants {
    const val SERVER_PORT = 8000
    const val NOTIFICATION_CHANNEL_ID = "TransferServiceChannel"
    const val NOTIFICATION_ID = 1
    const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
    const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    const val SHARED_PREFS_NAME = "TransferPrefs"
    const val IP_PERMISSION_VALIDITY_MS = 60 * 60 * 1000L // 1 hour

    const val EXTRA_FOLDER_URI = "FOLDER_URI"
    /** Written by FileServerService so QS tile / widget can show active state. */
    const val PREF_SERVER_ACTIVE = "server_active"
    /** When true, public Internet Sharing link accepts uploads (delete still blocked). */
    const val PREF_INTERNET_ALLOW_UPLOAD = "internet_allow_upload"
    /** When true, server may run without LAN IP (Internet Sharing over cellular only). */
    const val PREF_CELLULAR_TUNNEL_ONLY = "cellular_tunnel_only"
    const val PREF_HUB_URL = "hub_url"
    const val PREF_HUB_JOIN_CODE = "hub_join_code"
    const val PREF_HUB_DISPLAY_NAME = "hub_display_name"
}