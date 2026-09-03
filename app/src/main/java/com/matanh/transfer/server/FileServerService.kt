package com.matanh.transfer.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.matanh.transfer.util.Constants
import com.matanh.transfer.MainActivity
import com.matanh.transfer.R
import com.matanh.transfer.widget.ServerAppWidget
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

data class IpPermissionRequest(val ipAddress: String, val deferred: CompletableDeferred<Boolean>)


class FileServerService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val binder = LocalBinder()
    private var ktorServer: EmbeddedServer<*, *>? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val _serverState = MutableStateFlow<ServerState>(ServerState.UserStopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private lateinit var cloudflareTunnel: CloudflareTunnel
    val tunnelState: StateFlow<TunnelState>
        get() = cloudflareTunnel.state

    private val activeTransfers = java.util.concurrent.ConcurrentHashMap<String, TransferProgress>()
    private val _transferProgress = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transferProgress: StateFlow<List<TransferProgress>> = _transferProgress.asStateFlow()
    @Volatile private var lastProgressNotifyMs = 0L

    /**
     * Session-only: true while user wants Internet Sharing active.
     * Not persisted — reboot/service restart never auto-exposes publicly.
     */
    @Volatile
    private var internetSharingDesired = false


    private val _ipPermissionRequests =
        MutableSharedFlow<IpPermissionRequest>(replay = 0, extraBufferCapacity = 1)
    val ipPermissionRequests = _ipPermissionRequests.asSharedFlow()

    private val _pullRefresh = MutableSharedFlow<Unit>(replay = 0)
    val pullRefresh: SharedFlow<Unit> = _pullRefresh.asSharedFlow()

    private lateinit var sharedPreferences: SharedPreferences
    private val approvedIps = mutableMapOf<String, Long>()
    private lateinit var networkHelper: NetworkHelper

    var currentSharedFolderUri: Uri? = null
        private set

    @Volatile
    private var isActivityInForeground = false
    private val pendingNotificationRequests = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val pendingIntentFlags by lazy {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }

    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }

    companion object {
        private val logger = Timber.tag("FileServerServiceKtor")

        // Constants for IP Permission Notification
        const val PERMISSION_NOTIFICATION_CHANNEL_ID = "ip_permission_channel"
        const val ACTION_IP_PERMISSION_RESPONSE =
            "com.matanh.transfer.ACTION_IP_PERMISSION_RESPONSE"
        const val EXTRA_IP_ADDRESS = "extra_ip_address"
        const val EXTRA_IP_PERMISSION_APPROVED = "extra_ip_permission_approved"
        const val ACTION_STOP_INTERNET_SHARING =
            "com.matanh.transfer.ACTION_STOP_INTERNET_SHARING"
        /** Returned by [startInternetSharing] when password is not configured. */
        const val RESULT_PASSWORD_REQUIRED = "PASSWORD_REQUIRED"
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)

        sharedPreferences.registerOnSharedPreferenceChangeListener(this) // track changes
        createNotificationChannel()

        cloudflareTunnel = CloudflareTunnel(applicationContext, externalScope = serviceScope)
        serviceScope.launch {
            cloudflareTunnel.state.collectLatest {
                updateNotification()
            }
        }

        networkHelper = NetworkHelper(this)
        networkHelper.register()
        observeIpChanges()

        logger.d("FileServerService onCreate")
    }

    private fun cellularTunnelOnlyEnabled(): Boolean =
        sharedPreferences.getBoolean(Constants.PREF_CELLULAR_TUNNEL_ONLY, false)

    private fun canRunTunnelOnly(info: NetworkInfo): Boolean =
        cellularTunnelOnlyEnabled() && info.hasCellular

    private fun observeIpChanges() {
        serviceScope.launch {
            networkHelper.networkInfo.collectLatest { info ->
                val ipAddress = info.mainIp

                val currentState = _serverState.value
                if (currentState is ServerState.Error || currentState is ServerState.UserStopped) {
                    return@collectLatest
                }

                if (currentState is ServerState.AwaitNetwork || currentState is ServerState.Running) {
                    if (ipAddress != null) {
                        if (currentState is ServerState.Running &&
                            currentState.hosts.mainIp != ipAddress
                        ) {
                            logger.i("IP address changed from ${currentState.hosts.mainIp} to $ipAddress. restarting server.")
                            startKtorServer()
                        } else {
                            logger.i("IP address changed to $ipAddress.  restarting server.")
                            startKtorServer()
                        }
                        updateNotification()
                    } else if (canKeepServerWithoutLan(currentState, info)) {
                        if (currentState is ServerState.Running && !currentState.tunnelOnly) {
                            logger.i("LAN lost; keeping server in tunnel-only mode.")
                            _serverState.value = ServerState.Running(
                                info.copy(localIp = null, localHostname = null, hotspotIp = null),
                                currentState.port,
                                tunnelOnly = true,
                            )
                            updateNotification()
                        }
                    } else {
                        logger.w("Network unavailable. Stopping server.")
                        stopKtorServer(ServerState.AwaitNetwork)
                    }
                }
            }
        }
    }

    private fun canKeepServerWithoutLan(currentState: ServerState, info: NetworkInfo): Boolean {
        if (!canRunTunnelOnly(info)) return false
        if (ktorServer == null) return false
        return currentState is ServerState.Running ||
            currentState is ServerState.AwaitNetwork ||
            internetSharingDesired
    }

    fun isInternetSharingEnabled(): Boolean = internetSharingDesired

    /**
     * Report bytes for an in-flight transfer. Throttled notification updates.
     */
    fun reportTransferProgress(
        id: String,
        fileName: String,
        direction: TransferProgress.Direction,
        bytesTransferred: Long,
        totalBytes: Long?,
    ) {
        activeTransfers[id] = TransferProgress(id, fileName, direction, bytesTransferred, totalBytes)
        _transferProgress.value = activeTransfers.values.sortedBy { it.fileName }
        val now = System.currentTimeMillis()
        if (now - lastProgressNotifyMs >= 400L) {
            lastProgressNotifyMs = now
            updateNotification()
        }
    }

    fun clearTransferProgress(id: String) {
        activeTransfers.remove(id)
        _transferProgress.value = activeTransfers.values.sortedBy { it.fileName }
        updateNotification()
    }
    /**
     * Start Cloudflare Quick Tunnel. Requires Ktor running.
     * Password is optional (settings); not required to start Internet Sharing.
     * @return null on accepted start, or a localized error string.
     */
    fun startInternetSharing(): String? {
        if (currentSharedFolderUri == null) {
            return getString(R.string.shared_folder_not_selected)
        }
        val state = _serverState.value
        if (state !is ServerState.Running && state !is ServerState.Starting) {
            return getString(R.string.internet_sharing_requires_server)
        }
        internetSharingDesired = true
        if (state is ServerState.Running) {
            serviceScope.launch {
                cloudflareTunnel.start(Constants.SERVER_PORT, tunnelErrorMessages())
            }
        }
        // If Starting, tunnel starts via restartTunnelIfDesired when Running.
        return null
    }

    fun stopInternetSharing() {
        internetSharingDesired = false
        serviceScope.launch {
            cloudflareTunnel.stop()
            updateNotification()
        }
    }

    /**
     * Loopback / tunnel path: cloudflared connects to 127.0.0.1, so
     * [io.ktor.server.plugins.origin.remoteHost] is NOT the friend's public IP.
     * LAN IP approval must not block Quick Tunnel traffic on that basis.
     */
    fun shouldSkipIpApprovalForRemoteHost(remoteHost: String): Boolean {
        if (!internetSharingDesired) return false
        return isLoopbackHost(remoteHost)
    }

    /**
     * When Internet Sharing is on, treat loopback clients as the public tunnel:
     * block write ops (unless [allowsInternetUpload] for upload). LAN keeps full write.
     */
    fun isInternetSharingReadOnlyFor(remoteHost: String): Boolean {
        if (!internetSharingDesired) return false
        return isLoopbackHost(remoteHost)
    }

    /** Pref: friend may upload via public link. Delete still blocked on tunnel path. */
    fun allowsInternetUpload(): Boolean =
        sharedPreferences.getBoolean(Constants.PREF_INTERNET_ALLOW_UPLOAD, false)

    fun setAllowInternetUpload(allow: Boolean) {
        sharedPreferences.edit { putBoolean(Constants.PREF_INTERNET_ALLOW_UPLOAD, allow) }
    }

    private fun isLoopbackHost(remoteHost: String): Boolean {
        val host = remoteHost.lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1"
    }

    private fun tunnelErrorMessages() = TunnelErrorMessages(
        binaryUnavailable = getString(R.string.internet_sharing_binary_unavailable),
        unsupportedAbi = getString(R.string.internet_sharing_unsupported_abi),
        startFailed = getString(R.string.internet_sharing_start_failed),
        timeout = getString(R.string.internet_sharing_timeout),
        unexpectedExit = getString(R.string.internet_sharing_unexpected_exit),
        networkUnavailable = getString(R.string.internet_sharing_network_unavailable),
    )

    private suspend fun restartTunnelIfDesired() {
        if (!internetSharingDesired) return
        if (_serverState.value !is ServerState.Running) return
        // ponytail: Quick Tunnel URL stable while cloudflared alive; skip restart on Ktor-only bounce
        if (cloudflareTunnel.state.value is TunnelState.Running) {
            logger.i("Internet Sharing tunnel already running; skipping restart")
            return
        }
        logger.i("Starting Internet Sharing tunnel after server change")
        cloudflareTunnel.start(Constants.SERVER_PORT, tunnelErrorMessages())
    }

    fun activityResumed() {
        isActivityInForeground = true
        if (pendingNotificationRequests.isNotEmpty()) {
            serviceScope.launch {
                val requestsToForward = pendingNotificationRequests.toMap()
                pendingNotificationRequests.clear()
                requestsToForward.forEach { (ip, deferred) ->
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(ip.hashCode())
                    logger.d("Forwarding background IP request for $ip to foreground activity.")
                    _ipPermissionRequests.emit(IpPermissionRequest(ip, deferred))
                }
            }
        }
    }

    fun activityPaused() {
        isActivityInForeground = false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val passwordKey = getString(R.string.pref_key_server_password)
        val ipPermissionKey = getString(R.string.pref_key_ip_permission_enabled)

        // Check if a setting that requires a server restart was changed
        if (key == passwordKey || key == ipPermissionKey) {
            // Only restart if the server is currently running
            if (ktorServer != null) {
                logger.i("A server setting changed. Restarting Ktor server.")
                startKtorServer() // The restart is now handled by startKtorServer
            }
        }
    }

    // Called from the server to create a “refresh” event
    fun notifyFilePushed() {
        CoroutineScope(Dispatchers.Default).launch {
            _pullRefresh.emit(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        logger.d("onStartCommand: ${intent?.action}")
        when (intent?.action) {
            Constants.ACTION_START_SERVICE -> {
                val folderUriString = intent.getStringExtra(Constants.EXTRA_FOLDER_URI)
                if (folderUriString != null) {
                    currentSharedFolderUri = folderUriString.toUri()
                    startKtorServer()
                } else {
                    logger.e("Folder URI missing, stopping service")
                    _serverState.value = ServerState.Error("Folder URI missing.")
                    stopSelf()
                }
            }

            Constants.ACTION_STOP_SERVICE -> {
                internetSharingDesired = false
                serviceScope.launch { cloudflareTunnel.stop() }
                stopKtorServer(ServerState.UserStopped)
                stopSelf()
            }

            ACTION_STOP_INTERNET_SHARING -> {
                stopInternetSharing()
            }

            ACTION_IP_PERMISSION_RESPONSE -> handleIpPermissionResponse(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleIpPermissionResponse(intent: Intent) {
        val ipAddress = intent.getStringExtra(EXTRA_IP_ADDRESS)
        val approved = intent.getBooleanExtra(EXTRA_IP_PERMISSION_APPROVED, false)
        if (ipAddress == null) {
            logger.e("IP address missing in permission response intent.")
            return
        }
        val deferred = pendingNotificationRequests.remove(ipAddress)
        if (deferred != null) {
            if (!deferred.isCompleted) {
                logger.i("Completing permission for $ipAddress with result: $approved via notification.")
                deferred.complete(approved)
            }
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ipAddress.hashCode())
    }

    private fun startKtorServer() {
        serviceScope.launch {
            val keepTunnel = internetSharingDesired &&
                cloudflareTunnel.state.value is TunnelState.Running
            if (!keepTunnel) {
                cloudflareTunnel.stop()
            }

            _serverState.value = ServerState.Starting
            updateNotification()

            if (ktorServer != null) {
                logger.i("Stopping existing Ktor server for restart...")
                ktorServer?.stop(500, 1000)
                ktorServer = null
            }

            if (currentSharedFolderUri == null) {
                _serverState.value = ServerState.Error("Shared folder not set.")
                setServerActivePreference(false)
                updateNotification()
                return@launch
            }
            val baseDocFile =
                DocumentFile.fromTreeUri(this@FileServerService, currentSharedFolderUri!!)
            if (baseDocFile == null || !baseDocFile.canRead()) {
                _serverState.value = ServerState.Error("Shared folder not accessible.")
                setServerActivePreference(false)
                updateNotification()
                return@launch
            }

            try {
                val networkState = networkHelper.networkInfo.value
                val ipAddress = networkState.mainIp
                val tunnelOnly = ipAddress == null && canRunTunnelOnly(networkState)

                if (ipAddress == null && !tunnelOnly) {
                    _serverState.value = ServerState.AwaitNetwork
                    logger.e("Failed to get local IP address.")
                    setServerActivePreference(false)
                    updateNotification()
                    return@launch
                }

                val serviceProvider = { this@FileServerService }
                ktorServer =
                    embeddedServer(CIO, port = Constants.SERVER_PORT, host = "0.0.0.0", module = {
                        ktorServer(
                            applicationContext, serviceProvider, currentSharedFolderUri!!
                        )
                    }).apply {
                        start(wait = false)
                    }

                _serverState.value = ServerState.Running(networkState, Constants.SERVER_PORT, tunnelOnly)
                if (tunnelOnly) {
                    logger.i("Ktor Server started (tunnel-only) on port ${Constants.SERVER_PORT}")
                } else {
                    logger.i("Ktor Server started on $ipAddress:${Constants.SERVER_PORT}")
                }
                setServerActivePreference(true)
                updateNotification()
                restartTunnelIfDesired()
            } catch (e: Exception) {
                val cause = e.cause;
                if (cause is java.net.BindException){
                    logger.e("Port ${Constants.SERVER_PORT} is already in use. ")
                    _serverState.value = ServerState.Error("Port ${Constants.SERVER_PORT} is already in use.")
                };
                else{
                logger.e(e)
                _serverState.value = ServerState.Error("INTERNAL: Failed to start server: ${e.localizedMessage}")

                }
                ktorServer?.stop(1000, 2000)
                ktorServer = null
                internetSharingDesired = false
                setServerActivePreference(false)
                updateNotification()
            }
        }
    }

    private fun stopKtorServer(state: ServerState) {
        serviceScope.launch {
            if (state == ServerState.UserStopped) {
                internetSharingDesired = false
            }
            // Always tear down tunnel with the HTTP server so no orphan cloudflared remains.
            cloudflareTunnel.stop()
            try {
                ktorServer?.stop(1000, 2000)
            } catch (e: Exception) {
                logger.e(e, "Exception while stopping Ktor server $e")
            } finally {
                ktorServer = null
                _serverState.value = state
                logger.i("Ktor Server stopped.")
                setServerActivePreference(state is ServerState.Running)
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (state != ServerState.UserStopped) updateNotification() // if user stopped, the notification can be removed
            }

        }
    }

    private fun setServerActivePreference(active: Boolean) {
        sharedPreferences.edit { putBoolean(Constants.PREF_SERVER_ACTIVE, active) }
        ServerAppWidget.requestUpdate(this)
    }

    suspend fun requestIpApprovalFromClient(ipAddress: String): Boolean {
        logger.d("Requesting IP approval from $ipAddress")
        val now = System.currentTimeMillis()
        approvedIps.entries.removeIf { (_, expiryTime) -> expiryTime <= now }
        if (approvedIps.contains(ipAddress)) return true

        val deferred = CompletableDeferred<Boolean>()
        if (isActivityInForeground) {
            logger.d("Activity is in foreground. Emitting request to UI.")
            _ipPermissionRequests.emit(IpPermissionRequest(ipAddress, deferred))
        } else {
            logger.d("Activity is in background. Showing notification for IP permission.")
            pendingNotificationRequests[ipAddress] = deferred
            showIpPermissionNotification(ipAddress)
        }

        val approved = try {
            deferred.await()
        } catch (e: CancellationException) {
            logger.w("IP approval for $ipAddress was cancelled.")
            pendingNotificationRequests.remove(ipAddress)
            false
        }

        if (approved) {
            approvedIps[ipAddress] = now + Constants.IP_PERMISSION_VALIDITY_MS
        }
        return approved
    }

    fun isIpPermissionRequired(): Boolean =
        sharedPreferences.getBoolean(getString(R.string.pref_key_ip_permission_enabled), true)

    fun isPasswordProtectionEnabled(): Boolean =
        !sharedPreferences.getString(getString(R.string.pref_key_server_password), null)
            .isNullOrEmpty()

    fun getServerPassword(): String? =
        sharedPreferences.getString(getString(R.string.pref_key_server_password), null)

    fun checkPassword(providedPassword: String): Boolean = getServerPassword() == providedPassword

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = getString(R.string.notification_channel_description)
        val permissionChannel = NotificationChannel(
            PERMISSION_NOTIFICATION_CHANNEL_ID,
            "IP Permission Requests",
            NotificationManager.IMPORTANCE_HIGH
        )
        permissionChannel.description =
            "Shows notifications to allow or deny connections from new IP addresses."
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(permissionChannel)
    }

    private fun showIpPermissionNotification(ipAddress: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val uniqueId = ipAddress.hashCode()
        val contentIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, uniqueId, it, pendingIntentFlags)
        }
        val allowIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_IP_PERMISSION_RESPONSE
            putExtra(EXTRA_IP_ADDRESS, ipAddress)
            putExtra(EXTRA_IP_PERMISSION_APPROVED, true)
        }
        val allowPendingIntent =
            PendingIntent.getService(this, uniqueId * 2, allowIntent, pendingIntentFlags)
        val denyIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_IP_PERMISSION_RESPONSE
            putExtra(EXTRA_IP_ADDRESS, ipAddress)
            putExtra(EXTRA_IP_PERMISSION_APPROVED, false)
        }
        val denyPendingIntent =
            PendingIntent.getService(this, uniqueId * 2 + 1, denyIntent, pendingIntentFlags)
        val notification = NotificationCompat.Builder(this, PERMISSION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.permission_request_title))
            .setContentText(getString(R.string.permission_request_message, ipAddress))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(contentIntent)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.allow), allowPendingIntent)
            .addAction(0, getString(R.string.deny), denyPendingIntent).build()
        notificationManager.notify(uniqueId, notification)
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(Constants.NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        val stopIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)

        val tunnel = if (::cloudflareTunnel.isInitialized) cloudflareTunnel.state.value else TunnelState.Stopped
        val internetActive = tunnel is TunnelState.Running || tunnel is TunnelState.Starting
        val transfers = _transferProgress.value
        val primaryTransfer = transfers.firstOrNull()

        val (title, text) = when (val state = _serverState.value) {
            is ServerState.Running -> {
                val title = getString(R.string.file_server_notification_title)
                val text = when {
                    primaryTransfer != null -> {
                        val dir = if (primaryTransfer.direction == TransferProgress.Direction.DOWNLOAD) {
                            getString(R.string.transfer_direction_download)
                        } else {
                            getString(R.string.transfer_direction_upload)
                        }
                        val pct = primaryTransfer.percent
                        if (pct != null) {
                            getString(R.string.transfer_progress_notification, dir, primaryTransfer.fileName, pct)
                        } else {
                            getString(R.string.transfer_progress_notification_indeterminate, dir, primaryTransfer.fileName)
                        }
                    }
                    internetActive -> getString(R.string.file_server_notification_internet_sharing)
                    state.tunnelOnly -> getString(
                        R.string.server_notification_tunnel_only, state.port
                    )
                    else -> getString(
                        R.string.file_server_notification_text, state.hosts.mainIp, state.port
                    )
                }
                title to text
            }

            is ServerState.Starting -> getString(R.string.file_server_notification_title) to getString(
                R.string.server_starting
            )

            is ServerState.UserStopped -> getString(R.string.file_server_notification_title) to getString(
                R.string.server_stopped
            )
            is ServerState.AwaitNetwork -> getString(R.string.file_server_notification_title) to getString(
                R.string.waiting_for_network
            )

            is ServerState.Error -> getString(R.string.file_server_notification_title) to getString(
                R.string.server_error_format, state.message
            )
        }

        val builder = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pendingIntent).setOngoing(true)
            .addAction(R.drawable.ic_stop_black, getString(R.string.stop_server), stopPendingIntent)

        if (primaryTransfer != null) {
            val pct = primaryTransfer.percent
            if (pct != null) {
                builder.setProgress(100, pct, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            builder.setOnlyAlertOnce(true)
        }

        if (internetActive) {
            val stopTunnelIntent = Intent(this, FileServerService::class.java).apply {
                action = ACTION_STOP_INTERNET_SHARING
            }
            val stopTunnelPending = PendingIntent.getService(
                this, 1, stopTunnelIntent, pendingIntentFlags
            )
            builder.addAction(0, getString(R.string.stop_internet_sharing), stopTunnelPending)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        logger.d("FileServerService onDestroy")
        networkHelper.unregister()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        internetSharingDesired = false
        if (::cloudflareTunnel.isInitialized) {
            cloudflareTunnel.release()
        }
        stopKtorServer(ServerState.UserStopped) // Ensure server is stopped
        serviceJob.cancel() // Cancel all coroutines in this scope
        super.onDestroy()
    }

}