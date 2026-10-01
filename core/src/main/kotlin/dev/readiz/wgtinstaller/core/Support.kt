// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class BootstrapError(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

class Cancellation {
    private val cancelled = AtomicBoolean(false)
    private val hooks = CopyOnWriteArrayList<() -> Unit>()
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw BootstrapError("cancelled", "작업을 중단했습니다. 설치 명령이 이미 전송됐다면 TV에서 결과를 확인하세요.") }
    fun onCancel(hook: () -> Unit): AutoCloseable {
        hooks.add(hook)
        if (cancelled.get()) runCatching { hook() }
        return AutoCloseable { hooks.remove(hook) }
    }
    fun cancel() { if (cancelled.compareAndSet(false, true)) hooks.forEach { runCatching { it() } } }
}

fun InputStream.readBounded(limit: Int, cancellation: Cancellation = Cancellation()): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        cancellation.check()
        val n = read(buf)
        if (n < 0) break
        if (n == 0) continue
        if (out.size().toLong() + n > limit) throw BootstrapError("size.limit", "허용된 데이터 크기를 초과했습니다.")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

object Safety {
    fun privateIpv4(value: String): String {
        val parts = value.trim().split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }) { "TV의 IPv4 주소를 입력하세요." }
        val p = parts.map(String::toInt)
        require(p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)) { "같은 사설 Wi-Fi 안의 TV만 지원합니다." }
        return p.joinToString(".")
    }
    fun packageId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9]{10}"))) { "지원하지 않는 Tizen 패키지 ID입니다." }
        return value
    }
    fun appId(value: String, pkg: String): String {
        require(value.startsWith("${packageId(pkg)}.") && value.matches(Regex("[A-Za-z0-9_.-]{11,128}"))) { "안전하지 않은 앱 ID입니다." }
        return value
    }
    fun duid(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9]{10,64}"))) { "TV에서 유효한 DUID를 읽지 못했습니다." }
        return value
    }
    fun zipName(value: String): String {
        require(value.isNotEmpty() && value.length <= 1024 && !value.startsWith('/') && !value.contains('\\') && !value.contains(':') && !value.contains('\u0000')) { "안전하지 않은 ZIP 경로입니다." }
        require(value.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "안전하지 않은 ZIP 경로입니다." }
        return value
    }
    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun constantEqual(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    fun maskDuid(value: String) = value.take(3) + "…" + value.takeLast(3)
    fun literalIpv4(value: String): InetAddress = InetAddress.getByAddress(privateIpv4(value).split('.').map { it.toInt().toByte() }.toByteArray())
}
