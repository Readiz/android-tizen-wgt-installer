// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class HttpReply(val status: Int, val body: ByteArray) {
    fun requireOk(): ByteArray {
        if (status !in 200..299) throw BootstrapError("http.$status", "서버가 요청을 거부했습니다(HTTP $status). 인증서 발급 또는 네트워크 상태를 확인하세요.")
        return body
    }
}

interface HttpTransport {
    fun request(url: String, method: String = "GET", body: ByteArray? = null, headers: Map<String, String> = emptyMap(), limit: Int = 4 * 1024 * 1024): HttpReply
}

/** HTTPS for cloud endpoints; LAN HTTP is explicitly limited to the selected private IP. */
class SafeHttp(
    private val cancel: Cancellation,
    private val lanHost: String? = null,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : HttpTransport {
    private val cloudHosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com", "download.tizen.org", "svdca.samsungqbe.com")
    private fun allowed(url: URL) {
        require(url.userInfo == null && url.ref == null)
        if (lanHost != null) require(url.protocol == "http" && url.host == Safety.privateIpv4(lanHost) && url.port in setOf(8001)) { "허용하지 않은 LAN 주소입니다." }
        else require(url.protocol == "https" && url.host in cloudHosts && url.port in setOf(-1, 443)) { "허용하지 않은 다운로드 서버입니다." }
    }
    override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
        require(method in setOf("GET", "POST")); var target = java.net.URI(url).toURL()
        repeat(6) {
            cancel.check(); allowed(target)
            val connection = open(target)
            val hook = cancel.onCancel { connection.disconnect() }
            try {
                connection.connectTimeout = 8000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
                connection.requestMethod = method; connection.setRequestProperty("User-Agent", "WgtInstaller-Android/0.2")
                headers.forEach { (key, value) -> require(!key.contains('\n') && !value.contains('\n')); connection.setRequestProperty(key, value) }
                if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (method != "GET" || lanHost != null) throw BootstrapError("http.redirect", "인증 요청 또는 LAN 요청의 리디렉션을 거부했습니다.")
                    target = target.toURI().resolve(connection.getHeaderField("Location") ?: error("Redirect without location")).toURL(); return@repeat
                }
                if (connection.contentLengthLong > limit) throw BootstrapError("size.limit", "다운로드 파일이 허용 크기를 초과했습니다.")
                // Never include server error bodies in diagnostics: they can echo credentials.
                if (status !in 200..299) return HttpReply(status, byteArrayOf())
                val began = System.nanoTime(); val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                connection.inputStream.use { stream -> while (true) {
                    cancel.check()
                    if (System.nanoTime() - began > 180_000_000_000L) throw BootstrapError("http.timeout", "다운로드 제한 시간을 초과했습니다.")
                    val n = stream.read(buffer); if (n < 0) break
                    if (out.size().toLong() + n > limit) throw BootstrapError("size.limit", "다운로드 파일이 허용 크기를 초과했습니다.")
                    out.write(buffer, 0, n)
                } }
                return HttpReply(status, out.toByteArray())
            } finally { hook.close(); connection.disconnect() }
        }
        throw BootstrapError("http.redirect", "리디렉션 횟수가 너무 많습니다.")
    }
}

object Multipart {
    class Body(val bytes: ByteArray, val contentType: String)
    fun csr(fields: Map<String, String>, name: String, csr: String): Body {
        require(name.matches(Regex("[a-z]+\\.csr")))
        val boundary = "----TVBootstrap" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        fun put(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        fields.forEach { (k, v) ->
            require(k.matches(Regex("[a-z_]+")) && !v.contains(boundary))
            put("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        put("--$boundary\r\nContent-Disposition: form-data; name=\"csr\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n$csr\r\n--$boundary--\r\n")
        return Body(out.toByteArray(), "multipart/form-data; boundary=$boundary")
    }
}
