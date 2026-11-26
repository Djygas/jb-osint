package ru.jbosint.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.jbosint.lab.server.LabHttpServer
import ru.jbosint.osint.MainActivity
import ru.jbosint.osint.R

/**
 * Foreground Service встроенной лаборатории. Поднимает локальный HTTP-сервер
 * на 127.0.0.1:8081, показывает уведомление и останавливает сервер при остановке.
 */
class LabService : Service() {

    private var server: LabHttpServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LabToken.ensure(this)
        startForegroundNotification()
        if (server == null) {
            val started = runCatching { LabHttpServer(this).also { it.start() } }
            started.onSuccess {
                server = it
                running = true
                LabWorker.schedule(this)
            }
            started.onFailure {
                running = false
            }
        } else {
            running = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "JB Lab", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JB Lab запущен")
            .setContentText("Встроенная лаборатория слушает 127.0.0.1:8081")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        const val CHANNEL = "jb_lab"
        const val ID = 4201

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, LabService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, LabService::class.java)
            )
        }
    }
}
