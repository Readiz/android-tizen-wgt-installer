// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.simulator

import dev.readiz.wgtinstaller.core.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Protocol simulator only; never a Samsung certificate/install-policy emulator. Loopback-bound. */
class MockTv(var installOutput: String = "app_id[DemoWgt001] install completed\n", val maxData: Int = 4096, val emptySyncReply: Boolean = false, val identity: WidgetIdentity = Wgt.identity(DemoFixtures.widget()), val duid: String = "DEMODUID0001") : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val active = CopyOnWriteArrayList<Socket>()
    private val pool = Executors.newCachedThreadPool { task -> Thread(task, "wgt-demo-loopback").apply { isDaemon = true } }
    private val stopped = AtomicBoolean(false)
    val errors = CopyOnWriteArrayList<Throwable>()
    val files = ConcurrentHashMap<String, ByteArray>()
    val modes = ConcurrentHashMap<String, Int>()
    val installCalls = AtomicInteger()
    @Volatile var installed = false
    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
    init { pool.execute {
        while (!stopped.get()) {
            try { val socket = server.accept(); active.add(socket); pool.execute { serve(socket) } }
            catch (e: Exception) { if (!stopped.get()) errors.add(e) }
        }
    } }
    fun connect(cancel: Cancellation = Cancellation()) = SdbSession.connect(address, cancel)
    private fun serve(socket: Socket) {
        try { socket.use { s ->
            s.soTimeout = 15000
            val input = s.getInputStream(); val output = s.getOutputStream()
            fun send(command: String, a: Int, b: Int, data: ByteArray = byteArrayOf()) {
                // Deliberately fragment TCP writes. The packet codec must read a full header/payload.
                val frame = SdbWire.encode(SdbWire.Packet(command, a, b, data))
                var offset = 0
                while (offset < frame.size) { val n = minOf(7, frame.size - offset); output.write(frame, offset, n); offset += n }
                output.flush()
            }
            val hello = SdbWire.read(input); check(hello.command == "CNXN" && hello.payload.toString(Charsets.UTF_8) == "host::\u0000")
            send("CNXN", 0x01000000, maxData, "device::mock\u0000".toByteArray())
            var local = -1; var remote = 70; var service = ""; var sync = ByteArrayOutputStream()
            while (!stopped.get()) {
                val p = SdbWire.read(input)
                when (p.command) {
                    "OPEN" -> {
                        local = p.arg0; remote++; service = p.payload.toString(Charsets.UTF_8).removeSuffix("\u0000"); sync = ByteArrayOutputStream()
                        send("OKAY", remote, local)
                        if (service != "sync:" && service != "shell:0 wait") {
                            val text = when {
                                service == "shell:0 getduid" -> "$duid\n"
                                service == "shell:0 applist" -> "'Other App' 'ABCDEFGHIJ.Other'\n" + (if (installed) "'${identity.name}' '${identity.appId}'\n" else "")
                                service.startsWith("shell:0 vd_appinstall ") -> { installCalls.incrementAndGet(); if (InstallEvidence.failure(installOutput) == null) installed = true; installOutput }
                                service == "shell:0 unicode" -> "한글 분할 출력\n"
                                else -> "unknown\n"
                            }
                            if (text.isNotEmpty()) send("WRTE", remote, local, text.toByteArray())
                            else send("CLSE", remote, local)
                        }
                    }
                    "WRTE" -> {
                        check(service == "sync:"); check(p.arg0 == local && p.arg1 == remote); check(p.payload.size <= maxData)
                        sync.write(p.payload)
                        val bytes = sync.toByteArray(); var cursor = 0; var path: String? = null; var mode = 0; val data = ByteArrayOutputStream(); var done = false
                        while (cursor + 8 <= bytes.size) {
                            val tag = String(bytes, cursor, 4, Charsets.US_ASCII); val n = ByteBuffer.wrap(bytes, cursor + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            if (tag == "DONE") { done = true; break }
                            if (cursor + 8L + n > bytes.size) break
                            check(n >= 0)
                            when (tag) {
                                "SEND" -> { val target = String(bytes, cursor + 8, n, Charsets.UTF_8); path = target.substringBeforeLast(','); mode = target.substringAfterLast(',').toInt() }
                                "DATA" -> data.write(bytes, cursor + 8, n)
                                else -> error("Bad sync tag $tag")
                            }; cursor += 8 + n
                        }
                        if (done) {
                            files[path!!] = data.toByteArray(); modes[path] = mode
                            // The sync reply may arrive before the final transport ACK.
                            if (!emptySyncReply) send("WRTE", remote, local, SdbWire.sync("OKAY", 0))
                        }
                        if (maxData == 1024 && !done) { Thread.sleep(5); check(input.available() == 0) { "Client pipelined a WRTE before receiving OKAY" } }
                        send("OKAY", remote, local)
                        if (done && emptySyncReply) send("CLSE", remote, local)
                    }
                    "OKAY" -> if (service != "sync:") send("CLSE", remote, local)
                    "CLSE" -> Unit
                    else -> error("Unexpected ${p.command}")
                }
            }
        } } catch (_: EOFException) { } catch (_: SocketException) { } catch (e: Throwable) { if (!stopped.get()) errors.add(e) }
        finally { active.remove(socket) }
    }
    override fun close() { stopped.set(true); server.close(); active.forEach { runCatching { it.close() } }; pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
}
