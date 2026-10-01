// SPDX-License-Identifier: GPL-3.0-only
// Endpoint, CSR and certificate-chain conventions: reisxd/tizen.js (see docs/SOURCES.md).
package dev.readiz.wgtinstaller.core

import java.security.KeyPair

class SamsungIssuer(private val http: HttpTransport, private val checkpoint: () -> Unit = {}) {
    fun mint(account: SamsungAccount, duid: String, level: String = "Partner"): CertificatePair {
        require(level in setOf("Partner", "Public")); Safety.duid(duid); checkpoint()
        val roots = downloadChains()
        val authorKey = Csr.key(); val distributorKey = Csr.key(); checkpoint()
        val authorCsr = Csr.author(account.email.substringBefore('@').take(128), authorKey)
        val distributorCsr = Csr.distributor(account.email, listOf(duid), distributorKey)
        fun request(distributor: Boolean, csr: String, version: Int = 3): String {
            checkpoint()
            val fields = linkedMapOf("access_token" to account.accessToken, "user_id" to account.userId, "platform" to "VD")
            if (distributor) { fields["privilege_level"] = level; fields["developer_type"] = "Individual" }
            val name = if (distributor) "distributor" else "author"
            val body = Multipart.csr(fields, "$name.csr", csr)
            return http.request("https://svdca.samsungqbe.com/apis/v$version/${name}s", "POST", body.bytes, mapOf("Content-Type" to body.contentType), 1024 * 1024).requireOk().toString(Charsets.UTF_8)
        }
        val authorReply = request(false, authorCsr)
        // Samsung Certificate Extension DistributorGenerator: v1 writes device-profile.xml,
        // then v3 issues the PEM certificate, using the SAME distributor CSR/key.
        // Do not infer the response type from request order or PEM text inside an XML profile.
        val profile = request(true, distributorCsr, version = 1)
        try {
            require(profile.trimStart().startsWith('<'))
            SafeXml.parse(profile)
        } catch (_: Exception) {
            // Never expose response bodies, tokens, account IDs or XML parser exceptions.
            throw BootstrapError("cert.profile", "Samsung 기기 프로파일(v1) 응답이 올바른 XML이 아닙니다. 발급을 중단했습니다.")
        }
        val distributorReply = request(true, distributorCsr)
        if (!distributorReply.trimStart().startsWith("-----BEGIN CERTIFICATE-----"))
            throw BootstrapError("cert.response", "Samsung 배포자 인증서(v3) 응답이 올바른 PEM이 아닙니다. 발급을 중단했습니다.")
        val author = identity(authorReply, authorKey, roots.getValue("vd_tizen_dev_author_ca.cer"))
        val distributorCa = if (level == "Partner") "vd_tizen_dev_partner2.crt" else "vd_tizen_dev_public2.crt"
        val distributor = identity(distributorReply, distributorKey, roots.getValue(distributorCa))
        return CertificatePair(author, distributor, profile, listOf(duid), level).also { it.validate(duid) }
    }
    private fun identity(reply: String, key: KeyPair, caBytes: ByteArray): SigningIdentity {
        val leaf = Pem.certificate(reply); val ca = Pem.certificate(caBytes)
        require(leaf.publicKey.encoded.contentEquals(key.public.encoded)) { "발급된 인증서의 키가 요청과 일치하지 않습니다." }
        require(leaf.issuerX500Principal == ca.subjectX500Principal); leaf.verify(ca.publicKey)
        return SigningIdentity(listOf(Pem.encode("CERTIFICATE", leaf.encoded), Pem.encode("CERTIFICATE", ca.encoded)), Pem.encode("PRIVATE KEY", key.private.encoded)).also { it.validate() }
    }
    private fun downloadChains(): Map<String, ByteArray> {
        val metadata = "https://download.tizen.org/sdk/tizenstudio/official/extension_info.xml"
        val fallback = "https://download.tizen.org/sdk/extensions/tizen-certificate-extension_2.0.70.zip"
        val url = try {
            val xml = SafeXml.parse(http.request(metadata).requireOk().toString(Charsets.UTF_8))
            val extensions = xml.getElementsByTagName("extension")
            (0 until extensions.length).map { extensions.item(it) as org.w3c.dom.Element }
                .firstOrNull { it.getElementsByTagName("name").item(0)?.textContent?.trim() == "Samsung Certificate Extension" }
                ?.getElementsByTagName("repository")?.item(0)?.textContent?.trim()?.takeIf {
                    val uri = java.net.URI(it); uri.scheme == "https" && uri.host == "download.tizen.org" && uri.userInfo == null
                } ?: fallback
        } catch (e: BootstrapError) { if (e.code == "cancelled") throw e else fallback } catch (_: Exception) { fallback }
        checkpoint()
        val bytes = http.request(url, limit = 64 * 1024 * 1024).requireOk()
        val wanted = setOf("vd_tizen_dev_author_ca.cer", "vd_tizen_dev_partner2.crt", "vd_tizen_dev_public2.crt")
        val found = mutableMapOf<String, ByteArray>(); var examined = 0L
        fun visit(data: ByteArray, depth: Int) {
            require(depth <= 3); examined += data.size; require(examined <= 192L * 1024 * 1024)
            val entries = Archives.read(data, 128 * 1024 * 1024, 64 * 1024 * 1024)
            entries.forEach { (path, value) ->
                checkpoint(); val name = path.substringAfterLast('/')
                if (name in wanted) { require(name !in found || found.getValue(name).contentEquals(value)); found[name] = value }
            }
            if (!found.keys.containsAll(wanted) && depth < 3) entries.filterKeys { it.endsWith(".zip") || it.endsWith(".jar") }.values.forEach { visit(it, depth + 1) }
        }
        visit(bytes, 0)
        if (!found.keys.containsAll(wanted)) throw BootstrapError("cert.chain", "Samsung Certificate Extension의 인증서 구조를 확인하지 못했습니다.")
        return found
    }
}
