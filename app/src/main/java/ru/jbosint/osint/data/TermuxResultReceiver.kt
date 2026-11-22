package ru.jbosint.osint.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Живой канал результатов Termux -> ViewModel. */
object TermuxResults {
    private val _events = MutableSharedFlow<TermuxResult>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun publish(result: TermuxResult) {
        _events.tryEmit(result)
    }
}

/**
 * Принимает результат RUN_COMMAND, который Termux отправляет через наш PendingIntent.
 * Компонент не экспортируется: PendingIntent исполняется от имени приложения.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val bundle = intent.getBundleExtra(TermuxSpec.RESULT_BUNDLE)
        val result = TermuxResult(
            requestId = requestId,
            exitCode = bundle?.getInt(KEY_EXIT_CODE, -1) ?: -1,
            stdout = bundle?.getString(KEY_STDOUT).orEmpty(),
            stderr = bundle?.getString(KEY_STDERR).orEmpty(),
            error = bundle?.getString(KEY_ERRMSG).orEmpty()
        )
        TermuxResults.publish(result)
    }

    companion object {
        const val ACTION = "ru.jbosint.osint.TERMUX_RESULT"
        const val EXTRA_REQUEST_ID = "request_id"

        // Ключи из TermuxConstants.TERMUX_SERVICE.
        private const val KEY_STDOUT = "stdout"
        private const val KEY_STDERR = "stderr"
        private const val KEY_EXIT_CODE = "exitCode"
        private const val KEY_ERRMSG = "errmsg"
    }
}
