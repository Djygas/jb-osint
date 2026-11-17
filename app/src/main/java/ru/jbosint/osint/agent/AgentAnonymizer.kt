package ru.jbosint.osint.agent

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.SourceRef
import ru.jbosint.osint.core.report.SourceCatalog

/**
 * Анонимизация источников в отчёте.
 *
 * Все модули получают нейтральные номера «Модуль 1…N» в порядке первого
 * появления факта. Реальные названия и адреса остаются только в списке
 * [Result.modules] — он выводится отдельным приложением B, а основной текст
 * отчёта номеров не раскрывает.
 */
object AgentAnonymizer {

    data class Result(
        val data: AggregatedData,
        val modules: List<ModuleLabel>
    ) {
        fun labelFor(sourceId: String): String =
            modules.firstOrNull { it.id == sourceId }?.label ?: "Модуль ?"
    }

    fun apply(data: AggregatedData): Result {
        val order = LinkedHashSet<String>()
        data.facts.forEach { fact -> order += fact.sourceIds }
        data.sources.forEach { source -> order += source.id }
        if (order.isEmpty()) return Result(data, emptyList())

        val index = order.withIndex().associate { (position, id) -> id to (position + 1).toString() }
        val modules = order.mapIndexed { position, id ->
            val real = data.sources.firstOrNull { it.id == id }
                ?: SourceCatalog.ref(id)
            ModuleLabel(
                id = id,
                label = "Модуль ${position + 1}",
                realName = real.name,
                url = real.url
            )
        }

        val facts = data.facts.map { fact ->
            fact.copy(sourceIds = fact.sourceIds.map { id -> index[id] ?: id }.toSet())
        }
        val sources = modules.map { module ->
            SourceRef(
                id = index[module.id] ?: module.id,
                name = module.label,
                url = module.url
            )
        }
        return Result(data.copy(facts = facts, sources = sources), modules)
    }
}

/**
 * Тексты отчёта: утвердительные формулировки фактов и отдельный блок
 * аналитических предположений. Статус достоверности никогда не смешивается
 * с самим фактом — он выводится отдельным полем.
 */
object AgentNarrator {

    fun findings(data: AggregatedData, limit: Int = 60): List<AgentFinding> {
        val ordered = data.facts.sortedWith(
            compareByDescending<Fact> { it.confidence.level }
                .thenByDescending { it.observations }
                .thenBy { it.category.order }
        )
        return ordered.take(limit).map { fact ->
            AgentFinding(
                text = sentence(fact),
                kind = fact.kind,
                value = fact.value,
                status = fact.confidence,
                moduleLabel = fact.sourceIds.sorted()
                    .joinToString(", ") { "Модуль $it" },
                evidence = fact.urls.firstOrNull().orEmpty()
            )
        }
    }

    /** Утвердительная формулировка: «У объекта «X» зафиксировано — kind: value.» */
    fun sentence(fact: Fact): String {
        val subject = fact.subject.ifBlank { "объект" }
        val prefix = when (fact.category) {
            FactCategory.PERSON -> "У объекта «$subject» установлен"
            FactCategory.CONTACT -> "У объекта «$subject» зафиксирован"
            FactCategory.ORGANIZATION -> "У объекта «$subject» подтвержден"
            FactCategory.LOCATION -> "У объекта «$subject» определён"
            FactCategory.TECHNICAL -> "У объекта «$subject» выявлен"
            FactCategory.DOCUMENT -> "У объекта «$subject» обнаружен"
            FactCategory.OTHER -> "У объекта «$subject» отмечен"
        }
        return "$prefix $fact.kind: ${fact.value}."
    }

    /**
     * Аналитические предположения. Это не факты: каждое помечено, на чём основано,
     * и выводится отдельным блоком.
     */
    fun assumptions(data: AggregatedData, target: AgentTarget): List<AgentAssumption> {
        val out = ArrayList<AgentAssumption>()

        // 1. Одно значение — несколько субъектов: возможна связь разных профилей.
        val byValue = data.facts
            .filter { it.category == FactCategory.PERSON || it.category == FactCategory.CONTACT }
            .groupBy { it.value.lowercase() }
        byValue.forEach { (_, facts) ->
            val subjects = facts.map { it.subject }.filter { it.isNotBlank() }.distinct()
            if (subjects.size > 1) {
                out += AgentAssumption(
                    "Возможна связь между объектами: ${subjects.joinToString(", ")}.",
                    "одно значение ${facts.first().value} встречается у ${subjects.size} субъектов"
                )
            }
        }

        // 2. Домен почты совпадает с организацией — вероятна корпоративная принадлежность.
        val emailDomains = data.facts.filter { it.category == FactCategory.CONTACT && it.value.contains('@') }
            .map { it.value.substringAfter('@').lowercase() }
            .toSet()
        val organizations = data.facts.filter { it.category == FactCategory.ORGANIZATION }
            .map { it.value.lowercase() }
        val matchedDomains = emailDomains.filter { domain -> organizations.any { org -> domain.contains(org.take(6)) } }
        if (matchedDomains.isNotEmpty()) {
            out += AgentAssumption(
                "Домены ${matchedDomains.joinToString(", ")} могут относиться к организации, найденной в реестрах.",
                "совпадение домена адреса и названия организации"
            )
        }

        // 3. Авто: большой пробег при молодом годе — возможен скрученный одометр.
        if (target == AgentTarget.VIN) {
            val year = data.facts.firstOrNull { it.kind.contains("Год выпуска") }?.value?.toIntOrNull()
            val mileage = data.facts.firstOrNull { it.kind.contains("Пробег") }
                ?.value?.let { Regex("\\d+").find(it.replace(" ", ""))?.value?.toIntOrNull() }
            if (year != null && mileage != null && year >= 2016 && mileage > 200_000) {
                out += AgentAssumption(
                    "Возможен скрученный пробег: $mileage км для автомобиля $year года.",
                    "пробег выше типичного для этого года выпуска"
                )
            }
            if (data.facts.any { it.kind.contains("Залог") && it.value.any { c -> c.isDigit() } }) {
                out += AgentAssumption(
                    "Автомобиль может находиться в залоге — проверьте реестр залогов перед сделкой.",
                    "сервис вернул запись о залоге"
                )
            }
        }

        // 4. Профиль помечен как удалённый/заблокированный, но следы остались.
        val deleted = data.facts.any { it.attributes["Статус"]?.contains("deleted", true) == true }
        if (deleted) {
            out += AgentAssumption(
                "Профиль мог быть переименован: аккаунт помечен как удалённый, но данные сохранились в кэшах.",
                "источник вернул статус удалённого профиля"
            )
        }

        // 5. Много сведений из одного источника — вероятна неполная картина.
        val singleSource = data.facts.count { it.sourceCount == 1 }
        if (singleSource > 0 && data.facts.size > 3) {
            out += AgentAssumption(
                "Часть сведений ($singleSource из ${data.facts.size}) подтверждена одним источником — перед выводами стоит проверить независимо.",
                "низкое число независимых источников у части фактов"
            )
        }

        return out.take(6)
    }

    /** Статус достоверности для отчёта: подпись + короткое пояснение. */
    fun statusNote(status: Confidence): String = when (status) {
        Confidence.CONFIRMED -> "три и более независимых источника"
        Confidence.PROBABLE -> "два независимых источника"
        Confidence.UNVERIFIED -> "единственный источник"
    }
}
