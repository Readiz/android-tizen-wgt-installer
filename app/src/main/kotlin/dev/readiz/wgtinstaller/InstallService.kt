// SPDX-License-Identifier: GPL-3.0-only
package dev.readiz.wgtinstaller

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import dev.readiz.wgtinstaller.core.*
import java.util.concurrent.Executors

/** User-started foreground transfer; never resumes installation commands after process death. */
class InstallService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var cancellation: Cancellation? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val channel = "wgt-installations"
    private var lastNotification = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(channel, AppLanguage.wrap(this).getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancellation?.cancel(); return START_NOT_STICKY }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        val ip = intent.getStringExtra("ip") ?: run { stopSelf(startId); return START_NOT_STICKY }
        if (!InstallState.begin(ip)) return START_NOT_STICKY
        val preparing = AppLanguage.wrap(this).getString(R.string.phase_connecting)
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification(preparing), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(41, notification(preparing))
        val token = Cancellation(); cancellation = token
        val selectedJson = intent.getStringExtra("asset")
        val localJson = intent.getStringExtra("local")
        val trusted = intent.getBooleanExtra("trust", false)
        wakeLock = requireNotNull(getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WgtInstaller:transfer").apply { acquire(15 * 60 * 1000L) }
        val journal = getSharedPreferences("install-journal", MODE_PRIVATE)
        journal.edit().putString("ip", ip).putString("phase", Phase.CONNECTING.name).commit()
        executor.execute {
            val publish: (Update) -> Unit = { update ->
                token.check(); InstallState.publish(update)
                if (update.progress < 0) journal.edit().putString("phase", update.phase.name).commit()
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotification > 500 || update.progress < 0) {
                    lastNotification = now
                    runCatching { requireNotNull(getSystemService(NotificationManager::class.java)).notify(41, notification(AppMessages.status(AppLanguage.wrap(this), update))) }
                }
            }
            try {
                require(trusted && ((selectedJson != null) xor (localJson != null))) { "선택한 파일과 설치 동의가 필요합니다." }
                val installer = RepoInstaller(WifiLan(applicationContext), SafeHttp(token), EncryptedVault(applicationContext), LocalHistory(applicationContext), token, publish)
                if (localJson != null) {
                    val local = LocalWidget.decode(localJson)
                    installer.runLocal(ip, local, LocalWgtStore(applicationContext).read(local, token), trusted)
                } else installer.run(ip, ReleaseAsset.decode(requireNotNull(selectedJson)), trusted)
            } catch (e: Exception) {
                val cancelled = runCatching { token.check() }.exceptionOrNull() != null
                val message = when {
                    cancelled -> "작업을 중단했습니다. 설치 명령 전송 후라면 TV에서 결과를 확인하세요."
                    e is BootstrapError -> e.message ?: "설치를 완료하지 못했습니다."
                    e is java.net.SocketTimeoutException -> "연결 시간 초과. 같은 Wi-Fi, Host PC IP=휴대폰 IP, TV 재부팅을 확인하세요."
                    e is java.net.ConnectException -> "TV가 연결을 거부했습니다. 개발자 모드와 Host PC IP를 확인하세요."
                    e is java.security.cert.CertificateExpiredException -> "인증서가 만료되었습니다. 기존 Author 키를 교체하지 않고 중단했습니다."
                    e is IllegalArgumentException -> e.message?.take(250) ?: "입력 또는 서버 응답 검증 실패"
                    else -> "${e.javaClass.simpleName}: 설치를 완료하지 못했습니다. 자동 삭제·재설치는 하지 않았습니다."
                }
                InstallState.publish(Update(if (cancelled) Phase.CANCELLED else Phase.ERROR, message), if (cancelled) "cancelled" else AppMessages.errorCode(e))
                journal.edit().putString("phase", "interrupted").commit()
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null
                Handler(Looper.getMainLooper()).post { cancellation = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); InstallState.finish() }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val localized = AppLanguage.wrap(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, InstallService::class.java).setAction("cancel"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(localized.getString(R.string.app_name))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.stop), cancel).build()).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancellation?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
    override fun onDestroy() { cancellation?.cancel(); executor.shutdownNow(); runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; super.onDestroy() }
}
