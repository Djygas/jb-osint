package ru.jbosint.osint.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Локальные уведомления мониторинга.
 *
 * Фоновой службы нет: уведомление показывается в момент проверки критерия
 * (пользователь сам нажал «Проверить»). Канал создаётся один раз, разрешение
 * POST_NOTIFICATIONS запрашивается на экране мониторинга.
 */
object Notify {

    private const val CHANNEL_ID = "jb_watch"
    private const val CHANNEL_NAME = "Мониторинг объявлений"

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Новые объявления по сохранённым критериям" }
        manager.createNotificationChannel(channel)
    }

    fun watch(context: Context, subject: String, text: String, id: Int = 1001) {
        if (!permissionGranted(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(subject.take(60))
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id, notification) }
    }
}
