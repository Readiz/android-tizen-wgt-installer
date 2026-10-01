// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller
import android.content.Context
import dev.readiz.wgtinstaller.core.*
/** Metadata only, scoped to TV DUID + package ID. Backup is disabled at application level. */
class LocalHistory(context: Context) : InstallHistory {
    private val store = context.getSharedPreferences("installed-by-this-app", Context.MODE_PRIVATE)
    private fun key(duid: String, pkg: String) = Safety.sha256("${Safety.duid(duid)}/${Safety.packageId(pkg)}".toByteArray())
    override fun get(duid: String, packageId: String): InstallReceipt? = store.getString(key(duid, packageId), null)?.let {
        try { InstallReceipt.decode(it) }
        catch (e: Exception) { throw BootstrapError("history.unreadable", "기존 설치 기록이 손상되었습니다. 기존 앱을 덮어쓰지 않았습니다.", e) }
    }
    override fun put(duid: String, receipt: InstallReceipt) {
        check(store.edit().putString(key(duid, receipt.packageId), receipt.encode()).commit()) { "Failed to persist installation receipt" }
    }
}
