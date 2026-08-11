package com.matanh.transfer.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.matanh.transfer.R
import com.matanh.transfer.server.FileServerService

/** Shared start/stop used by QS tile and home-screen widget. */
object ServerRemoteControl {

    fun isServerActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Constants.PREF_SERVER_ACTIVE, false)
    }

    fun toggle(context: Context) {
        if (isServerActive(context)) stop(context) else start(context)
    }

    fun start(context: Context) {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val folderUri = prefs.getString(Constants.EXTRA_FOLDER_URI, null)
        if (folderUri.isNullOrEmpty()) {
            Toast.makeText(context, R.string.select_shared_folder_prompt, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, FileServerService::class.java).apply {
            action = Constants.ACTION_START_SERVICE
            putExtra(Constants.EXTRA_FOLDER_URI, folderUri)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, FileServerService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        context.startService(intent)
    }
}
