// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

/** Pins a user-selected file. Its checksum proves continuity, not publisher authenticity. */
data class LocalWidget(val name: String, val size: Int, val sha256: String) {
    init {
        require(name.length in 5..255 && name.endsWith(".wgt", true) && name.none { it.isISOControl() || it == '/' || it == '\\' })
        require(size in 1..GitHubReleases.MAX_WGT)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
    fun verify(bytes: ByteArray): WidgetIdentity {
        require(bytes.size == size && Safety.constantEqual(Safety.sha256(bytes), sha256)) { "선택한 WGT 파일이 변경되었습니다. 다시 선택하세요." }
        return Wgt.identity(bytes)
    }
    fun encode() = Json.stringify(mapOf("name" to name, "size" to size, "sha256" to sha256))
    companion object {
        fun inspect(name: String, bytes: ByteArray): LocalWidget = LocalWidget(name, bytes.size, Safety.sha256(bytes)).also { it.verify(bytes) }
        fun decode(value: String): LocalWidget {
            val m = Json.obj(value)
            val size = (m["size"] as Number).toLong()
            require(size in 1..GitHubReleases.MAX_WGT.toLong())
            return LocalWidget(m.string("name"), size.toInt(), m.string("sha256"))
        }
    }
}
