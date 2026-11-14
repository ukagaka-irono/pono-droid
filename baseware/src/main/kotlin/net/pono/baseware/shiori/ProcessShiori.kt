package net.pono.baseware.shiori

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Windows DLL ホストとのバイト列の往復。Windows ではホストを直接、Android では別途用意した
 * Wine/CPU 互換ランタイムを [command] で起動する。ランタイムそのものはこのクラスには含まない。
 * [guestPath] は Wine のドライブに対応するパスへの変換。辞書側から実行コマンドを指定させないこと。
 * いつも [ShioriSession] から呼び、時間切れは [abort] で待機中の読み書きごと止める。
 */
class ProcessShiori(
    private val module: WindowsModule,
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val guestPath: (File) -> String = { it.absolutePath },
) : Shiori {
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var onCrash: (() -> Unit)? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var loaded = false

    override fun load(dir: File): Boolean {
        check(process == null) { "A module host is already running" }
        require(command.isNotEmpty()) { "No Windows module host configured" }
        require(module.file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())) { "DLL is outside the module directory" }
        stopping = false
        val worker = ProcessBuilder(command).directory(dir).also { it.environment().putAll(environment) }.start()
        process = worker
        input = DataInputStream(worker.inputStream)
        output = DataOutputStream(worker.outputStream)
        Thread({
            // Always drain diagnostics, without retaining request text or credentials in memory or logs.
            runCatching { worker.errorStream.use { stream -> val buffer = ByteArray(4096); while (stream.read(buffer) >= 0) { /* drain */ } } }
        }, "windows-module-stderr").apply { isDaemon = true; start() }
        Thread({
            worker.waitFor()
            if (!stopping && process === worker) runCatching { onCrash?.invoke() }
            // The client may still be reading the final acknowledgement after the worker exits.
            // Keep its protocol streams open until unload/load failure has finished reading.
            runCatching { worker.errorStream.close() }
        }, "windows-module-exit").apply { isDaemon = true; start() }
        try {
            val hello = receive()
            if (hello.first != 0 || !hello.second.contentEquals("PONO-MODULE/1".toByteArray(Charsets.US_ASCII))) {
                throw IOException("Unexpected Windows module host handshake")
            }
            val reply = exchange(1, guestPath(module.file.canonicalFile).toByteArray(Charsets.UTF_8))
            if (reply.first !in 0..1) throw IOException("Unexpected Windows module load response: ${reply.first}")
            loaded = reply.first == 0
            if (!loaded) { stopping = true; abort(); closeStreams() }
            return loaded
        } catch (e: Exception) {
            stopping = true
            abort()
            closeStreams()
            throw e
        }
    }

    override fun request(request: ByteArray): ByteArray? {
        check(loaded) { "Windows module is not loaded" }
        val (status, bytes) = exchange(2, request)
        return when (status) {
            0 -> bytes
            3 -> null
            else -> throw IOException("Unexpected Windows module response: $status")
        }
    }

    override fun unload() {
        stopping = true
        try {
            if (loaded && process?.isAlive == true) {
                exchange(3, byteArrayOf())
                exchange(4, byteArrayOf())
            }
        } finally {
            loaded = false
            abort()
            closeStreams()
        }
    }

    override fun setOnCrash(listener: () -> Unit) { onCrash = listener }

    override fun abort() {
        process?.destroyForcibly()
        // Killing the worker closes the pipes and releases a request blocked in receive().
        // Do not take the request lock here: abort is called by the timeout thread.
    }

    private fun closeStreams() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        input = null
        output = null
    }

    private fun exchange(command: Int, bytes: ByteArray): Pair<Int, ByteArray> {
        require(bytes.size <= MAX_FRAME) { "Windows module request exceeds $MAX_FRAME bytes" }
        val stream = output ?: throw IOException("Windows module host is not connected")
        stream.writeByte(command)
        stream.writeInt(Integer.reverseBytes(bytes.size))
        stream.write(bytes)
        stream.flush()
        val reply = receive()
        if (reply.first == 2) throw IOException("Windows module host: " + reply.second.toString(Charsets.UTF_8).take(1024))
        return reply
    }

    private fun receive(): Pair<Int, ByteArray> {
        val stream = input ?: throw IOException("Windows module host is not connected")
        val status = stream.readUnsignedByte()
        val size = Integer.reverseBytes(stream.readInt())
        if (size !in 0..MAX_FRAME) throw IOException("Invalid Windows module response length")
        val bytes = ByteArray(size)
        stream.readFully(bytes)
        return status to bytes
    }

    companion object { const val MAX_FRAME = 16 * 1024 * 1024 }
}
