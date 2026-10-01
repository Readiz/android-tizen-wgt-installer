// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.readiz.wgtinstaller.core.*
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Exportable TV signing keys are encrypted at rest by a non-exportable Android Keystore AES key. */
class EncryptedVault(context: Context) : PairVault {
    private val directory = File(context.noBackupFilesDir, "certificates").apply { mkdirs() }
    private val alias = "wgt-installer-v1"
    @Synchronized private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun file(duid: String) = AtomicFile(File(directory, Safety.sha256(Safety.duid(duid).toByteArray()) + ".bin"))
    override fun load(duid: String): CertificatePair? {
        val f = file(duid)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        try {
            val packed = f.openRead().use { it.readBounded(2 * 1024 * 1024) }
            require(packed.size > 29 && packed[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, packed.copyOfRange(1, 13)))
            cipher.updateAAD(duid.toByteArray())
            val plain = cipher.doFinal(packed.copyOfRange(13, packed.size))
            return try { CertificatePair.decode(plain) } finally { plain.fill(0) }
        } catch (e: Exception) { throw BootstrapError("vault.unreadable", "기존 인증서를 복호화하지 못했습니다. 새 Author 키로 바꾸지 않고 중단했습니다. 앱 데이터는 삭제하지 마세요.", e) }
    }
    override fun save(duid: String, pair: CertificatePair) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, wrappingKey()); cipher.updateAAD(duid.toByteArray())
        val plain = pair.encode(); val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        require(cipher.iv.size == 12)
        val f = file(duid); val output = f.startWrite()
        try { output.write(byteArrayOf(1) + cipher.iv + encrypted); f.finishWrite(output) }
        catch (e: Exception) { f.failWrite(output); throw e }
    }
}
