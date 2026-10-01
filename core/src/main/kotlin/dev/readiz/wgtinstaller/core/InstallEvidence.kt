// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller.core

object InstallEvidence {
    fun failure(output: String): String? {
        val lower = output.lowercase()
        if (lower.contains("author certificate not match") || lower.contains("author certificate mismatch")) return "기존 앱의 Author 인증서가 다릅니다. 앱을 자동 삭제하지 않았습니다."
        if (lower.contains("install failed") || lower.contains("installation failed") || lower.contains("invalid signature") || lower.contains("check certificate error") || lower.contains("security error")) return "TV가 설치를 거부했습니다. 인증서·펌웨어 호환성과 개발자 모드를 확인하세요."
        return null
    }
    fun completed(output: String) = failure(output) == null && Regex("(?i)\\binstall(?:ation)? completed\\b").containsMatchIn(output)
    fun settled(output: String) = completed(output) || failure(output) != null
    /** An HTTP error is UNKNOWN, not proof that the package is absent. */
    fun registry(status: Int, body: String, appId: String): Presence {
        if (status == 404) return Presence(false, null, true)
        if (status !in 200..299) return Presence(null, null, false)
        return try { val m = Json.obj(body); if (m["id"] == appId) Presence(true, m["version"] as? String, true) else Presence(null, null, false) } catch (_: Exception) { Presence(null, null, false) }
    }
    fun appList(output: String, appId: String): Presence {
        val entries = Regex("(?m)^\\s*'[^'\\r\\n]*'\\s+'([A-Za-z0-9_.-]+)'\\s*$").findAll(output).map { it.groupValues[1] }.toList()
        return if (entries.isEmpty()) Presence(null, null, false) else Presence(appId in entries, null, true)
    }
    fun proven(output: String, before: Presence, after: Presence, version: String): Boolean =
        failure(output) == null && (completed(output) || (before.installed == false && after.installed == true && after.version == version))
}
data class Presence(val installed: Boolean?, val version: String?, val reliable: Boolean)
