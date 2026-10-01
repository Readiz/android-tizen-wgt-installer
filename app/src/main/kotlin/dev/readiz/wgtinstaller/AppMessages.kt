// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.content.Context
import dev.readiz.wgtinstaller.core.*

/** Keep protocol diagnostics separate from translated, user-facing status. */
object AppMessages {
    fun errorCode(error: Exception): String = when (error) {
        is BootstrapError -> error.code
        is java.net.SocketTimeoutException -> "connection.timeout"
        is java.net.ConnectException -> "sdb.connect"
        is java.security.cert.CertificateExpiredException -> "cert.expired"
        is IllegalArgumentException -> "validation"
        else -> "unexpected"
    }

    fun error(context: Context, code: String): String {
        if (code.startsWith("http.")) return context.getString(R.string.error_http, code.removePrefix("http."))
        return context.getString(when (code) {
            "cancelled" -> R.string.phase_cancelled
            "interrupted" -> R.string.interrupted
            "repo" -> R.string.error_repo
            "ip" -> R.string.error_ip
            "release.digest" -> R.string.error_digest
            "local.missing" -> R.string.error_local_missing
            "release.missing" -> R.string.error_release
            "github.limit" -> R.string.error_limit
            "wgt.missing" -> R.string.error_wgt
            "asset.missing", "asset.choice" -> R.string.error_asset
            "wifi.missing", "wifi.ip" -> R.string.error_wifi
            "sdb.connect" -> R.string.error_connect
            "connection.timeout" -> R.string.error_timeout
            "login.response", "login.timeout" -> R.string.error_login
            "cert.expired" -> R.string.error_expired
            "cert.profile", "cert.response", "cert.chain" -> R.string.error_certificate
            "package.owner", "author.unknown", "author.changed" -> R.string.error_ownership
            "author.lost", "vault.unreadable" -> R.string.error_keys
            "history.unreadable" -> R.string.error_history
            "history.failed" -> R.string.error_receipt
            "install.rejected" -> R.string.error_rejected
            "install.unknown" -> R.string.error_unknown
            "sdb.sync" -> R.string.error_transfer
            "sdb.auth" -> R.string.error_auth
            "size.limit" -> R.string.error_size
            "validation" -> R.string.error_validation
            else -> if (code.startsWith("sdb.")) R.string.error_protocol else R.string.error_generic
        })
    }

    fun status(context: Context, update: Update, code: String? = null): String {
        if (code != null) return error(context, code)
        return context.getString(when (update.phase) {
            Phase.IDLE -> R.string.phase_idle
            Phase.RESOLVING -> R.string.phase_resolving
            Phase.DOWNLOADING -> R.string.phase_downloading
            Phase.CONNECTING -> R.string.phase_connecting
            Phase.LOGIN -> R.string.phase_login
            Phase.CERTIFICATES -> R.string.phase_certificates
            Phase.SIGNING -> R.string.phase_signing
            Phase.TRANSFERRING -> R.string.phase_transferring
            Phase.INSTALLING -> R.string.phase_installing
            Phase.VERIFYING -> R.string.phase_verifying
            Phase.COMPLETE -> R.string.phase_complete
            Phase.ERROR -> R.string.error_generic
            Phase.CANCELLED -> R.string.phase_cancelled
        })
    }
}
