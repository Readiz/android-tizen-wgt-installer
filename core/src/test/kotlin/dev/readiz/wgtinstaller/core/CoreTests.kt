// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

import dev.readiz.wgtinstaller.simulator.DemoEnvironment
import dev.readiz.wgtinstaller.simulator.DemoFixtures
import dev.readiz.wgtinstaller.simulator.MemoryHistory
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyPair
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext

private var passed = 0
private var failed = 0
private fun test(name: String, block: () -> Unit) {
    try { block(); passed++; println("PASS  $name") }
    catch (e: Throwable) { failed++; System.err.println("FAIL  $name: ${e.javaClass.simpleName}: ${e.message}"); e.printStackTrace() }
}
private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
    try { block() } catch (e: Throwable) { check(e is T) { "Expected ${T::class.simpleName}, got ${e.javaClass.simpleName}" }; return e }
    error("Expected ${T::class.simpleName}")
}
private fun command(vararg args: String): String {
    val p = ProcessBuilder(*args).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "Test fixture command failed: ${args.first()}\n$text" }
    return text
}
private class CertificateFixture(val dir: File) {
    val caKey = File(dir, "test-ca.key")
    val caFile = File(dir, "test-ca.pem")
    init {
        dir.mkdirs()
        command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", caKey.path, "-out", caFile.path, "-subj", "/CN=TVBootstrap-Test-CA", "-days", "2", "-sha512")
    }
    private val sequence = AtomicInteger()
    fun issue(csr: String): String {
        val n = sequence.incrementAndGet(); val req = File(dir, "csr-$n.pem"); val cert = File(dir, "cert-$n.pem"); req.writeText(csr)
        command("openssl", "req", "-in", req.path, "-verify", "-noout")
        command("openssl", "x509", "-req", "-in", req.path, "-CA", caFile.path, "-CAkey", caKey.path, "-set_serial", n.toString(), "-out", cert.path, "-days", "1", "-sha512", "-copy_extensions", "copy")
        return cert.readText()
    }
    fun identity(csr: String, key: KeyPair) = SigningIdentity(listOf(issue(csr), caFile.readText()), Pem.encode("PRIVATE KEY", key.private.encoded))
    fun pair(): CertificatePair {
        val a = Csr.key(); val d = Csr.key()
        return CertificatePair(identity(Csr.author("Test Author", a), a), identity(Csr.distributor("test@example.invalid", listOf("TESTDUID0001"), d), d), "<profile><test>not a real Samsung profile</test></profile>", listOf("TESTDUID0001"), "Partner")
    }
}
private fun widget(): ByteArray = Archives.write(linkedMapOf(
    "config.xml" to """<?xml version="1.0" encoding="UTF-8"?><widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="0.2.2"><tizen:application id="GJBBYNLkgP.TizenHomebrew" package="GJBBYNLkgP"/><name>Test Homebrew</name><tizen:service id="GJBBYNLkgP.Test" on-boot="true" auto-restart="true"/></widget>""".toByteArray(),
    "ui/a + 한글.js" to "console.log('fixture only')".toByteArray(),
    "icon.png" to ByteArray(300) { it.toByte() }
))

private class ReleaseHttp(val archive: ByteArray = widget()) : HttpTransport {
    var badDigest = false
    override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
        check(method == "GET")
        if (url.endsWith("releases/latest")) return HttpReply(200, Json.stringify(mapOf("id" to 1L, "draft" to false, "prerelease" to false, "tag_name" to "v0.2.2", "assets" to listOf(mapOf("id" to 1L, "state" to "uploaded", "size" to archive.size.toLong(), "name" to "test.wgt", "browser_download_url" to "https://github.com/SushyDev/tizen-homebrew/releases/download/v0.2.2/test.wgt", "digest" to "sha256:${if (badDigest) "0".repeat(64) else Safety.sha256(archive)}")))).toByteArray())
        return HttpReply(200, archive)
    }
}

/** A local mock, not a Samsung TV. Exercise the real Socket/SDB client. */
private class FakeTv(var installOutput: String = "app_id[GJBBYNLkgP] install completed\n", val maxData: Int = 4096, val emptySyncReply: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val active = CopyOnWriteArrayList<Socket>()
    private val pool = Executors.newCachedThreadPool { task -> Thread(task, "mock-tv").apply { isDaemon = true } }
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
                                service == "shell:0 getduid" -> "TESTDUID0001\n"
                                service == "shell:0 applist" -> "'Other App' 'ABCDEFGHIJ.Other'\n" + (if (installed) "'Tizen Homebrew' 'GJBBYNLkgP.TizenHomebrew'\n" else "")
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

private class FakeLan(val tv: FakeTv, private val registryStatus: Int = 401) : LanAccess {
    override fun connect(ip: String, cancellation: Cancellation) = tv.connect(cancellation)
    override fun http(ip: String, cancellation: Cancellation): HttpTransport = object : HttpTransport {
        override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
            if (registryStatus == 401 || method == "POST") return HttpReply(401, byteArrayOf())
            return if (tv.installed) HttpReply(200, """{"id":"GJBBYNLkgP.TizenHomebrew","version":"0.2.2"}""".toByteArray()) else HttpReply(404, byteArrayOf())
        }
    }
}
private class MemoryVault(var pair: CertificatePair?) : PairVault {
    override fun load(duid: String) = pair
    override fun save(duid: String, pair: CertificatePair) { this.pair = pair }
}

private fun selection() = GitHubReleases(ReleaseHttp()).inspect("SushyDev/tizen-homebrew").choose()
private fun ownedHistory(pair: CertificatePair) = MemoryHistory().apply {
    put("TESTDUID0001", InstallReceipt("sushydev/tizen-homebrew", "GJBBYNLkgP", "GJBBYNLkgP.TizenHomebrew", "0.2.2", Safety.sha256(pair.author.leaf().encoded), Safety.sha256(widget())))
}

fun main(args: Array<String>) {
    val directory = File(args.firstOrNull() ?: "build/fixtures").absoluteFile; directory.mkdirs()
    val fixture = CertificateFixture(directory)
    val pair = fixture.pair()
    test("private IPv4 allowed; no DNS/network traversal") { check(Safety.privateIpv4("192.168.0.2") == "192.168.0.2"); check(Safety.privateIpv4("172.31.1.2") == "172.31.1.2") }
    test("public, loopback, padded and injected IPv4 rejected") { listOf("8.8.8.8", "127.0.0.1", "192.168.001.2", "192.168.1.2;rm", "localhost", "172.32.1.1").forEach { expect<IllegalArgumentException> { Safety.privateIpv4(it) } } }
    test("manifest identifiers cannot become shell injection") { expect<IllegalArgumentException> { Safety.packageId("abc; touch x") }; expect<IllegalArgumentException> { Safety.appId("GJBBYNLkgP.a;ls", "GJBBYNLkgP") } }
    test("JSON UTF-8/escapes/null/list round trip") { val m = mapOf("text" to "한글\n\"+\\", "items" to listOf(true, null, 1L)); check(Json.obj(Json.stringify(m)) == m) }
    test("duplicate JSON keys and trailing tokens rejected") { expect<IllegalArgumentException> { Json.obj("{\"x\":1,\"x\":2}") }; expect<IllegalArgumentException> { Json.parse("true false") } }
    test("JSON excessive nesting rejected") { expect<IllegalArgumentException> { Json.parse("[".repeat(50) + "0" + "]".repeat(50)) } }
    test("XML DTD and external entity rejected") { expect<IllegalArgumentException> { SafeXml.parse("<!DOCTYPE x SYSTEM 'file:///etc/passwd'><x/>") } }
    test("WGT manifest and version parsed") { val id = Wgt.identity(widget()); check(id.packageId == "GJBBYNLkgP" && id.version == "0.2.2") }
    test("path traversal and absolute ZIP entries rejected") { listOf("../a", "/x", "a/../b", "a\\b", "a//b").forEach { expect<IllegalArgumentException> { Safety.zipName(it) } } }
    test("ZIP inflated entry limit enforced") { val zip = Archives.write(mapOf("large" to ByteArray(5000))); expect<BootstrapError> { Archives.read(zip, 1000, 1000) } }
    test("WGT without root config rejected") { expect<IllegalStateException> { Wgt.identity(Archives.write(mapOf("sub/config.xml" to "x".toByteArray()))) } }
    test("RSA CSR verified independently by OpenSSL") { val key = Csr.key(); val file = File(directory, "standalone.csr"); file.writeText(Csr.author("테스트", key)); command("openssl", "req", "-in", file.path, "-verify", "-noout") }
    test("distributor CSR SAN contains exact TV DUID") { check(pair.distributor.leaf().subjectAlternativeNames.any { it[1] == "URN:tizen:deviceid=TESTDUID0001" }) }
    test("certificate chain, private key and DUID validated") { pair.validate("TESTDUID0001") }
    test("wrong device certificate refused") { expect<IllegalArgumentException> { pair.validate("OTHERDUID001") } }
    test("wrong private key refused") { val wrong = SigningIdentity(pair.author.certificates, Pem.encode("PRIVATE KEY", Csr.key().private.encoded)); expect<IllegalArgumentException> { wrong.validate() } }
    test("credential toString does not expose secrets") { check(!pair.toString().contains("BEGIN")); check(!SamsungAccount("supersecret", "u", "e").toString().contains("supersecret")) }
    test("certificate persistence round trip") { val restored = CertificatePair.decode(pair.encode()); restored.validate("TESTDUID0001"); check(restored.author.keyPem == pair.author.keyPem) }
    test("encodeURIComponent compatibility (slash/space/plus/unicode)") { check(XmlSignature.uri("a/b +한") == "a%2Fb%20%2B%ED%95%9C") }
    val signed = Wgt.sign(widget(), pair)
    test("old signatures replaced, both new signatures present") {
        val previous = Archives.read(widget()).apply { put("signature2.xml", "old".toByteArray()); put("author-signature.xml", "old".toByteArray()) }
        val names = Archives.read(Wgt.sign(Archives.write(previous), pair)).keys
        check("signature2.xml" !in names && "signature1.xml" in names && "author-signature.xml" in names)
    }
    test("JDK XMLDSig independently validates both signatures and all references") {
        val entries = Archives.read(signed)
        val factory = XMLSignatureFactory.getInstance("DOM")
        for ((name, key) in listOf("author-signature.xml" to pair.author.leaf().publicKey, "signature1.xml" to pair.distributor.leaf().publicKey)) {
            val doc = SafeXml.parse(entries.getValue(name).toString(Charsets.UTF_8)); val context = DOMValidateContext(key, doc.documentElement)
            context.setProperty("org.jcp.xml.dsig.secureValidation", false)
            val prop = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Object").item(0) as org.w3c.dom.Element
            context.setIdAttributeNS(prop, null, "Id")
            context.uriDereferencer = URIDereferencer { reference, ctx ->
                if (reference.uri.startsWith('#')) factory.uriDereferencer.dereference(reference, ctx)
                else OctetStreamData(ByteArrayInputStream(entries.getValue(URLDecoder.decode(reference.uri, "UTF-8"))))
            }
            val parsed = factory.unmarshalXMLSignature(context)
            check(parsed.validate(context)) { "Invalid $name; refs=" + parsed.signedInfo.references.map { (it as javax.xml.crypto.dsig.Reference).validate(context) } }
        }
    }
    test("generic Public signing preserves original manifest") {
        val pub = CertificatePair(pair.author, pair.distributor, pair.profile, pair.duids, "Public")
        val archive = Wgt.sign(widget(), pub); val config = Archives.read(archive).getValue("config.xml").toString(Charsets.UTF_8)
        check(config == Archives.read(widget()).getValue("config.xml").toString(Charsets.UTF_8))
    }
    test("release SHA-256 verified before package acceptance") { val release = GitHubReleases(ReleaseHttp()).download(selection()); check(release.asset.tag == "v0.2.2") }
    test("altered release digest rejected") { expect<IllegalArgumentException> { GitHubReleases(ReleaseHttp()).download(GitHubReleases(ReleaseHttp().apply { badDigest = true }).inspect("SushyDev/tizen-homebrew").choose()) } }
    test("CSRF nonce is 256-bit and fresh") { val a = LoginProtocol.state(); val b = LoginProtocol.state(); check(a != b && a.length == 43) }
    test("Samsung form preserves plus/equals within token") {
        val encoded = "code=" + URLEncoder.encode("""{"access_token":"ab+c=defgh","userId":"test","inputEmailID":"a@example.invalid"}""", "UTF-8") + "&state=nonce"
        check(LoginProtocol.account(LoginProtocol.fields(encoded, true), "nonce").accessToken == "ab+c=defgh")
    }
    test("wrong callback state and duplicate fields rejected") { expect<IllegalArgumentException> { LoginProtocol.account(mapOf("state" to "wrong"), "right") }; expect<IllegalArgumentException> { LoginProtocol.fields("state=a&state=b") } }
    test("loopback callback rejects bad request, then accepts valid browser POST") {
        val port = ServerSocket(0).use { it.localPort }
        SamsungLogin(Cancellation(), port).use { login ->
            val nonce = LoginProtocol.fields(URI(login.url()).rawQuery).getValue("state")
            val executor = Executors.newSingleThreadExecutor(); val result = executor.submit<SamsungAccount> { login.await(5000) }
            fun post(state: String): Int {
                val payload = "state=$state&code=" + URLEncoder.encode("""{"access_token":"fixture+token==","userId":"test-user","inputEmailID":"test@example.invalid"}""", "UTF-8")
                val url = URI("http://localhost:$port/signin/callback").toURL().openConnection() as HttpURLConnection
                url.requestMethod = "POST"; url.doOutput = true; url.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); url.connectTimeout = 2000; url.readTimeout = 2000
                url.outputStream.use { it.write(payload.toByteArray()) }; val status = url.responseCode; url.disconnect(); return status
            }
            try { check(post("wrong") == 400); check(post(nonce) == 200); check(result.get(5, TimeUnit.SECONDS).accessToken == "fixture+token==") }
            finally { executor.shutdownNow() }
        }
    }
    test("wire header fixed known magic/checksum") {
        val encoded = SdbWire.encode(SdbWire.Packet("CNXN", 0x01000000, 4096, "host::\u0000".toByteArray()))
        check(encoded.take(4).toByteArray().toString(Charsets.US_ASCII) == "CNXN")
        check(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(16) == 562)
        check(encoded.size == 31)
    }
    test("wire decoder accepts single-byte TCP reads") {
        val raw = SdbWire.encode(SdbWire.Packet("WRTE", 3, 4, byteArrayOf(-1, 2)))
        val input = object : ByteArrayInputStream(raw) { override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len)) }
        check(SdbWire.read(input).payload.contentEquals(byteArrayOf(-1, 2)))
    }
    test("wire corruption and oversize lengths rejected") {
        val good = SdbWire.encode(SdbWire.Packet("WRTE", 3, 4, byteArrayOf(1)))
        val magic = good.clone().apply { this[20] = (this[20].toInt() xor 1).toByte() }; expect<BootstrapError> { SdbWire.read(ByteArrayInputStream(magic)) }
        val checksum = good.clone().apply { this[24] = 2 }; expect<BootstrapError> { SdbWire.read(ByteArrayInputStream(checksum)) }
        val length = good.clone().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 4097) }; expect<BootstrapError> { SdbWire.read(ByteArrayInputStream(length)) }
    }
    test("real TCP mock: handshake, DUID, sequential Unicode command") { FakeTv().use { tv -> tv.connect().use { check(it.duid() == "TESTDUID0001"); check(it.exec("shell:0 unicode").contains("한글")) }; check(tv.errors.isEmpty()) } }
    test("real TCP mock: negotiated max payload and ACK-before-next-write") {
        FakeTv(maxData = 1024).use { tv -> val data = ByteArray(24001) { (it * 7).toByte() }; tv.connect().use { it.push(REMOTE_WGT, data) }; check(tv.files.getValue(REMOTE_WGT).contentEquals(data)); check(tv.errors.isEmpty()) }
    }
    test("private-key handoff and arbitrary SDB destinations forbidden") { FakeTv().use { tv -> tv.connect().use { session ->
        expect<IllegalArgumentException> { session.push("$STAGING_DIR/homebrewCerts.json", pair.encode()) }
        expect<IllegalArgumentException> { session.push("/etc/anything", byteArrayOf()) }
        check(tv.files.isEmpty())
    } } }
    test("cancellation closes live SDB socket") { FakeTv().use { tv -> val c = Cancellation(); val session = tv.connect(c); c.cancel(); expect<BootstrapError> { session.duid() }; session.close() } }
    test("HTTP 401 is unknown, never interpreted as missing app") { check(InstallEvidence.registry(401, "", "x").installed == null) }
    test("applist fallback requires exact app identifier") { check(InstallEvidence.appList("'X' 'GJBBYNLkgP.TizenHomebrewExtra'\n", "GJBBYNLkgP.TizenHomebrew").installed == false) }
    test("author mismatch and install error take priority over completion") { check(!InstallEvidence.completed("install completed\nAuthor certificate not match")) }
    test("progress 100 is not installation completion") { check(!InstallEvidence.completed("app installing[100]")) }
    test("already-present matching version is not proof after lost output") { check(!InstallEvidence.proven("", Presence(true, "1", true), Presence(true, "1", true), "1")) }
    test("new matching version can prove install after transport loss") { check(InstallEvidence.proven("", Presence(false, null, true), Presence(true, "1", true), "1")) }
    test("generic install: 401 fallback, signing, push, install; zero key handoff") {
        FakeTv().use { tv -> val updates = mutableListOf<Update>(); val history = MemoryHistory()
            RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), history, Cancellation(), updates::add).run("192.168.0.10", selection(), true)
            check(tv.installCalls.get() == 1 && tv.files.keys == setOf(REMOTE_WGT, REMOTE_PROFILE))
            check(Wgt.identity(tv.files.getValue(REMOTE_WGT)).version == "0.2.2")
            check(history.get("TESTDUID0001", "GJBBYNLkgP") != null)
            check(updates.last().phase == Phase.COMPLETE && tv.errors.isEmpty())
            check(tv.files.values.none { it.toString(Charsets.UTF_8).contains("PRIVATE KEY") })
        }
    }
    test("foreign-author app not overwritten even when phone has another pair") {
        FakeTv().use { tv -> tv.installed = true
            val error = expect<BootstrapError> { RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), MemoryHistory(), Cancellation(), {}).run("192.168.0.10", selection(), true) }
            check(error.code == "author.unknown" && tv.installCalls.get() == 0 && tv.files.isEmpty())
        }
    }
    test("uncertain install never automatically replays install command") {
        FakeTv(installOutput = "").use { tv -> tv.installed = true
            val error = expect<BootstrapError> { RepoInstaller(FakeLan(tv, 200), ReleaseHttp(), MemoryVault(pair), ownedHistory(pair), Cancellation(), {}).run("192.168.0.10", selection(), true) }
            check(error.code == "install.unknown" && tv.installCalls.get() == 1 && tv.files.keys == setOf(REMOTE_WGT, REMOTE_PROFILE))
        }
    }
    test("install failure cannot be masked by registry entry") {
        FakeTv(installOutput = "install failed: Invalid signature\n").use { tv ->
            val error = expect<BootstrapError> { RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), MemoryHistory(), Cancellation(), {}).run("192.168.0.10", selection(), true) }
            check(error.code == "install.rejected" && tv.installCalls.get() == 1)
        }
    }
    test("source-trust consent mandatory before network operations") { FakeTv().use { tv ->
        expect<IllegalArgumentException> { RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), MemoryHistory(), Cancellation(), {}).run("192.168.0.10", selection(), false) }
        check(tv.files.isEmpty())
    } }
    // Model endpoint contracts independently: v1 returns XML, v3 always returns PEM.
    // The old order-based mock incorrectly made the first v3 call return XML.
    class IssuanceHttp(
        val profile: String = "<profile><test/></profile>",
        val profileStatus: Int = 200,
        val distributorResponse: String? = null
    ) : HttpTransport {
        val calls = mutableListOf<String>()
        val distributorCsrs = mutableListOf<String>()
        val ca = fixture.caFile.readBytes()
        val extension = Archives.write(mapOf("addon.zip" to Archives.write(mapOf("certificates.jar" to Archives.write(mapOf(
            "certs/vd_tizen_dev_author_ca.cer" to ca, "certs/vd_tizen_dev_partner2.crt" to ca, "certs/vd_tizen_dev_public2.crt" to ca))))))
        override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
            if (url.endsWith("extension_info.xml")) return HttpReply(200, "<extensions/>".toByteArray())
            if (url.endsWith(".zip")) return HttpReply(200, extension)
            check(method == "POST" && body != null && headers.getValue("Content-Type").startsWith("multipart/form-data"))
            val path = URI(url).path
            calls.add(path)
            val payload = body.toString(Charsets.UTF_8)
            val csr = Regex("-----BEGIN CERTIFICATE REQUEST-----[\\s\\S]*?-----END CERTIFICATE REQUEST-----").find(payload)!!.value
            if (path.endsWith("/distributors")) {
                distributorCsrs.add(csr)
                check(payload.contains("Partner") && payload.contains("Individual") && payload.contains("VD"))
            }
            return when (path) {
                "/apis/v3/authors" -> HttpReply(200, fixture.issue(csr).toByteArray())
                "/apis/v1/distributors" -> HttpReply(profileStatus, profile.toByteArray())
                "/apis/v3/distributors" -> HttpReply(200, (distributorResponse ?: fixture.issue(csr)).toByteArray())
                else -> error("Unexpected issuance endpoint")
            }
        }
        fun mint() = SamsungIssuer(this).mint(SamsungAccount("FAKE-TOKEN-NOT-REAL", "fixture", "test@example.invalid"), "TESTDUID0001")
    }
    test("Samsung issuance uses v1 profile then v3 certificate with identical distributor CSR") {
        val fake = IssuanceHttp()
        val minted = fake.mint()
        minted.validate("TESTDUID0001")
        check(minted.profile == fake.profile)
        check(fake.calls == listOf("/apis/v3/authors", "/apis/v1/distributors", "/apis/v3/distributors"))
        check(fake.distributorCsrs.size == 2 && fake.distributorCsrs.distinct().size == 1)
    }
    test("profile XML containing PEM text is preserved and not mistaken for distributor certificate") {
        val fake = IssuanceHttp("<profile><Certificate>" + pair.author.certificates.first() + "</Certificate></profile>")
        check(fake.mint().profile == fake.profile)
    }
    test("PEM returned by profile endpoint is refused without retry or exposing body") {
        val fake = IssuanceHttp("-----BEGIN CERTIFICATE-----\nPRIVATE-RESPONSE-SENTINEL\n-----END CERTIFICATE-----")
        val error = expect<BootstrapError> { fake.mint() }
        check(error.code == "cert.profile" && !error.message.orEmpty().contains("PRIVATE-RESPONSE-SENTINEL") && error.cause == null)
        check(fake.calls == listOf("/apis/v3/authors", "/apis/v1/distributors"))
    }
    test("malformed profile response is a redacted stage-specific error") {
        val fake = IssuanceHttp("<profile><PRIVATE-RESPONSE-SENTINEL></profile>")
        val error = expect<BootstrapError> { fake.mint() }
        check(error.code == "cert.profile" && !error.message.orEmpty().contains("PRIVATE-RESPONSE-SENTINEL") && error.cause == null)
        check(fake.calls.size == 2)
    }
    test("DTD in profile is refused before certificate issuance") {
        val fake = IssuanceHttp("<!DOCTYPE profile SYSTEM 'file:///not-a-real-file'><profile/>")
        check(expect<BootstrapError> { fake.mint() }.code == "cert.profile")
        check(fake.calls.size == 2)
    }
    test("XML returned by v3 cannot be used as a distributor certificate") {
        val fake = IssuanceHttp(distributorResponse = "<profile>PRIVATE-RESPONSE-SENTINEL</profile>")
        val error = expect<BootstrapError> { fake.mint() }
        check(error.code == "cert.response" && !error.message.orEmpty().contains("PRIVATE-RESPONSE-SENTINEL"))
        check(fake.calls.size == 3)
    }
    test("profile HTTP refusal aborts without retry or requesting distributor certificate") {
        val fake = IssuanceHttp(profileStatus = 403)
        check(expect<BootstrapError> { fake.mint() }.code == "http.403")
        check(fake.calls == listOf("/apis/v3/authors", "/apis/v1/distributors"))
    }
    test("cloud HTTP refuses plaintext and unapproved redirect destinations") {
        val http = SafeHttp(Cancellation())
        expect<IllegalArgumentException> { http.request("http://svdca.samsungqbe.com/apis/v3/authors") }
        expect<IllegalArgumentException> { http.request("https://untrusted.example.invalid/package.wgt") }
    }
    test("LAN HTTP cannot reach another TV, public host or arbitrary port") {
        val http = SafeHttp(Cancellation(), "192.168.0.10")
        listOf("http://192.168.0.11:8091/", "http://8.8.8.8:8001/", "http://192.168.0.10:22/").forEach { address ->
            expect<IllegalArgumentException> { http.request(address) }
        }
    }
    test("silent shell command obeys overall deadline") {
        FakeTv().use { tv -> tv.connect().use { session ->
            val began = System.nanoTime()
            val error = expect<ShellInterrupted> { session.exec("shell:0 wait", 100) }
            check(error.cause is SocketTimeoutException && (System.nanoTime() - began) < 2_000_000_000L)
        } }
    }
    test("missing sync completion cannot be counted as transferred") {
        FakeTv(emptySyncReply = true).use { tv -> tv.connect().use { session ->
            expect<BootstrapError> { session.push(REMOTE_WGT, byteArrayOf(1, 2, 3)) }
        } }
    }
    test("truncated packet payload is rejected rather than partially accepted") {
        val frame = SdbWire.encode(SdbWire.Packet("WRTE", 1, 2, ByteArray(20)))
        expect<EOFException> { SdbWire.read(ByteArrayInputStream(frame.copyOf(frame.size - 1))) }
    }
    additionalTests(pair)
    println("RESULT $passed passed, $failed failed")
    check(failed == 0) { "$failed tests failed" }
}

private fun additionalTests(pair: CertificatePair) {
    test("ZIP serialization uses deterministic timestamps for reproducible digests") {
        val a = Archives.write(mapOf("sample.txt" to "sample".toByteArray()))
        val b = Archives.write(mapOf("sample.txt" to "sample".toByteArray()))
        check(a.contentEquals(b))
        java.util.zip.ZipInputStream(ByteArrayInputStream(a)).use { check(it.nextEntry.time == 0L) }
    }
    test("non-widget XML root rejected before signing") {
        val bad = widget(); val entries = Archives.read(bad)
        entries["config.xml"] = entries.getValue("config.xml").toString(Charsets.UTF_8).replace("<widget ", "<notwidget ").replace("</widget>", "</notwidget>").toByteArray()
        expect<IllegalArgumentException> { Wgt.identity(Archives.write(entries)) }
    }
    test("source ZIP and TPK assets never silently treated as WGT") {
        val original = ReleaseHttp()
        val cloud = object : HttpTransport { override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
            val reply = original.request(url, method, body, headers, limit)
            return HttpReply(reply.status, reply.body.toString(Charsets.UTF_8).replace("test.wgt", "test.zip").toByteArray())
        } }
        check(expect<BootstrapError> { GitHubReleases(cloud).inspect("SushyDev/tizen-homebrew") }.code == "wgt.missing")
    }
    test("draft and prerelease responses blocked") {
        val original = ReleaseHttp()
        for (field in listOf("draft", "prerelease")) {
            val cloud = object : HttpTransport { override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
                val reply = original.request(url, method, body, headers, limit)
                val m = Json.obj(reply.body.toString(Charsets.UTF_8)).toMutableMap(); m[field] = true
                return HttpReply(200, Json.stringify(m).toByteArray())
            } }
            expect<IllegalArgumentException> { GitHubReleases(cloud).inspect("SushyDev/tizen-homebrew") }
        }
    }
    test("oversized assets blocked before downloading") { expect<IllegalArgumentException> { selection().copy(size = GitHubReleases.MAX_WGT.toLong() + 1) } }
    test("history persistence failure never reported as full success") {
        FakeTv().use { tv ->
            val history = object : InstallHistory { override fun get(duid: String, packageId: String): InstallReceipt? = null; override fun put(duid: String, receipt: InstallReceipt) { throw IOException("fixture disk error") } }
            val events = mutableListOf<Update>()
            check(expect<BootstrapError> { RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), history, Cancellation(), events::add).run("192.168.0.10", selection(), true) }.code == "history.failed")
            check(events.none { it.phase == Phase.COMPLETE } && tv.installCalls.get() == 1)
        }
    }
    test("local WGT installs and updates without GitHub, preserving Author") {
        val bytes = widget(); val local = LocalWidget.inspect("app.wgt", bytes)
        check(LocalWidget.decode(local.encode()) == local)
        val offline = object : HttpTransport {
            override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply = error("Unexpected cloud request")
        }
        FakeTv().use { tv ->
            val vault = MemoryVault(pair); val history = MemoryHistory()
            val installer = RepoInstaller(FakeLan(tv), offline, vault, history, Cancellation(), {})
            val first = installer.runLocal("192.168.0.10", local, bytes, true)
            val second = installer.runLocal("192.168.0.10", local, bytes, true)
            check(first.repository == "local-wgt:GJBBYNLkgP" && first.authorSha256 == second.authorSha256)
            check(tv.installCalls.get() == 2 && tv.files.keys == setOf(REMOTE_WGT, REMOTE_PROFILE))
        }
    }
    test("local WGT tampering, no consent and source changes fail before TV writes") {
        val bytes = widget(); val local = LocalWidget.inspect("app.wgt", bytes)
        FakeTv().use { tv ->
            val installer = RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), ownedHistory(pair), Cancellation(), {})
            expect<IllegalArgumentException> { installer.runLocal("192.168.0.10", local, bytes + 0.toByte(), true) }
            expect<IllegalArgumentException> { installer.runLocal("192.168.0.10", local, bytes, false) }
            check(expect<BootstrapError> { installer.runLocal("192.168.0.10", local, bytes, true) }.code == "package.owner")
            check(tv.files.isEmpty() && tv.installCalls.get() == 0)
        }
    }
    test("local WGT rejects invalid names, archives and oversized metadata") {
        for (name in listOf("app.zip", "../app.wgt", "a/b.wgt")) expect<IllegalArgumentException> { LocalWidget.inspect(name, widget()) }
        expect<Exception> { LocalWidget.inspect("bad.wgt", "not a widget".toByteArray()) }
        expect<IllegalArgumentException> { LocalWidget("large.wgt", GitHubReleases.MAX_WGT + 1, "a".repeat(64)) }
        expect<IllegalArgumentException> { LocalWidget.decode("""{"name":"large.wgt","size":4294967297,"sha256":"${"a".repeat(64)}"}""") }
    }
    test("repo shorthand and GitHub URL/.git normalized") {
        check(RepoRef.parse("  Readiz/readiz-tv ").key == "readiz/readiz-tv")
        check(RepoRef.parse("https://github.com/Readiz/readiz-tv.git/").key == "readiz/readiz-tv")
    }
    test("repo rejects credentials, foreign domains, API paths and injection") {
        listOf("http://github.com/a/b", "https://github.com.evil.test/a/b", "https://u@github.com/a/b", "https://github.com/a/b?x=1", "https://github.com/a/b#x", "https://github.com/a/b/tree/main", "a/../b", "a/%2E%2E", "a/b;sh", "https://github.com:443/a/b").forEach { expect<Exception> { RepoRef.parse(it) } }
    }
    test("snapshot serialization pins selected asset ID/release ID/hash") { check(ReleaseAsset.decode(selection().encode()) == selection()) }
    test("multiple WGTs require explicit choice rather than first-file guess") {
        val one = selection(); val two = one.copy(assetId = 2, name = "tizen-5.0.wgt")
        val catalog = ReleaseCatalog(one.repo, one.releaseId, one.tag, listOf(one, two))
        check(expect<BootstrapError> { catalog.choose() }.code == "asset.choice")
        check(catalog.choose(2).name == "tizen-5.0.wgt")
        expect<BootstrapError> { catalog.choose(999) }
    }
    test("asset cannot switch repository or non-HTTPS host") {
        val one = selection()
        expect<IllegalArgumentException> { one.copy(url = "https://github.com/evil/other/releases/download/v1/a.wgt") }
        expect<IllegalArgumentException> { one.copy(url = "http://github.com/SushyDev/tizen-homebrew/releases/download/v1/a.wgt") }
    }
    test("missing digest is visible metadata but blocks automatic installation") { check(expect<BootstrapError> { GitHubReleases(ReleaseHttp()).download(selection().copy(sha256 = null)) }.code == "release.digest") }
    test("download size mismatch blocks signing") { expect<IllegalArgumentException> { GitHubReleases(ReleaseHttp()).download(selection().copy(size = selection().size + 1)) } }
    test("selected release is pinned; latest never queried again during install") {
        var latest = 0; val origin = ReleaseHttp()
        val transport = object : HttpTransport {
            override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
                if (url.endsWith("/latest")) { latest++; check(latest == 1) }
                return origin.request(url, method, body, headers, limit)
            }
        }
        val pinned = GitHubReleases(transport).inspect("SushyDev/tizen-homebrew").choose()
        FakeTv().use { tv -> RepoInstaller(FakeLan(tv), transport, MemoryVault(pair), MemoryHistory(), Cancellation(), {}).run("192.168.0.10", pinned, true) }
        check(latest == 1)
    }
    test("404 is ambiguous; 403/429 stop repeated requests") {
        for (status in listOf(404, 403, 429)) {
            val transport = object : HttpTransport { override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int) = HttpReply(status, byteArrayOf()) }
            check(expect<BootstrapError> { GitHubReleases(transport).inspect("a/b") }.code == if (status == 404) "release.missing" else "github.limit")
        }
    }
    test("same package ID from another repository refuses overwrite") {
        FakeTv().use { tv -> tv.installed = true
            val another = selection().copy(repo = RepoRef("Other", "project"), url = "https://github.com/Other/project/releases/download/v0.2.2/test.wgt")
            check(expect<BootstrapError> { RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(pair), ownedHistory(pair), Cancellation(), {}).run("192.168.0.10", another, true) }.code == "package.owner")
            check(tv.installCalls.get() == 0)
        }
    }
    test("lost local Author key never silently replaced") {
        FakeTv().use { tv -> tv.installed = true
            check(expect<BootstrapError> { RepoInstaller(FakeLan(tv), ReleaseHttp(), MemoryVault(null), ownedHistory(pair), Cancellation(), {}).run("192.168.0.10", selection(), true) }.code == "author.lost")
            check(tv.files.isEmpty())
        }
    }
    test("same app update reuses original Author key") {
        FakeTv().use { tv -> tv.installed = true; val vault = MemoryVault(pair); val original = vault.pair!!.author.keyPem
            RepoInstaller(FakeLan(tv), ReleaseHttp(), vault, ownedHistory(pair), Cancellation(), {}).run("192.168.0.10", selection(), true)
            check(vault.pair!!.author.keyPem == original && tv.installCalls.get() == 1)
        }
    }
    test("device-free demo uses actual signing/SDB pipeline on loopback") {
        DemoEnvironment().use { demo ->
            val events = mutableListOf<Update>(); val receipt = demo.run(Cancellation(), events::add)
            check(receipt.packageId == "DemoWgt001" && events.last().phase == Phase.COMPLETE)
            check(demo.tv.installCalls.get() == 1 && demo.tv.files.keys == setOf(REMOTE_WGT, REMOTE_PROFILE))
            check(demo.tv.errors.isEmpty())
            expect<IllegalArgumentException> { demo.lan.connect("192.168.1.55", Cancellation()) }
        }
    }
    test("demo certificate is synthetic self-signed with explicit demo DUID") {
        val p = DemoFixtures.pair(); p.validate(DemoFixtures.DUID)
        check(p.author.leaf().subjectX500Principal.name.contains("DEMO"))
        p.author.leaf().verify(p.author.leaf().publicKey)
    }
}
