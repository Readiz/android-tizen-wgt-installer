// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

/** UI never receives account tokens, private keys or raw server error bodies. */
enum class Phase { IDLE, RESOLVING, DOWNLOADING, CONNECTING, LOGIN, CERTIFICATES, SIGNING, TRANSFERRING, INSTALLING, VERIFYING, COMPLETE, ERROR, CANCELLED }
data class Update(val phase: Phase, val message: String, val progress: Int = -1, val browserUrl: String? = null)
interface PairVault { fun load(duid: String): CertificatePair?; fun save(duid: String, pair: CertificatePair) }
interface LanAccess { fun connect(ip: String, cancellation: Cancellation): SdbSession; fun http(ip: String, cancellation: Cancellation): HttpTransport }
/** The saved receipt is ownership evidence, not a current TV inventory. */
data class InstallReceipt(val repository: String, val packageId: String, val appId: String, val version: String, val authorSha256: String, val sourceSha256: String) {
    fun encode() = Json.stringify(mapOf("repository" to repository, "packageId" to packageId, "appId" to appId, "version" to version, "authorSha256" to authorSha256, "sourceSha256" to sourceSha256))
    companion object { fun decode(s: String): InstallReceipt { val m = Json.obj(s); return InstallReceipt(m.string("repository"), Safety.packageId(m.string("packageId")), m.string("appId"), m.string("version"), m.string("authorSha256"), m.string("sourceSha256")) } }
}
interface InstallHistory { fun get(duid: String, packageId: String): InstallReceipt?; fun put(duid: String, receipt: InstallReceipt) }
fun interface CertificateProvider { fun issue(duid: String, notify: (Update) -> Unit): CertificatePair }
class SamsungCertificates(private val cloud: HttpTransport, private val cancel: Cancellation) : CertificateProvider {
    override fun issue(duid: String, notify: (Update) -> Unit): CertificatePair {
        val account = SamsungLogin(cancel).use { login ->
            notify(Update(Phase.LOGIN, "삼성 계정 로그인이 필요합니다. 비밀번호는 앱이 받지 않습니다.", browserUrl = login.url()))
            login.await()
        }
        notify(Update(Phase.CERTIFICATES, "선택한 TV용 인증서를 Samsung에 요청합니다."))
        return SamsungIssuer(cloud) { cancel.check() }.mint(account, duid, "Partner")
    }
}

/** Standalone installer: the phone stays the developer host. NO key handoff or Homebrew service. */
class RepoInstaller(private val lan: LanAccess, private val cloud: HttpTransport, private val vault: PairVault,
    private val history: InstallHistory, private val cancel: Cancellation, private val notify: (Update) -> Unit,
    private val certificates: CertificateProvider = SamsungCertificates(cloud, cancel)) {
    private fun emit(p: Phase, m: String, n: Int = -1) { cancel.check(); notify(Update(p, m, n)) }
    fun run(ip: String, selected: ReleaseAsset, trustSource: Boolean): InstallReceipt {
        Safety.privateIpv4(ip)
        require(trustSource) { "이 저장소의 코드를 신뢰하고 내 TV에 설치하는 데 동의해야 합니다." }
        emit(Phase.DOWNLOADING, "${selected.repo.fullName}: ${selected.name} 다운로드·해시 확인")
        val widget = GitHubReleases(cloud).download(selected)
        return install(ip, selected.repo.key, selected.name, widget.archive, widget.sha256, widget.identity)
    }
    fun runLocal(ip: String, selected: LocalWidget, bytes: ByteArray, trustSource: Boolean): InstallReceipt {
        Safety.privateIpv4(ip)
        require(trustSource) { "선택한 파일의 출처를 신뢰하고 설치하는 데 동의해야 합니다." }
        cancel.check()
        val id = selected.verify(bytes)
        return install(ip, "local-wgt:${id.packageId}", selected.name, bytes, selected.sha256, id)
    }
    private fun install(ip: String, source: String, name: String, archive: ByteArray, sha256: String, id: WidgetIdentity): InstallReceipt {
        emit(Phase.CONNECTING, "${id.name} ${id.version} · TV 기기 ID와 기존 설치 확인")
        val duid = lan.connect(ip, cancel).use { it.duid() }
        val before = presence(ip, id.appId, duid)
        val record = history.get(duid, id.packageId)
        var pair = vault.load(duid)
        if (record != null && record.repository != source) throw BootstrapError("package.owner", "다른 출처가 같은 패키지 ID를 사용합니다. 기존 앱을 덮어쓰지 않았습니다.")
        if (record != null && pair == null) throw BootstrapError("author.lost", "기존 설치에 사용한 Author 키가 없습니다. 새 키로 교체하지 않았습니다.")
        if (before.installed != false && record == null) throw BootstrapError("author.unknown", "기존 앱의 소유 관계 또는 설치 여부를 확인하지 못했습니다. 기존 앱을 삭제하거나 덮어쓰지 않았습니다.")
        if (pair == null) {
            cancel.check(); pair = certificates.issue(duid, notify)
            pair.validate(duid)
            // Must be durable before any writes to the TV. The same author is reused for all apps on this TV.
            vault.save(duid, pair)
        }
        pair.validate(duid)
        val author = Safety.sha256(pair.author.leaf().encoded)
        if (record != null && record.authorSha256 != author) throw BootstrapError("author.changed", "설치 기록과 현재 Author 인증서가 다릅니다. 자동 교체하지 않았습니다.")
        emit(Phase.SIGNING, "${id.name} ${id.version} 재서명 · 원본 manifest와 권한은 보존")
        val signed = Wgt.sign(archive, pair)
        var output = ""
        lan.connect(ip, cancel).use { sdb ->
            require(sdb.duid() == duid) { "TV의 DUID가 바뀌었습니다. 전송을 중단합니다." }
            emit(Phase.TRANSFERRING, "기기 프로파일 전송 · 개인키는 휴대폰에 유지")
            sdb.push(REMOTE_PROFILE, pair.profile.toByteArray(Charsets.UTF_8))
            sdb.push(REMOTE_WGT, signed) { sent, total -> emit(Phase.TRANSFERRING, "$name 전송", sent * 100 / maxOf(total, 1)) }
            emit(Phase.INSTALLING, "TV 설치 명령 실행 · 응답이 끊겨도 자동 재전송하지 않음")
            try { output = sdb.exec("shell:0 vd_appinstall ${id.packageId} $REMOTE_WGT", 180000, InstallEvidence::settled) }
            catch (e: ShellInterrupted) { cancel.check(); output = e.output }
        }
        InstallEvidence.failure(output)?.let { throw BootstrapError("install.rejected", it) }
        emit(Phase.VERIFYING, "설치 완료 응답·앱 목록 확인")
        val after = presence(ip, id.appId, duid)
        if (!InstallEvidence.proven(output, before, after, id.version)) throw BootstrapError("install.unknown", "설치 성공을 확정하지 못했습니다. 자동 재설치하지 않았습니다. TV 앱 목록을 확인하세요.")
        val receipt = InstallReceipt(source, id.packageId, id.appId, id.version, author, sha256)
        try { history.put(duid, receipt) }
        catch (e: Exception) { throw BootstrapError("history.failed", "TV 설치는 완료됐지만 휴대폰 설치 기록 저장에 실패했습니다. 자동 재설치하지 마세요.", e) }
        emit(Phase.COMPLETE, "${id.name} ${id.version} 설치 완료. Host PC IP는 휴대폰 IP로 유지하세요. 앱 실행·재생 호환성은 별도 확인이 필요합니다.")
        return receipt
    }
    private fun presence(ip: String, appId: String, duid: String): Presence {
        cancel.check()
        val http = runCatching {
            val r = lan.http(ip, cancel).request("http://$ip:8001/api/v2/applications/$appId")
            InstallEvidence.registry(r.status, r.body.toString(Charsets.UTF_8), appId)
        }.getOrDefault(Presence(null, null, false))
        cancel.check()
        if (http.reliable) return http
        return runCatching { lan.connect(ip, cancel).use { s ->
            require(s.duid() == duid)
            InstallEvidence.appList(s.exec("shell:0 applist"), appId)
        } }.getOrDefault(Presence(null, null, false)).also { cancel.check() }
    }
}
