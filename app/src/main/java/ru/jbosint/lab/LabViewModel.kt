package ru.jbosint.lab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.jbosint.lab.repository.GitHubLabRepository

/** Состояние встроенного сервера для экрана лаборатории. */
data class LabServerUi(
    val running: Boolean = false,
    val starting: Boolean = false,
    val note: String? = null
)

/**
 * Управление встроенным сервером лаборатории: включение/остановка foreground
 * service и проверка /health. Никаких Termux и ручных настроек.
 */
class LabViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = GitHubLabRepository(app)

    private val _state = MutableStateFlow(LabServerUi(running = LabService.running))
    val state: StateFlow<LabServerUi> = _state.asStateFlow()

    init {
        LabToken.ensure(app)
    }

    fun setEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            LabToken.ensure(app)
            _state.update { it.copy(starting = true, note = "Запускаю встроенный сервер…") }
            runCatching { LabService.start(app) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            running = false,
                            starting = false,
                            note = error.message ?: "Не удалось запустить сервис"
                        )
                    }
                }
            viewModelScope.launch {
                delay(900)
                val ok = runCatching { repository.health() }.getOrDefault(false)
                _state.update {
                    it.copy(
                        running = ok || LabService.running,
                        starting = false,
                        note = if (ok) "Сервер отвечает" else "Сервер запускается…"
                    )
                }
            }
        } else {
            runCatching { LabService.stop(app) }
            _state.update { it.copy(running = false, starting = false, note = "Лаборатория остановлена") }
        }
    }

    fun check() {
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            val ok = runCatching { repository.health() }.getOrDefault(false)
            _state.update {
                it.copy(
                    running = ok,
                    starting = false,
                    note = if (ok) "Сервер отвечает" else "Сервер не отвечает — включите лабораторию"
                )
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(running = LabService.running) }
    }
}
