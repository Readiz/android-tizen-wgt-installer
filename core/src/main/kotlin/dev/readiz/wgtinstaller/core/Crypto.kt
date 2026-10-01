// SPDX-License-Identifier: GPL-3.0-only
// Samsung CSR format follows reisxd/tizen.js src/samsungCertificateCreator.js.
package dev.readiz.wgtinstaller.core

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

object Pem {
    fun encode(label: String, bytes: ByteArray): String = "-----BEGIN $label-----\n" + Base64.getEncoder().encodeToString(bytes).chunked(64).joinToString("\n") + "\n-----END $label-----\n"
    fun decode(label: String, text: String): ByteArray {
        val pattern = Regex("-----BEGIN ${Regex.escape(label)}-----([A-Za-z0-9+/=\\r\\n ]+)-----END ${Regex.escape(label)}-----")
        val body = pattern.find(text)?.groupValues?.get(1) ?: error("Invalid PEM $label")
        return Base64.getDecoder().decode(body.filterNot(Char::isWhitespace))
    }
    fun certificate(bytes: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    fun certificate(text: String): X509Certificate = certificate(decode("CERTIFICATE", text))
    fun privateKey(text: String): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decode("PRIVATE KEY", text)))
}

/** DER encoding only. Cryptographic primitives are supplied by the JCA provider. */
object Der {
    private fun concat(parts: Array<out ByteArray>): ByteArray = parts.fold(byteArrayOf()) { a, b -> a + b }
    fun tag(type: Int, body: ByteArray): ByteArray {
        val n = body.size
        val length = if (n < 128) byteArrayOf(n.toByte()) else {
            val bytes = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }
        return byteArrayOf(type.toByte()) + length + body
    }
    fun seq(vararg parts: ByteArray) = tag(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tag(0x31, concat(parts))
    fun integer(n: Int) = tag(2, BigInteger.valueOf(n.toLong()).toByteArray())
    fun utf8(s: String) = tag(12, s.toByteArray(Charsets.UTF_8))
    fun ia5(s: String): ByteArray { require(s.all { it.code < 128 }); return tag(22, s.toByteArray(Charsets.US_ASCII)) }
    fun oid(s: String): ByteArray {
        val values = s.split('.').map(String::toLong); require(values.size >= 2 && values[0] in 0..2 && values[1] >= 0)
        fun base128(value: Long): ByteArray {
            require(value >= 0); var n = value; val bytes = mutableListOf((n and 127).toByte()); n = n ushr 7
            while (n > 0) { bytes.add(((n and 127) or 128).toByte()); n = n ushr 7 }
            return bytes.reversed().toByteArray()
        }
        var body = base128(values[0] * 40 + values[1]); values.drop(2).forEach { body += base128(it) }
        return tag(6, body)
    }
}

object Csr {
    fun key(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    fun fromPrivate(key: PrivateKey): KeyPair {
        val rsa = key as RSAPrivateCrtKey
        return KeyPair(KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(rsa.modulus, rsa.publicExponent)), key)
    }
    fun author(name: String, key: KeyPair): String {
        require(name.isNotBlank() && name.length <= 128)
        return request(Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(name)))), byteArrayOf(), key)
    }
    fun distributor(email: String, duids: List<String>, key: KeyPair): String {
        require(email.length in 3..254 && email.contains('@') && email.all { it.code in 33..126 }) { "Samsung 계정에서 이메일 주소를 확인하지 못했습니다." }
        require(duids.isNotEmpty() && duids.size <= 50); duids.forEach(Safety::duid)
        val subject = Der.seq(
            Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8("TizenSDK"))),
            Der.set(Der.seq(Der.oid("1.2.840.113549.1.9.1"), Der.ia5(email)))
        )
        val names = listOf("URN:tizen:packageid=") + duids.map { "URN:tizen:deviceid=$it" }
        val san = Der.seq(*names.map { Der.tag(0x86, it.toByteArray(Charsets.US_ASCII)) }.toTypedArray())
        val extensions = Der.seq(Der.seq(Der.oid("2.5.29.17"), Der.tag(4, san)))
        val attributes = Der.seq(Der.oid("1.2.840.113549.1.9.14"), Der.set(extensions))
        return request(subject, attributes, key)
    }
    private fun request(subject: ByteArray, attributes: ByteArray, key: KeyPair): String {
        val info = Der.seq(Der.integer(0), subject, key.public.encoded, Der.tag(0xa0, attributes))
        val signature = Signature.getInstance("SHA512withRSA").apply { initSign(key.private); update(info) }.sign()
        return Pem.encode("CERTIFICATE REQUEST", Der.seq(info, Der.seq(Der.oid("1.2.840.113549.1.1.13"), Der.tag(5, byteArrayOf())), Der.tag(3, byteArrayOf(0) + signature)))
    }
}

class SigningIdentity(val certificates: List<String>, val keyPem: String) {
    fun key() = Pem.privateKey(keyPem)
    fun leaf() = Pem.certificate(certificates.first())
    fun validate() {
        require(certificates.isNotEmpty() && certificates.size <= 5)
        val chain = certificates.map(Pem::certificate)
        chain.forEach { it.checkValidity() }
        val rsa = key() as? RSAPrivateCrtKey ?: error("RSA key required")
        require(chain[0].publicKey.encoded.contentEquals(Csr.fromPrivate(rsa).public.encoded)) { "인증서와 개인키가 일치하지 않습니다." }
        for (i in 0 until chain.lastIndex) {
            require(chain[i].issuerX500Principal == chain[i + 1].subjectX500Principal) { "인증서 체인이 일치하지 않습니다." }
            chain[i].verify(chain[i + 1].publicKey)
        }
    }
    fun asMap(): Map<String, Any?> = mapOf("certificates" to certificates, "key" to keyPem)
    override fun toString() = "SigningIdentity([REDACTED])"
    companion object {
        fun fromMap(m: Map<String, Any?>): SigningIdentity = SigningIdentity((m["certificates"] as List<*>).map { it as String }, m.string("key"))
    }
}

class CertificatePair(val author: SigningIdentity, val distributor: SigningIdentity, val profile: String, val duids: List<String>, val level: String) {
    fun validate(duid: String) {
        require(level in setOf("Partner", "Public")); author.validate(); distributor.validate(); Safety.duid(duid)
        require(duid in duids)
        val names = distributor.leaf().subjectAlternativeNames.orEmpty()
        require(names.any { it.size >= 2 && it[0] == 6 && it[1] == "URN:tizen:deviceid=$duid" }) { "이 인증서는 선택한 TV의 DUID를 포함하지 않습니다." }
        require(profile.isNotBlank()); SafeXml.parse(profile)
    }
    fun encode(): ByteArray = Json.stringify(mapOf("author" to author.asMap(), "distributor" to distributor.asMap(), "devices" to duids, "profile" to profile, "level" to level)).toByteArray()
    override fun toString() = "CertificatePair([REDACTED])"
    companion object {
        fun decode(bytes: ByteArray): CertificatePair {
            val m = Json.obj(bytes.toString(Charsets.UTF_8))
            return CertificatePair(SigningIdentity.fromMap(m.objectAt("author")), SigningIdentity.fromMap(m.objectAt("distributor")), m.string("profile"), (m["devices"] as List<*>).map { it as String }, m.string("level"))
        }
    }
}
