package ru.jbosint.lab

/** Запрос открыть папку в лаборатории из другого модуля (например, из «Утечек»). */
data class LabLaunchRequest(val dir: String, val title: String)

/** Одноразовая передача «открой эту папку в лаборатории» при переходе на вкладку. */
object LabLaunch {

    @Volatile
    private var pending: LabLaunchRequest? = null

    fun request(dir: String, title: String) {
        pending = LabLaunchRequest(dir, title)
    }

    fun consume(): LabLaunchRequest? {
        val value = pending
        pending = null
        return value
    }
}
