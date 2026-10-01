// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

import dev.readiz.wgtinstaller.simulator.DemoFixtures
import dev.readiz.wgtinstaller.simulator.MemoryHistory
import dev.readiz.wgtinstaller.simulator.MemoryVault
import dev.readiz.wgtinstaller.simulator.MockTv
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext

/** Downloads real WGT bytes but NEVER executes their code or contacts a physical TV/Samsung. */
fun main(args: Array<String>) {
    require(args.size == 1)
    val cancel = Cancellation()
    val releases = GitHubReleases(SafeHttp(cancel))
    val catalog = releases.inspect("SushyDev/tizen-youtube")
    check(catalog.assets.size > 1) { "Review changed upstream release layout before proceeding" }
    check(runCatching { catalog.choose() }.isFailure) { "Multiple WGTs must require explicit selection" }
    val results = catalog.assets.map { asset ->
        val widget = releases.download(asset)
        val original = Archives.read(widget.archive)
        val config = SafeXml.parse(original.getValue("config.xml").toString(Charsets.UTF_8))
        val application = config.getElementsByTagNameNS("http://tizen.org/ns/widgets", "application").item(0) as org.w3c.dom.Element
        val requiredVersion = application.getAttribute("required_version")
        val pair = DemoFixtures.pair()
        val vault = MemoryVault()
        val history = MemoryHistory()
        var issued = 0
        // Immutable cache of the bytes just verified against GitHub's digest. No repeat download.
        val cloud = object : HttpTransport {
            override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int): HttpReply {
                check(url == asset.url && method == "GET" && body == null && widget.archive.size <= limit)
                return HttpReply(200, widget.archive)
            }
        }
        MockTv(installOutput = "app_id[${widget.identity.packageId}] install completed\n", identity = widget.identity).use { tv ->
            val lan = object : LanAccess {
                override fun connect(ip: String, cancellation: Cancellation): SdbSession {
                    check(ip == DemoFixtures.IP)
                    return tv.connect(cancellation)
                }
                override fun http(ip: String, cancellation: Cancellation): HttpTransport {
                    check(ip == DemoFixtures.IP)
                    return object : HttpTransport {
                        override fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, limit: Int) = HttpReply(401, byteArrayOf())
                    }
                }
            }
            val provider = CertificateProvider { duid, _ ->
                check(duid == DemoFixtures.DUID); issued++; pair
            }
            val installer = RepoInstaller(lan, cloud, vault, history, cancel, {}, provider)
            val first = installer.run(DemoFixtures.IP, asset, true)
            val second = installer.run(DemoFixtures.IP, asset, true)
            check(first == second && issued == 1 && tv.installCalls.get() == 2)
            check(tv.files.keys == setOf(REMOTE_WGT, REMOTE_PROFILE))
            val signed = Archives.read(tv.files.getValue(REMOTE_WGT))
            val signatureName = Regex("(?i)(author-signature\\.xml|signature[0-9]*\\.xml)")
            val payload = original.filterKeys { !signatureName.matches(it) }
            check(signed.keys == payload.keys + setOf("author-signature.xml", "signature1.xml"))
            payload.forEach { (name, bytes) -> check(bytes.contentEquals(signed.getValue(name))) { "Changed original file: $name" } }
            val factory = XMLSignatureFactory.getInstance("DOM")
            for ((name, identity) in listOf("author-signature.xml" to pair.author, "signature1.xml" to pair.distributor)) {
                val doc = SafeXml.parse(signed.getValue(name).toString(Charsets.UTF_8))
                val context = DOMValidateContext(identity.leaf().publicKey, doc.documentElement)
                context.setProperty("org.jcp.xml.dsig.secureValidation", false)
                val prop = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Object").item(0) as org.w3c.dom.Element
                context.setIdAttributeNS(prop, null, "Id")
                context.uriDereferencer = URIDereferencer { reference, ctx ->
                    if (reference.uri.startsWith('#')) factory.uriDereferencer.dereference(reference, ctx)
                    else OctetStreamData(ByteArrayInputStream(signed.getValue(URLDecoder.decode(reference.uri, "UTF-8"))))
                }
                check(factory.unmarshalXMLSignature(context).validate(context)) { "Invalid $name" }
            }
            check(tv.errors.isEmpty())
            println("PASS ${asset.name}: live SHA-256, ${payload.size} unchanged files, XMLDSig, loopback first install + same-version reinstall, author reuse")
            mapOf("asset" to asset.name, "assetId" to asset.assetId, "sha256" to widget.sha256,
                "bytes" to widget.archive.size, "packageId" to widget.identity.packageId,
                "version" to widget.identity.version, "requiredTizen" to requiredVersion,
                "preservedFiles" to payload.size, "mockInstallCalls" to tv.installCalls.get())
        }
    }
    val report = mapOf("repository" to catalog.repo.fullName, "releaseId" to catalog.id, "tag" to catalog.tag,
        "liveGithub" to true, "syntheticCertificates" to true, "realSamsungAuth" to false, "realTv" to false, "results" to results)
    File(args[0]).apply { parentFile.mkdirs(); writeText(Json.stringify(report) + "\n") }
    println("PASS release smoke; Samsung issuance, Android runtime and physical TV compatibility remain untested")
}
