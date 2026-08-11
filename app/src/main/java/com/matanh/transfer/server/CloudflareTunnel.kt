package com.matanh.transfer.server

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the packaged cloudflared child process for Cloudflare Quick Tunnels.
 * No UI / no Ktor. Origin is always http://127.0.0.1:<port>.
 */
class CloudflareTunnel(
    context: Context,
    private val launcher: TunnelProcessLauncher = ProcessBuilderTunnelProcessLauncher(),
    private val externalScope: CoroutineScope? = null,
) {
    private val appContext = context.applicationContext
    private val ownJob = SupervisorJob()
    private val scope = externalScope ?: CoroutineScope(Dispatchers.IO + ownJob)
    private val ownsScope = externalScope == null

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var processHandle: TunnelProcessHandle? = null
    private var monitorJob: Job? = null
    private var timeoutJob: Job? = null
    private val stopRequested = AtomicBoolean(false)

    companion object {
        private val logger = Timber.tag("TransferCloudflareTunnel")

        /** Milliseconds to wait for a trycloudflare.com URL after process start. */
        const val STARTUP_TIMEOUT_MS = 30_000L

        /** Grace period after destroy() before destroyForcibly(). */
        private const val DESTROY_GRACE_MS = 2_000L

        const val BINARY_NAME = "libcloudflared.so"

        // Pinned release — see docs/internet-sharing.md
        const val CLOUDFLARED_VERSION = "2025.8.1"

        /** ABI directory name → expected SHA-256 of packaged libcloudflared.so (hex). */
        val EXPECTED_SHA256: Map<String, String> = mapOf(
            "arm64-v8a" to "9e2088063c8b8f71ce4b15d65e6f4b1ef345f90c9c15e762cfd2bc8fc63cf22a",
            "x86_64" to "a66353004197ee4c1fcb68549203824882bba62378ad4d00d234bdb8251f1114",
        )
    }

    fun isRunning(): Boolean {
        val s = _state.value
        return s is TunnelState.Starting || s is TunnelState.Running
    }

    /**
     * Start Quick Tunnel to local origin. Idempotent while Starting/Running.
     */
    suspend fun start(
        port: Int,
        errorMessages: TunnelErrorMessages,
    ) = mutex.withLock {
        val current = _state.value
        if (current is TunnelState.Starting || current is TunnelState.Running) {
            logger.i("Tunnel start ignored — already $current")
            return@withLock
        }
        stopRequested.set(false)
        _state.value = TunnelState.Starting
        logger.i("Tunnel start requested for port $port")

        val executable = try {
            resolveExecutable(errorMessages)
        } catch (e: TunnelPrepareException) {
            logger.e(e, "Failed to prepare cloudflared executable")
            _state.value = TunnelState.Error(e.userMessage)
            return@withLock
        }
        logger.i("Selected executable: ${executable.absolutePath}")

        // Register via Android DNS + pass edge IPs — linux Go binary cannot resolve DNS on Android.
        val prepared = try {
            withContext(Dispatchers.IO) {
                QuickTunnelBootstrap.prepare(appContext, port)
            }
        } catch (e: Exception) {
            logger.e(e, "Quick Tunnel registration failed")
            _state.value = TunnelState.Error(errorMessages.startFailed)
            return@withLock
        }

        if (stopRequested.get()) {
            _state.value = TunnelState.Stopped
            return@withLock
        }

        val handle = try {
            launcher.start(executable, prepared.arguments, prepared.workDir)
        } catch (e: Exception) {
            logger.e(e, "Failed to start cloudflared process")
            _state.value = TunnelState.Error(errorMessages.startFailed)
            return@withLock
        }

        processHandle = handle
        // URL known from API registration — no need to wait for log scrape.
        _state.value = TunnelState.Running(prepared.publicUrl)
        logger.i("cloudflared process started; public URL ${prepared.publicUrl}")

        monitorJob?.cancel()
        timeoutJob?.cancel()
        timeoutJob = null

        monitorJob = scope.launch {
            consumeOutput(handle, errorMessages)
        }
    }

    /** Idempotent stop. */
    suspend fun stop() = mutex.withLock {
        stopRequested.set(true)
        timeoutJob?.cancel()
        timeoutJob = null
        stopProcessInternal()
        if (_state.value !is TunnelState.Stopped) {
            _state.value = TunnelState.Stopped
            logger.i("Tunnel stopped")
        }
    }

    fun release() {
        stopRequested.set(true)
        timeoutJob?.cancel()
        monitorJob?.cancel()
        try {
            processHandle?.destroy()
            processHandle?.destroyForcibly()
        } catch (_: Exception) {
        }
        processHandle = null
        _state.value = TunnelState.Stopped
        if (ownsScope) {
            scope.cancel()
        }
    }

    private suspend fun consumeOutput(
        handle: TunnelProcessHandle,
        errorMessages: TunnelErrorMessages,
    ) = withContext(Dispatchers.IO) {
        try {
            BufferedReader(InputStreamReader(handle.inputStream)).use { reader ->
                while (isActive && !stopRequested.get()) {
                    val line = reader.readLine() ?: break
                    logger.d("cloudflared: $line")
                    // URL already set from API registration; keep parser as soft confirmation only.
                    if (_state.value is TunnelState.Starting) {
                        val url = CloudflareTunnelUrlParser.parse(line)
                        if (url != null) {
                            timeoutJob?.cancel()
                            logger.i("Tunnel URL detected in logs: $url")
                            _state.value = TunnelState.Running(url)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopRequested.get()) {
                logger.e(e, "Error reading cloudflared output")
            }
        }

        if (stopRequested.get()) return@withContext

        val stillStarting = _state.value is TunnelState.Starting
        val wasRunning = _state.value is TunnelState.Running
        val exit = try {
            if (!handle.isAlive()) handle.exitValue() else -1
        } catch (_: Exception) {
            -1
        }
        logger.w("cloudflared process exited (code=$exit), state=${_state.value}")

        // Do not take [mutex] here — stop() may hold it while destroying the process.
        if (stopRequested.get()) return@withContext
        processHandle = null
        when {
            stillStarting -> _state.value = TunnelState.Error(errorMessages.startFailed)
            wasRunning -> _state.value = TunnelState.Error(errorMessages.unexpectedExit)
        }
    }

    private fun stopProcessInternal() {
        monitorJob?.cancel()
        monitorJob = null
        val handle = processHandle ?: return
        processHandle = null
        try {
            if (handle.isAlive()) {
                // Prefer graceful SIGTERM; force only if still alive after grace.
                handle.destroy()
                val exited = try {
                    handle.waitFor(DESTROY_GRACE_MS)
                } catch (_: Exception) {
                    false
                }
                if (!exited && handle.isAlive()) {
                    logger.w("cloudflared still alive after destroy(); using destroyForcibly()")
                    handle.destroyForcibly()
                    try {
                        handle.waitFor(1_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(e, "Error terminating cloudflared")
            try {
                handle.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        logger.i("Tunnel process cleanup complete")
    }

    private fun resolveExecutable(errorMessages: TunnelErrorMessages): File {
        val abi = selectAbi()
            ?: throw TunnelPrepareException(errorMessages.unsupportedAbi)
        logger.i("Selected ABI: $abi nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir}")

        // Prefer extracted jniLibs path (useLegacyPackaging=true).
        val fromJni = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (fromJni.isFile) {
            fromJni.setReadable(true, true)
            fromJni.setExecutable(true, true)
            if (fromJni.canExecute()) {
                logger.i("Using nativeLibraryDir binary: ${fromJni.absolutePath}")
                verifyChecksumOptional(fromJni, abi)
                return fromJni
            }
            logger.w("nativeLibraryDir binary not executable; copying to filesDir")
            return copyExecutable(fromJni.inputStream(), fromJni.length(), abi, errorMessages)
        }
        logger.w("nativeLibraryDir missing $BINARY_NAME — extracting from APK")

        // Extract libcloudflared.so from the installed APK (split-aware).
        val apkSources = listOfNotNull(
            appContext.applicationInfo.nativeLibraryDir, // already checked
            appContext.applicationInfo.sourceDir,
            appContext.applicationInfo.publicSourceDir,
        ) + (appContext.applicationInfo.splitSourceDirs?.toList().orEmpty())

        for (apkPath in apkSources) {
            val apk = File(apkPath)
            if (!apk.isFile || !apkPath.endsWith(".apk", ignoreCase = true)) continue
            val zipEntryNames = listOf(
                "lib/$abi/$BINARY_NAME",
                "lib/$abi/cloudflared",
            )
            try {
                java.util.zip.ZipFile(apk).use { zip ->
                    for (entryName in zipEntryNames) {
                        val entry = zip.getEntry(entryName) ?: continue
                        logger.i("Extracting $entryName from $apkPath")
                        zip.getInputStream(entry).use { input ->
                            return copyExecutable(input, entry.size, abi, errorMessages)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.w(e, "Failed reading $apkPath for cloudflared")
            }
        }

        // Last resort: assets/cloudflared/<abi>/cloudflared
        val assetPath = "cloudflared/$abi/cloudflared"
        try {
            appContext.assets.open(assetPath).use { input ->
                return copyExecutable(input, -1L, abi, errorMessages)
            }
        } catch (e: Exception) {
            logger.e(e, "Asset copy failed for $assetPath")
        }

        throw TunnelPrepareException(errorMessages.binaryUnavailable)
    }

    private fun copyExecutable(
        input: java.io.InputStream,
        expectedSize: Long,
        abi: String,
        errorMessages: TunnelErrorMessages,
    ): File {
        val destDir = File(appContext.filesDir, "cloudflared/$abi")
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw TunnelPrepareException(errorMessages.binaryUnavailable)
        }
        val dest = File(destDir, "cloudflared")
        // Skip rewrite if already present and size matches (fast path after first extract).
        if (dest.isFile && expectedSize > 0 && dest.length() == expectedSize && dest.canExecute()) {
            logger.i("Reusing extracted binary ${dest.absolutePath}")
            return dest
        }
        try {
            input.use { src ->
                dest.outputStream().use { out -> src.copyTo(out) }
            }
        } catch (e: Exception) {
            logger.e(e, "Failed writing cloudflared to filesDir")
            throw TunnelPrepareException(errorMessages.binaryUnavailable)
        }
        dest.setReadable(true, true)
        if (!dest.setExecutable(true, true)) {
            logger.w("setExecutable returned false for ${dest.absolutePath}")
        }
        if (!dest.isFile || !dest.canExecute()) {
            throw TunnelPrepareException(errorMessages.binaryUnavailable)
        }
        logger.i("Prepared executable ${dest.absolutePath} (${dest.length()} bytes)")
        verifyChecksumOptional(dest, abi)
        return dest
    }

    private fun selectAbi(): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            if (EXPECTED_SHA256.containsKey(abi)) return abi
            // Map common synonyms
            when (abi) {
                "arm64-v8a" -> return "arm64-v8a"
                "x86_64" -> return "x86_64"
            }
        }
        return null
    }

    private fun verifyChecksumOptional(file: File, abi: String) {
        val expected = EXPECTED_SHA256[abi] ?: return
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                logger.e("cloudflared SHA-256 mismatch for $abi: expected=$expected actual=$actual")
                // Soft fail: log only — some packaging paths may strip/repack.
                // Hard fail would brick installs after Play App Bundle splits; keep warning.
            } else {
                logger.i("cloudflared SHA-256 verified for $abi")
            }
        } catch (e: Exception) {
            logger.w(e, "SHA-256 verification skipped")
        }
    }

    private class TunnelPrepareException(val userMessage: String) : Exception(userMessage)
}

/** Localized user-facing error strings supplied by the service/UI layer. */
data class TunnelErrorMessages(
    val binaryUnavailable: String,
    val unsupportedAbi: String,
    val startFailed: String,
    val timeout: String,
    val unexpectedExit: String,
    val networkUnavailable: String,
)
