// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.simulator

import dev.readiz.wgtinstaller.core.*
import java.security.Signature
import java.util.concurrent.ConcurrentHashMap

/** Synthetic fixtures. No embedded or real Samsung signing credentials. */
object DemoFixtures {
    const val IP = "192.168.0.10"
    const val REPO = "demo/sample"
    const val DUID = "DEMODUID0001"
    fun widget(): ByteArray = Archives.write(linkedMapOf(
        "config.xml" to """<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.0.0"><tizen:application id="DemoWgt001.Main" package="DemoWgt001" required_version="3.0"/><name>SIMULATION ONLY</name><content src="index.html"/></widget>""".toByteArray(),
        "index.html" to "<h1>Simulation fixture, not a real release</h1>".toByteArray()))
    /** Self-signed, fake CA material generated in memory. Samsung TVs will not trust it. */
    fun pair(): CertificatePair {
        fun identity(name: String, distributor: Boolean): SigningIdentity {
            val key = Csr.key()
            val algo = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.tag(5, byteArrayOf()))
            val subject = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(name))))
            val validity = Der.seq(Der.tag(0x18, "20200101000000Z".toByteArray()), Der.tag(0x18, "20400101000000Z".toByteArray()))
            val san = if (distributor) Der.tag(0xa3, Der.seq(Der.seq(Der.oid("2.5.29.17"), Der.tag(4,
                Der.seq(Der.tag(0x86, "URN:tizen:deviceid=$DUID".toByteArray())))))) else byteArrayOf()
            val tbs = Der.seq(Der.tag(0xa0, Der.integer(2)), Der.integer(if (distributor) 2 else 1), algo, subject, validity, subject, key.public.encoded, san)
            val signed = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(tbs); sign() }
            val cert = Der.seq(tbs, algo, Der.tag(3, byteArrayOf(0) + signed))
            return SigningIdentity(listOf(Pem.encode("CERTIFICATE", cert)), Pem.encode("PRIVATE KEY", key.private.encoded))
        }
        return CertificatePair(identity("DEMO AUTHOR - NOT SAMSUNG", false), identity("DEMO DISTRIBUTOR - NOT SAMSUNG", true), "<profile><simulation>true</simulation></profile>", listOf(DUID), "Partner").also { it.validate(DUID) }
    }
}
class MemoryHistory : InstallHistory {
    private val records = ConcurrentHashMap<String, InstallReceipt>()
    override fun get(duid: String, packageId: String) = records["$duid/$packageId"]
    override fun put(duid: String, receipt: InstallReceipt) { records["$duid/${receipt.packageId}"] = receipt }
}
class MemoryVault : PairVault {
    private val values = ConcurrentHashMap<String, CertificatePair>()
    override fun load(duid: String) = values[duid]
    override fun save(duid: String, pair: CertificatePair) { values[duid] = pair }
}
class DemoCloud : HttpTransport {
    private val bytes = DemoFixtures.widget()
    val asset = ReleaseAsset(RepoRef.parse(DemoFixtures.REPO), 1, "v1.0.0", 1, "sample.wgt", bytes.size.toLong(), "https://github.com/demo/sample/releases/download/v1.0.0/sample.wgt", Safety.sha256(bytes))
    override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
        require(method == "GET") { "Demo never talks to Samsung." }
        return when (url) {
            "https://api.github.com/repos/demo/sample/releases/latest" -> HttpReply(200, Json.stringify(mapOf("id" to 1L, "tag_name" to "v1.0.0", "draft" to false, "prerelease" to false,
                "assets" to listOf(mapOf("id" to 1L, "state" to "uploaded", "name" to asset.name, "size" to asset.size, "browser_download_url" to asset.url, "digest" to "sha256:${asset.sha256}")))).toByteArray())
            asset.url -> HttpReply(200, bytes)
            else -> throw BootstrapError("demo.offline", "데모는 외부 주소에 접속하지 않습니다.")
        }
    }
}
class DemoEnvironment : AutoCloseable {
    val cloud = DemoCloud()
    val tv = MockTv()
    val history = MemoryHistory()
    val vault = MemoryVault()
    val lan = object : LanAccess {
        override fun connect(ip: String, cancellation: Cancellation): SdbSession {
            require(ip == DemoFixtures.IP); return tv.connect(cancellation)
        }
        override fun http(ip: String, cancellation: Cancellation): HttpTransport {
            require(ip == DemoFixtures.IP)
            return object : HttpTransport {
                override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int) = HttpReply(401, byteArrayOf())
            }
        }
    }
    fun run(cancel: Cancellation, notify: (Update) -> Unit): InstallReceipt {
        val provider = CertificateProvider { duid, update ->
            require(duid == DemoFixtures.DUID)
            update(Update(Phase.CERTIFICATES, "[데모] 합성 인증서 생성 · 삼성 로그인 생략"))
            DemoFixtures.pair()
        }
        return RepoInstaller(lan, cloud, vault, history, cancel, { u -> notify(u.copy(message = "[DEMO · 실제 TV 아님] ${u.message}")) }, provider)
            .run(DemoFixtures.IP, GitHubReleases(cloud).inspect(DemoFixtures.REPO).choose(), true)
    }
    override fun close() = tv.close()
}
