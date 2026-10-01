// SPDX-License-Identifier: GPL-3.0-only
// Protocol reference: SushyDev/tizen-homebrew service/src/tv/adb.js and tools/installing.js.
package dev.readiz.wgtinstaller.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.SocketFactory

object SdbWire {
    const val MAX = 4096
    data class Packet(val command: String, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf())
    fun checksum(bytes: ByteArray) = bytes.fold(0) { total, byte -> total + (byte.toInt() and 255) }
    fun encode(p: Packet): ByteArray {
        require(p.command.matches(Regex("[A-Z]{4}")) && p.payload.size <= MAX)
        val command = ByteBuffer.wrap(p.command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
        return ByteBuffer.allocate(24 + p.payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(p.arg0).putInt(p.arg1).putInt(p.payload.size)
            .putInt(checksum(p.payload)).putInt(command.inv()).put(p.payload).array()
    }
    fun read(input: InputStream, check: () -> Unit = {}): Packet {
        fun exact(n: Int): ByteArray {
            val b = ByteArray(n); var offset = 0
            while (offset < n) { check(); val got = input.read(b, offset, n - offset); if (got < 0) throw EOFException("SDB connection closed"); offset += got }
            return b
        }
        val h = ByteBuffer.wrap(exact(24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int; val a0 = h.int; val a1 = h.int; val length = h.int; val sum = h.int; val magic = h.int
        if (magic != command.inv() || length !in 0..MAX) throw BootstrapError("sdb.frame", "SDB 프레임이 손상되었습니다.")
        val data = exact(length)
        if (checksum(data) != sum) throw BootstrapError("sdb.checksum", "SDB 체크섬이 일치하지 않습니다.")
        val name = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), Charsets.US_ASCII)
        return Packet(name, a0, a1, data)
    }
    fun sync(tag: String, value: Int): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .put(tag.toByteArray(Charsets.US_ASCII)).putInt(value).array()
}

class ShellInterrupted(val output: String, cause: Throwable) : Exception("SDB 명령 결과가 확정되지 않았습니다.", cause)

/** Sequential streams on one transport; every WRTE waits for the peer's OKAY. */
class SdbSession private constructor(private val socket: Socket, private val cancel: Cancellation) : AutoCloseable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val cancelHook = cancel.onCancel { socket.close() }
    private var limit = SdbWire.MAX
    private var nextId = 12345
    private var deadline = Long.MAX_VALUE
    private var stream: Channel? = null
    private data class Channel(val local: Int, val remote: Int, val pending: ByteArrayOutputStream = ByteArrayOutputStream(), var closed: Boolean = false)
    val localAddress: String get() = socket.localAddress.hostAddress ?: ""

    companion object {
        fun connect(address: InetSocketAddress, cancel: Cancellation = Cancellation(), factory: SocketFactory = SocketFactory.getDefault()): SdbSession {
            cancel.check(); val socket = factory.createSocket()
            val duringConnect = cancel.onCancel { socket.close() }
            try {
                socket.connect(address, 8000); socket.soTimeout = 8000; socket.tcpNoDelay = true; socket.keepAlive = true
                return SdbSession(socket, cancel).also { session ->
                    session.send("CNXN", 0x01000000, SdbWire.MAX, "host::\u0000".toByteArray())
                    val response = session.read()
                    if (response.command == "AUTH") throw BootstrapError("sdb.auth", "이 TV는 지원하지 않는 키 인증을 요구합니다.")
                    if (response.command != "CNXN" || response.arg1 <= 0) throw BootstrapError("sdb.handshake", "SDB 핸드셰이크에 실패했습니다.")
                    session.limit = minOf(SdbWire.MAX, response.arg1)
                }
            } catch (e: Exception) { runCatching { socket.close() }; throw e }
            finally { duringConnect.close() }
        }
    }
    private fun send(command: String, a0: Int, a1: Int, data: ByteArray = byteArrayOf()) {
        cancel.check(); output.write(SdbWire.encode(SdbWire.Packet(command, a0, a1, data))); output.flush()
    }
    private fun read(): SdbWire.Packet {
        cancel.check()
        if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("SDB operation deadline")
        val left = if (deadline == Long.MAX_VALUE) 8000 else maxOf(1, (deadline - System.nanoTime()) / 1_000_000).toInt()
        socket.soTimeout = minOf(15000, left)
        val deadlineInput = object : java.io.FilterInputStream(input) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                while (true) {
                    cancel.check()
                    if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("SDB operation deadline")
                    try { return super.read(bytes, offset, length) }
                    catch (e: java.net.SocketTimeoutException) {
                        // Preserve partially read frames while a slow install is silent.
                        if (deadline == Long.MAX_VALUE || System.nanoTime() >= deadline) throw e
                    }
                }
            }
        }
        return SdbWire.read(deadlineInput) {
            cancel.check()
            if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("SDB operation deadline")
        }
    }
    private fun open(service: String): Channel {
        check(stream == null); require(!service.contains('\u0000') && service.length < 1024)
        val id = nextId++; send("OPEN", id, 0, "$service\u0000".toByteArray())
        repeat(128) {
            val p = read()
            if (p.arg1 != id) return@repeat // A previous stream's delayed CLSE/OKAY.
            if (p.command == "CLSE") throw BootstrapError("sdb.open", "TV가 요청한 SDB 채널을 거부했습니다.")
            if (p.command != "OKAY" || p.arg0 == 0) throw BootstrapError("sdb.open", "예상하지 못한 SDB 응답입니다.")
            return Channel(id, p.arg0).also { stream = it }
        }
        throw BootstrapError("sdb.open", "SDB 채널을 열지 못했습니다.")
    }
    private fun event(c: Channel): SdbWire.Packet {
        while (true) {
            val p = read()
            if (p.arg1 != c.local) continue
            if (p.arg0 != c.remote) throw BootstrapError("sdb.stream", "SDB 채널 ID가 일치하지 않습니다.")
            if (p.command == "WRTE") {
                if (c.pending.size().toLong() + p.payload.size > 2 * 1024 * 1024) throw BootstrapError("sdb.output", "SDB 응답이 너무 큽니다.")
                c.pending.write(p.payload); send("OKAY", c.local, c.remote)
            }
            if (p.command == "CLSE") { c.closed = true; send("CLSE", c.local, c.remote) }
            return p
        }
    }
    private fun write(c: Channel, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (c.closed) throw EOFException("SDB channel closed")
            val end = minOf(bytes.size, offset + limit)
            send("WRTE", c.local, c.remote, bytes.copyOfRange(offset, end))
            while (true) {
                val reply = event(c)
                if (reply.command == "OKAY") break
                if (reply.command == "CLSE") throw EOFException("SDB channel closed before ACK")
            }
            offset = end
        }
    }
    private fun closeChannel(c: Channel) {
        if (!c.closed) runCatching { send("CLSE", c.local, c.remote) }
        c.closed = true; if (stream === c) stream = null
    }
    @Synchronized
    fun exec(service: String, timeoutMillis: Long = 15000, settled: (String) -> Boolean = { false }): String {
        deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val c = open(service)
        try {
            while (true) {
                event(c)
                val text = c.pending.toString("UTF-8")
                if (settled(text) || c.closed) return text
            }
        } catch (e: Exception) { throw ShellInterrupted(c.pending.toString("UTF-8"), e) }
        finally { closeChannel(c); deadline = Long.MAX_VALUE }
    }
    fun duid(): String = Safety.duid(exec("shell:0 getduid", settled = { it.contains('\n') }).lineSequence().first().trim())

    @Synchronized
    fun push(path: String, data: ByteArray, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(path in setOf(REMOTE_WGT, REMOTE_PROFILE)) { "Only fixed package/profile staging paths are writable" }
        deadline = System.nanoTime() + 180_000_000_000L
        val c = open("sync:")
        try {
            val mode = 33261 // Upstream 0755 staging compatibility; these files contain NO private keys.
            val target = "$path,$mode".toByteArray()
            write(c, SdbWire.sync("SEND", target.size) + target)
            var offset = 0
            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(offset + 4000, data.size))
                write(c, SdbWire.sync("DATA", chunk.size) + chunk)
                offset += chunk.size; progress(offset, data.size)
            }
            write(c, SdbWire.sync("DONE", (System.currentTimeMillis() / 1000).toInt()))
            while (true) {
                val reply = c.pending.toByteArray()
                if (reply.size >= 8) {
                    val tag = String(reply, 0, 4, Charsets.US_ASCII)
                    val length = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (length !in 0..65536) throw BootstrapError("sdb.sync", "잘못된 파일 전송 응답입니다.")
                    if (tag == "OKAY" && length == 0) return
                    if (tag == "FAIL" && reply.size >= 8 + length) throw BootstrapError("sdb.sync", "TV가 파일 전송을 거부했습니다. 개발자 호스트 설정과 저장 공간을 확인하세요.")
                    if (tag != "FAIL") throw BootstrapError("sdb.sync", "예상하지 못한 파일 전송 응답입니다.")
                }
                if (c.closed) throw BootstrapError("sdb.sync", "파일 전송 완료 응답 전에 연결이 닫혔습니다.")
                event(c)
            }
        } finally { closeChannel(c); deadline = Long.MAX_VALUE }
    }
    override fun close() { stream?.let { closeChannel(it) }; cancelHook.close(); runCatching { socket.close() } }
}

const val STAGING_DIR = "/home/owner/share/tmp/sdk_tools"
const val REMOTE_WGT = "$STAGING_DIR/package.wgt"
const val REMOTE_PROFILE = "$STAGING_DIR/device-profile.xml"
