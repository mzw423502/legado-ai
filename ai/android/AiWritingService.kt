/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.legado.app.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.legado.app.ai.core.Cancellation
import io.legado.app.ai.core.RunLimits
import io.legado.app.ai.core.RunMode
import java.util.concurrent.Executors

class AiWritingService : Service() {
    companion object {
        const val ACTION_STOP = "io.legado.app.ai.STOP"
        private const val CHANNEL = "ai_novel_generation"
        private const val NOTIFICATION_ID = 71001
        fun start(context: Context, id: String, limits: RunLimits) {
            limits.validate()
            ContextCompat.startForegroundService(context, Intent(context, AiWritingService::class.java)
                .putExtra("id", id).putExtra("mode", limits.mode.name).putExtra("target", limits.targetChapter)
                .putExtra("requests", limits.requestLimit).putExtra("tokens", limits.tokenLimit)
                .putExtra("minutes", limits.maxMinutes).putExtra("battery", limits.minBattery))
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var currentId: String? = null
    private var token: Cancellation? = null
    private var wake: PowerManager.WakeLock? = null
    private var finished = false
    private val tick = object : Runnable {
        override fun run() {
            val id = currentId ?: return
            AiRuntime.io.execute {
                val p = runCatching { AiRuntime.store.get(id) }.getOrNull()
                val text = p?.let { "${it.title} · 已完成${it.chapters.size}章\n${it.status}" } ?: "准备创作"
                main.post {
                    if (!finished && currentId == id) {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
                        main.postDelayed(this, 1800)
                    }
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "AI 小说创作", NotificationManager.IMPORTANCE_LOW))
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification("正在准备创作"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification("正在准备创作"))
    }
    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, AiActivity::class.java).putExtra("projectId", currentId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AiWritingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("阅读 · AI 创作").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止并保留草稿", stop).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopGeneration(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("id")
        if (id == null) { stopSelf(); return START_NOT_STICKY }
        if (currentId != null) return START_NOT_STICKY
        try {
            AiRuntime.init(this)
            val limits = RunLimits(RunMode.valueOf(intent.getStringExtra("mode") ?: RunMode.ONE_CHAPTER.name),
                intent.getIntExtra("target", 100), intent.getIntExtra("requests", 600),
                intent.getLongExtra("tokens", 3_000_000), intent.getIntExtra("minutes", 60), intent.getIntExtra("battery", 15))
            limits.validate()
            val leaseToken = AiRuntime.claim(id)
            token = leaseToken; currentId = id; finished = false
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AiWriting").apply {
                acquire(limits.maxMinutes * 60000L + 10000L)
            }
            main.post(tick)
            worker.execute {
                try { AiRuntime.engine(id, applicationContext).run(id, limits, leaseToken) }
                catch (e: Exception) {
                    runCatching { AiRuntime.store.update(id) { it.copy(activeRun = null, status = e.message ?: "任务已停止") } }
                } finally {
                    AiRuntime.release(id, leaseToken)
                    main.post { finished = true; main.removeCallbacks(tick); releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, e.message ?: "无法启动创作", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun stopGeneration() {
        token?.cancel()
        currentId?.let { id -> AiRuntime.io.execute { runCatching { AiRuntime.actions.stop(id) } } }
        if (currentId == null) stopSelf()
    }
    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopGeneration(); releaseWake(); stopSelf()
    }
    override fun onDestroy() {
        main.removeCallbacks(tick)
        if (!finished) stopGeneration()
        token?.cancel(); worker.shutdown(); releaseWake(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
