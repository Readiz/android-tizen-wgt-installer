// SPDX-License-Identifier: GPL-3.0-only
// Proprietary Samsung callback shape matches SushyDev/tizen-homebrew tools/minting.js.
package dev.readiz.wgtinstaller.core

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

class SamsungAccount(val accessToken: String, val userId: String, val email: String) {
    override fun toString() = "SamsungAccount([REDACTED])"
}

object LoginProtocol {
    const val CALLBACK = "http://localhost:4794/signin/callback"
    fun state(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun signInUrl(state: String): String = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate" +
        "?locale=ko_KR&clientId=v285zxnl3h&redirect_uri=${URLEncoder.encode(CALLBACK, "UTF-8")}&state=${URLEncoder.encode(state, "UTF-8")}&tokenType=TOKEN"
    fun fields(encoded: String, preservePlus: Boolean = false): Map<String, String> {
        val out = linkedMapOf<String, String>()
        encoded.split('&').filter { it.isNotEmpty() }.forEach { part ->
            val equals = part.indexOf('='); require(equals >= 0)
            fun decode(s: String) = URLDecoder.decode(if (preservePlus) s.replace("+", "%2B") else s, "UTF-8")
            val key = decode(part.substring(0, equals)); require(!out.containsKey(key)) { "Duplicate callback field" }
            out[key] = decode(part.substring(equals + 1))
        }
        return out
    }
    fun account(params: Map<String, String>, expected: String): SamsungAccount {
        require(Safety.constantEqual(params["state"].orEmpty(), expected)) { "Login state mismatch" }
        val m = Json.obj(params["code"] ?: error("Missing callback code"))
        val token = m.string("access_token"); val userId = m.string("userId"); val email = m.string("inputEmailID")
        require(token.length in 8..32768 && userId.length in 1..256 && email.length in 3..254)
        require(!token.contains('\r') && !token.contains('\n') && !userId.contains('\n'))
        return SamsungAccount(token, userId, email)
    }
}

/** Fixed loopback redirect, fresh 256-bit state, bounded request parsing, one-shot success. */
class SamsungLogin(private val cancel: Cancellation, private val port: Int = 4794) : AutoCloseable {
    private val state = LoginProtocol.state()
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    private val hook = cancel.onCancel { server.close() }
    fun url(): String = LoginProtocol.signInUrl(state)
    fun await(timeoutMillis: Long = 600000): SamsungAccount {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < end) {
            cancel.check()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use { client ->
                client.soTimeout = 5000
                val requestHook = cancel.onCancel { client.close() }
                var validState = false
                try {
                    require(client.inetAddress.isLoopbackAddress)
                    val input = BufferedInputStream(client.getInputStream()); var read = 0
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) { require(++read <= 16384); val b = input.read(); require(b >= 0); if (b == 10) break; out.append(b.toChar()) }
                        return out.toString().removeSuffix("\r")
                    }
                    val first = line().split(' '); require(first.size == 3 && first[2].startsWith("HTTP/1."))
                    val method = first[0]; require(method == "GET" || method == "POST")
                    val uri = URI(first[1]); require(!uri.isAbsolute && uri.rawPath == "/signin/callback")
                    val headers = linkedMapOf<String, String>()
                    while (true) { val text = line(); if (text.isEmpty()) break; val colon = text.indexOf(':'); require(colon > 0); val k = text.substring(0, colon).lowercase(); require(k !in headers); headers[k] = text.substring(colon + 1).trim() }
                    require(headers["host"] in setOf("localhost:$port", "127.0.0.1:$port")); require("transfer-encoding" !in headers)
                    val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..65536)
                    val body = ByteArray(length); var offset = 0
                    while (offset < length) { cancel.check(); val n = input.read(body, offset, length - offset); require(n > 0); offset += n }
                    val params = if (method == "POST") {
                        require(headers["content-type"]?.substringBefore(';')?.trim() == "application/x-www-form-urlencoded")
                        LoginProtocol.fields(body.toString(Charsets.UTF_8), preservePlus = true)
                    } else { require(length == 0); LoginProtocol.fields(uri.rawQuery.orEmpty()) }
                    validState = Safety.constantEqual(params["state"].orEmpty(), state)
                    val account = LoginProtocol.account(params, state)
                    respond(client.getOutputStream(), 200, "삼성 로그인이 완료되었습니다. 설치 앱으로 돌아가세요.")
                    return account
                } catch (_: Exception) {
                    // Do not close the listener for invalid requests; never echo tokens or input.
                    runCatching { respond(client.getOutputStream(), 400, "인증 응답을 확인하지 못했습니다. 설치 앱에서 로그인 상태를 확인하세요.") }
                    cancel.check()
                    if (validState) throw BootstrapError("login.response", "Samsung 로그인 응답에서 토큰·계정 ID·이메일을 확인하지 못했습니다. 서버 응답 형식 변경 또는 추가 계정 인증이 필요할 수 있습니다.")
                } finally { requestHook.close() }
            }
        }
        throw BootstrapError("login.timeout", "삼성 로그인 시간이 만료되었습니다. 설치 앱에서 다시 시도하세요.")
    }
    private fun respond(output: java.io.OutputStream, code: Int, message: String) {
        val html = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>TV Bootstrap</title><p>$message</p>".toByteArray()
        output.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(html); output.flush()
    }
    override fun close() { hook.close(); runCatching { server.close() } }
}
