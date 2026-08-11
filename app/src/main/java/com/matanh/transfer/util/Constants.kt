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
}