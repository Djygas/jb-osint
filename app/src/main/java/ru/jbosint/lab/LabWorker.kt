package ru.jbosint.lab

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Фоновая уборка лаборатории: раз в сутки удаляет проекты, к которым не
 * обращались больше недели. Запускается из LabService.
 */
object LabWorker {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jb-lab-worker").apply { isDaemon = true }
    }

    @Volatile
    private var scheduled = false

    fun schedule(context: Context) {
        if (scheduled) return
        scheduled = true
        val app = context.applicationContext
        executor.scheduleWithFixedDelay({ cleanup(app) }, 0, 24, TimeUnit.HOURS)
    }

    private fun cleanup(context: Context) {
        val root = File(context.filesDir, "lab")
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        root.listFiles()?.forEach { dir ->
            if (dir.lastModified() < cutoff) runCatching { dir.deleteRecursively() }
        }
    }
}
