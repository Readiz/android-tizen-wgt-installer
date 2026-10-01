// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.readiz.wgtinstaller.core.*
import java.io.File

/** Copies document-provider bytes once; installation never reopens a mutable external URI. */
class LocalWgtStore(private val context: Context) {
    private fun file(selected: LocalWidget) = File(context.cacheDir, "wgt-imports/${selected.sha256}.wgt")
    fun import(uri: Uri, cancel: Cancellation): LocalWidget {
        require(uri.scheme == "content")
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw IllegalArgumentException("WGT file name missing")
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBounded(GitHubReleases.MAX_WGT, cancel) }
        val selected = LocalWidget.inspect(name, bytes)
        cancel.check()
        val target = file(selected)
        target.parentFile!!.mkdirs()
        val temporary = File.createTempFile("import-", ".tmp", target.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes) }
            cancel.check()
            check(temporary.renameTo(target))
        } finally { temporary.delete() }
        return selected
    }
    fun read(selected: LocalWidget, cancel: Cancellation): ByteArray = try {
        file(selected).inputStream().use { it.readBounded(GitHubReleases.MAX_WGT, cancel) }.also { selected.verify(it) }
    } catch (e: java.io.FileNotFoundException) {
        throw BootstrapError("local.missing", "선택한 임시 파일이 없습니다. WGT를 다시 선택하세요.", e)
    }
}
