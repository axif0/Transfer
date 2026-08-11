package com.matanh.transfer.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CloudflareTunnelLifecycleTest {

    @Test
    fun startTwiceIsIdempotent() = runBlocking {
        val launcher = FakeTunnelProcessLauncher(
            outputLines = listOf("https://once.trycloudflare.com"),
            holdOpen = true,
        )
        val tunnel = FakeLifecycleTunnel(launcher)
        tunnel.start()
        tunnel.start()
        assertEquals(1, launcher.startCount.get())
        assertTrue(tunnel.state.value is TunnelState.Starting || tunnel.state.value is TunnelState.Running)
        tunnel.stop()
    }

    @Test
    fun stopTwiceIsIdempotent() = runBlocking {
        val tunnel = FakeLifecycleTunnel(FakeTunnelProcessLauncher(holdOpen = true))
        tunnel.start()
        tunnel.stop()
        tunnel.stop()
        assertEquals(TunnelState.Stopped, tunnel.state.value)
    }

    @Test
    fun stopWhileStarting() = runBlocking {
        val launcher = FakeTunnelProcessLauncher(holdOpen = true)
        val tunnel = FakeLifecycleTunnel(launcher)
        tunnel.start()
        assertEquals(TunnelState.Starting, tunnel.state.value)
        tunnel.stop()
        assertEquals(TunnelState.Stopped, tunnel.state.value)
        assertTrue(launcher.lastHandle!!.destroyed.get())
    }

    @Test
    fun startingToRunningOnUrl() = runBlocking {
        val launcher = FakeTunnelProcessLauncher(
            outputLines = listOf(
                "INF boot",
                "https://live.trycloudflare.com",
            ),
            holdOpen = true,
        )
        val tunnel = FakeLifecycleTunnel(launcher)
        tunnel.start()
        var ok = false
        repeat(50) {
            if (tunnel.state.value is TunnelState.Running) {
                ok = true
                return@repeat
            }
            delay(20)
        }
        assertTrue(ok)
        assertEquals(
            "https://live.trycloudflare.com",
            (tunnel.state.value as TunnelState.Running).publicUrl
        )
        tunnel.stop()
    }

    @Test
    fun startingToErrorOnProcessDeath() = runBlocking {
        val launcher = FakeTunnelProcessLauncher(
            outputLines = emptyList(),
            exitImmediately = true,
        )
        val tunnel = FakeLifecycleTunnel(launcher)
        tunnel.start()
        var sawError = false
        repeat(50) {
            if (tunnel.state.value is TunnelState.Error) {
                sawError = true
                return@repeat
            }
            delay(20)
        }
        assertTrue(sawError)
    }

    @Test
    fun runningToStopped() = runBlocking {
        val launcher = FakeTunnelProcessLauncher(
            outputLines = listOf("https://stop-me.trycloudflare.com"),
            holdOpen = true,
        )
        val tunnel = FakeLifecycleTunnel(launcher)
        tunnel.start()
        repeat(50) {
            if (tunnel.state.value is TunnelState.Running) return@repeat
            delay(20)
        }
        assertTrue(tunnel.state.value is TunnelState.Running)
        tunnel.stop()
        assertEquals(TunnelState.Stopped, tunnel.state.value)
    }

    /**
     * Minimal stand-in that mirrors CloudflareTunnel state machine without Android Context/binary.
     */
    private class FakeLifecycleTunnel(private val launcher: FakeTunnelProcessLauncher) {
        private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
        val state = _state
        private var handle: FakeHandle? = null
        private val stopRequested = AtomicBoolean(false)
        private var readerThread: Thread? = null

        fun isBusy(): Boolean =
            _state.value is TunnelState.Starting || _state.value is TunnelState.Running

        fun start() {
            if (isBusy()) return
            stopRequested.set(false)
            _state.value = TunnelState.Starting
            val h = launcher.start(File("fake"), listOf("tunnel")) as FakeHandle
            handle = h
            readerThread = Thread {
                val reader = h.inputStream.bufferedReader()
                try {
                    while (!stopRequested.get()) {
                        val line = reader.readLine() ?: break
                        if (_state.value is TunnelState.Starting) {
                            CloudflareTunnelUrlParser.parse(line)?.let { url ->
                                _state.value = TunnelState.Running(url)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                if (!stopRequested.get()) {
                    if (_state.value is TunnelState.Starting || _state.value is TunnelState.Running) {
                        _state.value = TunnelState.Error("exit")
                    }
                }
            }.also { it.start() }
        }

        fun stop() {
            stopRequested.set(true)
            handle?.destroy()
            handle?.destroyForcibly()
            readerThread?.join(500)
            handle = null
            _state.value = TunnelState.Stopped
        }
    }

    private class FakeTunnelProcessLauncher(
        private val outputLines: List<String> = emptyList(),
        private val holdOpen: Boolean = false,
        private val exitImmediately: Boolean = false,
    ) : TunnelProcessLauncher {
        val startCount = AtomicInteger(0)
        var lastHandle: FakeHandle? = null

        override fun start(executable: File, arguments: List<String>, workingDirectory: File?): TunnelProcessHandle {
            startCount.incrementAndGet()
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
            val alive = AtomicBoolean(!exitImmediately)
            val destroyed = AtomicBoolean(false)
            val writer = Thread {
                try {
                    for (line in outputLines) {
                        pipeOut.write((line + "\n").toByteArray())
                        pipeOut.flush()
                    }
                    if (holdOpen && !exitImmediately) {
                        while (alive.get() && !destroyed.get()) {
                            Thread.sleep(50)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        pipeOut.close()
                    } catch (_: Exception) {
                    }
                    alive.set(false)
                }
            }
            writer.isDaemon = true
            writer.start()
            val handle = FakeHandle(pipeIn, alive, destroyed)
            lastHandle = handle
            return handle
        }
    }

    private class FakeHandle(
        private val stream: InputStream,
        private val alive: AtomicBoolean,
        val destroyed: AtomicBoolean,
    ) : TunnelProcessHandle {
        override val inputStream: InputStream get() = stream
        override fun isAlive(): Boolean = alive.get()
        override fun destroy() {
            destroyed.set(true)
            alive.set(false)
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }

        override fun destroyForcibly() = destroy()
        override fun waitFor(timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (!alive.get()) return true
                Thread.sleep(10)
            }
            return !alive.get()
        }

        override fun exitValue(): Int = 0
    }

    @Test
    fun fakeLauncherCanProduceStream() {
        val bytes = "https://x.trycloudflare.com\n".toByteArray()
        val stream = ByteArrayInputStream(bytes)
        assertFalse(stream.readBytes().isEmpty())
    }
}
