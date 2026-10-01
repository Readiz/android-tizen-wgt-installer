// SPDX-License-Identifier: GPL-3.0-only
// XML signature layout adapted from SushyDev/tizen-homebrew service/src/install/signature.js.
package dev.readiz.wgtinstaller.core

import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

object SafeXml {
    fun parse(text: String): Document {
        require(text.length <= 4 * 1024 * 1024 && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "DTD와 외부 엔티티를 허용하지 않습니다." }
        val f = DocumentBuilderFactory.newInstance(); f.isNamespaceAware = true
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) runCatching { f.setFeature(feature, false) }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val builder = f.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entity denied") }
        return builder.parse(InputSource(StringReader(text)))
    }
}

data class WidgetIdentity(val packageId: String, val appId: String, val name: String, val version: String)

object Archives {
    fun read(bytes: ByteArray, maxTotal: Int = 128 * 1024 * 1024, maxEntry: Int = 32 * 1024 * 1024): LinkedHashMap<String, ByteArray> {
        require(bytes.size <= 128 * 1024 * 1024)
        val out = linkedMapOf<String, ByteArray>(); var total = 0; var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 10000) { "ZIP entry limit" }
                if (entry.isDirectory) { Safety.zipName(entry.name.trimEnd('/')); continue }
                val name = Safety.zipName(entry.name); require(!out.containsKey(name)) { "Duplicate ZIP entry" }
                val data = zip.readBounded(minOf(maxEntry, maxTotal - total)); total += data.size; out[name] = data
            }
        }
        require(out.isNotEmpty()) { "읽을 수 있는 ZIP 파일이 아닙니다." }
        return out
    }
    fun write(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(Safety.zipName(name)).apply { time = 0L }); zip.write(data); zip.closeEntry() } }
        return out.toByteArray()
    }
}

object Wgt {
    private const val NS = "http://tizen.org/ns/widgets"
    private val signatureName = Regex("(?i)(author-signature\\.xml|signature[0-9]*\\.xml)")
    fun identity(bytes: ByteArray): WidgetIdentity = identityOf(Archives.read(bytes))
    private fun identityOf(entries: Map<String, ByteArray>): WidgetIdentity {
        val config = entries["config.xml"]?.toString(Charsets.UTF_8) ?: error("WGT 루트에 config.xml이 없습니다.")
        val xml = SafeXml.parse(config)
        require(xml.documentElement.localName == "widget" && xml.documentElement.namespaceURI == "http://www.w3.org/ns/widgets") { "올바른 W3C widget manifest가 아닙니다." }
        val nodes = xml.getElementsByTagNameNS(NS, "application"); require(nodes.length == 1) { "Expected one tizen:application" }
        val a = nodes.item(0) as org.w3c.dom.Element
        val pkg = Safety.packageId(a.getAttribute("package")); val id = Safety.appId(a.getAttribute("id"), pkg)
        val version = xml.documentElement.getAttribute("version")
        require(version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}"))) { "유효하지 않은 버전입니다." }
        return WidgetIdentity(pkg, id, xml.getElementsByTagNameNS("*", "name").item(0)?.textContent ?: id, version)
    }
    fun sign(archive: ByteArray, pair: CertificatePair): ByteArray {
        val entries = Archives.read(archive).filterKeys { !signatureName.matches(it) }.toMutableMap()
        identityOf(entries)
        // Generic packages are never silently patched, including service lifecycle/privileges.
        val author = XmlSignature.create("AuthorSignature", entries, pair.author)
        val withAuthor = linkedMapOf("author-signature.xml" to author).apply { putAll(entries) }
        val distributor = XmlSignature.create("DistributorSignature", withAuthor, pair.distributor)
        return Archives.write(linkedMapOf("signature1.xml" to distributor).apply { putAll(withAuthor) })
    }
}

object XmlSignature {
    private const val AUTHOR_PROP = "aXbSAVgmAz0GsBUeZ1UmNDRrxkWhDUVGb45dZcNRq429wX3X+x6kaXT3NdNDTSNVTU+ypkysPMGvQY10fG1EWQ=="
    private const val DIST_PROP = "/r5npk2VVA46QFJnejgONBEh4BWtjrtu9x/IFeLksjWyGmB/cMWKSJWQl7aU3YRQRZ3AesG8gF7qGyvKX9Snig=="
    private const val NS = "http://www.w3.org/2000/09/xmldsig#"
    fun uri(text: String): String = buildString {
        text.toByteArray(Charsets.UTF_8).forEach { b -> val c = (b.toInt() and 255).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.!~*'()") append(c) else append("%%%02X".format(b.toInt() and 255))
        }
    }
    private fun wrap(s: String) = s.replace(Regex("(.{76})"), "$1\n")
    private fun reference(uri: String, digest: String): String = "<Reference URI=\"$uri\">\n" +
        (if (uri == "#prop") "<Transforms>\n<Transform Algorithm=\"http://www.w3.org/2006/12/xml-c14n11\"></Transform>\n</Transforms>\n" else "") +
        "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n<DigestValue>${wrap(digest)}</DigestValue>\n</Reference>\n"
    fun canonical(signedInfo: String) = signedInfo.replace("<SignedInfo>", "<SignedInfo xmlns=\"$NS\">").removeSuffix("\n")
    fun create(id: String, files: Map<String, ByteArray>, identity: SigningIdentity): ByteArray {
        require(id == "AuthorSignature" || id == "DistributorSignature")
        val references = files.entries.joinToString("") { (name, data) -> reference(uri(name), Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(data))) } + reference("#prop", if (id == "AuthorSignature") AUTHOR_PROP else DIST_PROP)
        val info = "<SignedInfo>\n<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>\n" +
            "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>\n" + references + "</SignedInfo>\n"
        val signature = Signature.getInstance("SHA512withRSA").apply { initSign(identity.key()); update(canonical(info).toByteArray()) }.sign()
        val keyInfo = "<KeyInfo>\n<X509Data>" + identity.certificates.joinToString("") { cert ->
            "\n<X509Certificate>\n" + wrap(Base64.getEncoder().encodeToString(Pem.certificate(cert).encoded)) + "\n</X509Certificate>"
        } + "\n</X509Data>\n</KeyInfo>\n"
        val role = if (id == "AuthorSignature") "author" else "distributor"
        val properties = "<Object Id=\"prop\"><SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
            "<SignatureProperty Id=\"profile\" Target=\"#$id\"><dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile></SignatureProperty>" +
            "<SignatureProperty Id=\"role\" Target=\"#$id\"><dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$role\"></dsp:Role></SignatureProperty>" +
            "<SignatureProperty Id=\"identifier\" Target=\"#$id\"><dsp:Identifier></dsp:Identifier></SignatureProperty></SignatureProperties></Object>\n"
        return ("<Signature xmlns=\"$NS\" Id=\"$id\">\n" + info + "<SignatureValue>\n${wrap(Base64.getEncoder().encodeToString(signature))}\n</SignatureValue>\n" + keyInfo + properties + "</Signature>\n").toByteArray()
    }
}
