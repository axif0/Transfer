package com.matanh.transfer.server

import java.io.File
import java.io.InputStream

/**
 * Abstraction over OS process launch so tunnel lifecycle can be unit-tested
 * without a real cloudflared binary.
 */
interface TunnelProcessHandle {
    val inputStream: InputStream
    fun isAlive(): Boolean
    fun destroy()
    fun destroyForcibly()
    fun waitFor(timeoutMs: Long): Boolean
    fun exitValue(): Int
}

interface TunnelProcessLauncher {
    fun start(
        executable: File,
        arguments: List<String>,
        workingDirectory: File? = null,
    ): TunnelProcessHandle
}

class ProcessBuilderTunnelProcessLauncher : TunnelProcessLauncher {
    override fun start(
        executable: File,
        arguments: List<String>,
        workingDirectory: File?,
    ): TunnelProcessHandle {
        val command = ArrayList<String>(arguments.size + 1).apply {
            add(executable.absolutePath)
            addAll(arguments)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .directory(workingDirectory ?: executable.parentFile)
            .start()
        return object : TunnelProcessHandle {
            override val inputStream: InputStream get() = process.inputStream
            override fun isAlive(): Boolean = process.isAlive
            override fun destroy() {
                process.destroy()
            }
            override fun destroyForcibly() {
                process.destroyForcibly()
            }
            override fun waitFor(timeoutMs: Long): Boolean =
                process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            override fun exitValue(): Int = process.exitValue()
        }
    }
}
