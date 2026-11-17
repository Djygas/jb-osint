package ru.jbosint.osint.profile

/** Что выгружаем из профиля. */
enum class ProfileExport(val label: String) {
    XLSX("Excel"),
    MESSAGES_CSV("Сообщения CSV"),
    AUDIENCE_CSV("Круг общения CSV"),
    GRAPHML("Граф GraphML"),
    DOSSIER("Досье HTML")
}

/** Шаблон текстового досье. */
enum class DossierTemplate(val title: String) {
    PERSON("Досье на человека"),
    ACTIVITY("Анализ активности"),
    CIRCLE("Круг общения")
}

/**
 * Сравнение двух профилей: что общего у объектов.
 * Используется инструментом «Сравнение профилей» — без сетевых запросов,
 * только по уже собранным данным.
 */
object ProfileCompare {

    fun compare(first: ProfileData, second: ProfileData): String {
        if (first.id == second.id) return "Выберите два разных профиля: откройте другой объект и повторите сравнение."
        val a = first.subject.title
        val b = second.subject.title
        val sb = StringBuilder()
        sb.appendLine("$a ↔ $b")

        val groupsA = first.groups.map { it.lowercase() }.toSet()
        val groupsB = second.groups.map { it.lowercase() }.toSet()
        val commonGroups = first.groups.filter { it.lowercase() in groupsB }
        sb.appendLine(
            "Общие группы и каналы: " + if (commonGroups.isEmpty()) "не найдены"
            else commonGroups.joinToString(", ") + " (${commonGroups.size} из ${(groupsA + groupsB).size})"
        )

        fun handle(c: ProfileContact) = c.username.ifBlank { c.id.ifBlank { c.name } }.lowercase()
        val contactsB = second.contacts.map(::handle).toSet()
        val commonContacts = first.contacts.filter { handle(it) in contactsB }
        sb.appendLine(
            "Общие знакомые: " + if (commonContacts.isEmpty()) "не найдены"
            else commonContacts.take(12).joinToString(", ") { it.name.ifBlank { it.username } } +
                if (commonContacts.size > 12) " и ещё ${commonContacts.size - 12}" else ""
        )

        val topicsA = first.behavior.topics.associate { it.name to it.share }
        val topicsB = second.behavior.topics.associate { it.name to it.share }
        val commonTopics = topicsA.keys.intersect(topicsB.keys)
        sb.appendLine(
            "Пересечение тем: " + if (commonTopics.isEmpty()) "нет"
            else commonTopics.joinToString(", ") { "$it (${topicsA[it]}% / ${topicsB[it]}%)" }
        )

        sb.appendLine(
            "Активность: ${first.summary.messages} и ${second.summary.messages} сообщений; " +
                "контактов ${first.summary.contacts} и ${second.summary.contacts}"
        )
        val link = listOf(first.subject.username, second.subject.username).filter { it.isNotBlank() }
        if (link.size == 2) sb.appendLine("Ники: @${link[0]} и @${link[1]}")
        sb.appendLine("Данные собраны из открытых источников.")
        return sb.toString()
    }
}

/**
 * Отслеживание изменений профиля (триггеры и алерты).
 * Сравнивает сохранённую копию с новой сборкой: смена ника, новые группы,
 * новые контакты, всплеск токсичности.
 */
object ProfileDiff {

    fun diff(old: ProfileData?, new: ProfileData): List<String> {
        if (old == null) return emptyList()
        val out = ArrayList<String>()
        if (old.subject.username.isNotBlank() && new.subject.username.isNotBlank() &&
            !old.subject.username.equals(new.subject.username, true)
        ) {
            out += "Сменился ник: @${old.subject.username} → @${new.subject.username}"
        }
        if (old.subject.title != new.subject.title && new.subject.title.isNotBlank()) {
            out += "Сменилось имя: ${old.subject.title} → ${new.subject.title}"
        }
        if (old.subject.status != new.subject.status) {
            out += "Статус аккаунта: ${old.subject.status.title} → ${new.subject.status.title}"
        }
        val oldGroups = old.groups.map { it.lowercase() }.toSet()
        val newGroups = new.groups.filter { it.lowercase() !in oldGroups }
        if (newGroups.isNotEmpty()) {
            out += "Новые группы/каналы: " + newGroups.take(5).joinToString(", ")
        }
        fun handle(c: ProfileContact) = c.username.ifBlank { c.id.ifBlank { c.name } }.lowercase()
        val oldContacts = old.contacts.map(::handle).toSet()
        val added = new.contacts.filter { handle(it) !in oldContacts }
        if (added.isNotEmpty()) {
            out += "Новые контакты: " + added.take(5).joinToString(", ") { it.name.ifBlank { it.username } }
        }
        if (new.reputation.toxicity - old.reputation.toxicity >= 10) {
            out += "Выросла токсичность: ${old.reputation.toxicity}% → ${new.reputation.toxicity}%"
        }
        if (new.summary.messages > old.summary.messages) {
            out += "Новые сообщения: +${new.summary.messages - old.summary.messages}"
        }
        return out
    }
}
