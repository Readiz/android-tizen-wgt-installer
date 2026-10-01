// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

import java.net.URI

/** Public github.com repositories only. Paths, queries and credentials are not accepted. */
data class RepoRef(val owner: String, val name: String) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))) { "GitHub owner 형식을 확인하세요." }
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && name != "." && name != "..") { "GitHub repo 형식을 확인하세요." }
    }
    val fullName get() = "$owner/$name"
    val key get() = fullName.lowercase(java.util.Locale.ROOT)
    companion object {
        fun parse(input: String): RepoRef {
            val text = input.trim()
            require(text.length <= 400) { "GitHub 주소가 너무 깁니다." }
            val path = if (text.startsWith("https://", true)) {
                val uri = URI(text)
                require(uri.scheme.equals("https", true) && uri.host.equals("github.com", true) && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "https://github.com/owner/repo 주소를 입력하세요." }
                uri.rawPath.removePrefix("/").removeSuffix("/")
            } else text
            val parts = path.removeSuffix(".git").split('/')
            require(parts.size == 2) { "owner/repo 또는 GitHub 저장소 주소를 입력하세요." }
            return RepoRef(parts[0], parts[1])
        }
    }
}

/** The selected release/asset is pinned before an installation begins. Never re-fetch latest mid-install. */
data class ReleaseAsset(val repo: RepoRef, val releaseId: Long, val tag: String, val assetId: Long,
    val name: String, val size: Long, val url: String, val sha256: String?) {
    init {
        require(releaseId > 0 && assetId > 0 && tag.isNotBlank() && tag.length <= 256)
        require(name.length <= 255 && name.endsWith(".wgt", true) && name.none { it.isISOControl() || it == '/' || it == '\\' }) { "WGT 파일 이름을 확인하세요." }
        require(size in 1..GitHubReleases.MAX_WGT.toLong()) { "이 실험판은 64 MiB 이하 WGT만 지원합니다." }
        val uri = URI(url)
        val prefix = "/${repo.fullName}/releases/download/"
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith(prefix, true) && uri.rawPath.removePrefixIgnoreCase(prefix).split('/').size == 2 &&
            uri.path.split('/').none { it == "." || it == ".." }) { "선택한 저장소의 GitHub 릴리스 다운로드 주소가 아닙니다." }
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 형식이 올바르지 않습니다." }
    }
    fun encode(): String = Json.stringify(mapOf("repo" to repo.fullName, "releaseId" to releaseId, "tag" to tag, "assetId" to assetId, "name" to name, "size" to size, "url" to url, "sha256" to sha256))
    companion object {
        fun decode(text: String): ReleaseAsset {
            val m = Json.obj(text)
            return ReleaseAsset(RepoRef.parse(m.string("repo")), m.long("releaseId"), m.string("tag"), m.long("assetId"), m.string("name"), m.long("size"), m.string("url"), m["sha256"] as? String)
        }
    }
}
private fun String.removePrefixIgnoreCase(prefix: String) = if (startsWith(prefix, true)) substring(prefix.length) else this
private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Long) ?: (this[key] as? Int)?.toLong() ?: error("Missing integer: $key")
data class ReleaseCatalog(val repo: RepoRef, val id: Long, val tag: String, val assets: List<ReleaseAsset>) {
    fun choose(assetId: Long? = null): ReleaseAsset {
        if (assetId != null) return assets.singleOrNull { it.assetId == assetId } ?: throw BootstrapError("asset.missing", "선택한 WGT가 이 릴리스에 없습니다.")
        if (assets.size != 1) throw BootstrapError("asset.choice", "WGT가 ${assets.size}개입니다. TV 버전에 맞는 파일을 선택하세요.")
        return assets.single()
    }
}
data class DownloadedWidget(val asset: ReleaseAsset, val archive: ByteArray, val sha256: String, val identity: WidgetIdentity)

class GitHubReleases(private val http: HttpTransport) {
    companion object { const val MAX_WGT = 64 * 1024 * 1024 }
    fun inspect(input: String): ReleaseCatalog {
        val repo = RepoRef.parse(input)
        val response = http.request("https://api.github.com/repos/${repo.fullName}/releases/latest", headers = mapOf("Accept" to "application/vnd.github+json"))
        when (response.status) {
            404 -> throw BootstrapError("release.missing", "공개 정식 릴리스가 없거나 비공개·존재하지 않는 저장소입니다. 저장소 삭제로 단정할 수 없습니다.")
            403, 429 -> throw BootstrapError("github.limit", "GitHub 접근 제한 또는 요청 한도에 도달했습니다. 반복 요청을 중단했습니다.")
        }
        val m = Json.obj(response.requireOk().toString(Charsets.UTF_8))
        require(m["draft"] == false && m["prerelease"] == false) { "공개 정식 릴리스만 지원합니다." }
        val id = m.long("id"); val tag = m.string("tag_name")
        val list = m["assets"] as? List<*> ?: error("Missing assets")
        require(list.size <= 1000) { "릴리스 파일 수가 너무 많습니다." }
        val assets = list.map { @Suppress("UNCHECKED_CAST") (it as? Map<String, Any?> ?: error("Invalid asset")) }
            .filter { it.string("name").endsWith(".wgt", true) && it["state"] == "uploaded" }
            .map { a ->
                val digest = a["digest"] as? String
                require(digest == null || digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "지원하지 않는 릴리스 해시입니다." }
                ReleaseAsset(repo, id, tag, a.long("id"), a.string("name"), a.long("size"), a.string("browser_download_url"), digest?.substringAfter(':')?.lowercase())
            }
        if (assets.isEmpty()) throw BootstrapError("wgt.missing", "최신 정식 릴리스에 업로드된 WGT가 없습니다. 소스 ZIP을 WGT로 취급하지 않습니다.")
        require(assets.map { it.assetId }.distinct().size == assets.size) { "중복된 릴리스 asset ID입니다." }
        return ReleaseCatalog(repo, id, tag, assets)
    }
    fun download(asset: ReleaseAsset): DownloadedWidget {
        val expected = asset.sha256 ?: throw BootstrapError("release.digest", "이 WGT에는 GitHub SHA-256이 없습니다. 무결성 확인 없이 자동 설치하지 않습니다.")
        val bytes = http.request(asset.url, limit = MAX_WGT).requireOk()
        require(bytes.size.toLong() == asset.size) { "다운로드 크기가 릴리스 정보와 다릅니다." }
        val actual = Safety.sha256(bytes)
        require(Safety.constantEqual(actual, expected)) { "다운로드 SHA-256이 일치하지 않습니다." }
        return DownloadedWidget(asset, bytes, actual, Wgt.identity(bytes))
    }
}
