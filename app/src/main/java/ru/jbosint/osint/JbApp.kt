package ru.jbosint.osint

import android.app.Application

/**
 * Точка входа приложения. Намеренно не пишет ключи и запросы в логи.
 */
class JbApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: JbApp
            private set
    }
}
