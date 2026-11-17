package ru.jbosint.osint.data

import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory

/**
 * Склейка сущностей и выбор «зацепок» для следующего шага поиска.
 *
 * Одна и та же почта, телефон или ник приходят из разных модулей в разном виде:
 * «+7 (999) 000-11-22», «89990001122», «@ToXa» и «vk.com/toxa». Здесь значения
 * приводятся к каноническому виду, поэтому дубликаты схлопываются, а граф
 * связей не рассыпается на копии одного человека.
 */
object EntityResolver {

    /** Зацепка для следующего прохода: что искать и в каком виде. */
    data class Pivot(val kind: String, val value: String, val query: String) {
        val key: String get() = "${kind.lowercase()}:${value.lowercase()}"
    }

    /**
     * Телефон в виде +7XXXXXXXXXX (для РФ) или + и цифры для международных.
     * Строгий разбор: даты (17.11.2026), номера заказов и ID сюда не проходят —
     * иначе они становятся «зацепками» и жгут запросы.
     */
    fun phone(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in 10..15) return null
        return when {
            digits.length == 11 && (digits.startsWith("7") || digits.startsWith("8")) -> "+7" + digits.drop(1)
            digits.length == 10 && digits.startsWith("9") -> "+7$digits"
            trimmed.startsWith("+") -> "+$digits"
            else -> null
        }
    }

    fun email(raw: String): String? {
        val clean = raw.trim().trim('<', '>', '"', '\'', ',', '.', ';').lowercase()
        return clean.takeIf { it.matches(Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$")) }
    }

    /** Ник из @ник, ссылки t.me/ник или голого логина. */
    fun handle(raw: String): String? {
        val clean = raw.trim()
        val candidate = when {
            clean.contains("t.me/") -> clean.substringAfter("t.me/").substringBefore('?').substringBefore('/')
            clean.contains("vk.com/") -> clean.substringAfter("vk.com/").substringBefore('?').substringBefore('/')
            clean.contains("github.com/") -> clean.substringAfter("github.com/").substringBefore('?').substringBefore('/')
            clean.contains("instagram.com/") -> clean.substringAfter("instagram.com/").substringBefore('?').substringBefore('/')
            else -> clean.trimStart('@')
        }.trim('/').lowercase()
        return candidate.takeIf {
            it.length in 2..40 && it.matches(Regex("^[a-z0-9_.\\-]+$")) &&
                it !in setOf("id", "profile", "index", "search", "user")
        }
    }

    fun domain(raw: String): String? {
        val clean = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').removePrefix("www.")
        return clean.takeIf { it.matches(Regex("^[a-z0-9.\\-]+\\.[a-z]{2,}$")) }
    }

    /** Имя для сравнения: нижний регистр, ё→е, без лишних пробелов и инициалов. */
    fun name(raw: String): String = raw.trim().lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\s\\-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Транслитерация: нужна, чтобы «Юлия» и «Yulia» считались одной зацепкой. */
    fun translit(raw: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh",
            'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
            'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ы' to "y",
            'ь' to "", 'ъ' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        val sb = StringBuilder(raw.length + 4)
        raw.lowercase().forEach { ch -> sb.append(map[ch] ?: ch) }
        return sb.toString()
    }

    /**
     * Совпадают ли два имени с точностью до транслитерации и порядка слов.
     * Сравнение терпимое к вариантам записи: «Юлия» и «Yulia» дают общий префикс
     * «yuli», поэтому имена из разных алфавитов склеиваются.
     */
    fun sameName(first: String, second: String): Boolean {
        val a = name(first).split(' ').filter { it.length > 1 }
        val b = name(second).split(' ').filter { it.length > 1 }
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        fun tokensMatch(x: String, y: String): Boolean {
            if (x == y) return true
            var common = 0
            while (common < minOf(x.length, y.length) && x[common] == y[common]) common++
            return common >= 4
        }
        val flatA = a.map(::translit)
        val flatB = b.map(::translit)
        if (flatA == flatB) return true
        val matches = flatA.count { token -> flatB.any { other -> tokensMatch(token, other) } }
        return matches >= 2 || (minOf(a.size, b.size) == 1 && matches >= 1)
    }

    /** Канонический ключ значения — для дедупликации фактов и узлов графа. */
    fun key(category: FactCategory, kind: String, value: String): String? = when {
        category == FactCategory.CONTACT || kind.contains("Телефон", true) || kind.contains("phone", true) ->
            phone(value)?.let { "phone:$it" }
        kind.contains("Почт", true) || kind.contains("email", true) || value.contains('@') && !value.startsWith("@") ->
            email(value)?.let { "email:$it" }
        kind.contains("Ник", true) || kind.contains("профиль", true) && value.contains('/') ->
            handle(value)?.let { "handle:$it" }
        kind.contains("Домен", true) || kind.contains("сайт", true) ->
            domain(value)?.let { "domain:$it" }
        kind.contains("Имя", true) -> name(value).takeIf { it.isNotBlank() }?.let { "name:$it" }
        else -> null
    }

    /**
     * Зацепки для следующего прохода. Берутся только точные идентификаторы
     * (почта, телефон, ник, домен) — имена и адреса страниц не годятся:
     * по ним поиск размывается и тратит запросы.
     */
    fun pivots(
        facts: List<Fact>,
        exclude: Set<String> = emptySet(),
        limit: Int = 3
    ): List<Pivot> {
        val out = LinkedHashMap<String, Pivot>()
        facts.forEach { fact ->
            if (fact.value.isBlank()) return@forEach
            val pivot = when {
                fact.category == FactCategory.CONTACT && fact.value.contains('@') &&
                    !fact.value.startsWith("@") -> email(fact.value)?.let { Pivot("EMAIL", it, it) }
                fact.category == FactCategory.CONTACT || fact.kind.contains("Телефон", true) ->
                    phone(fact.value)?.let { Pivot("PHONE", it, it) }
                fact.kind.contains("Почт", true) -> email(fact.value)?.let { Pivot("EMAIL", it, it) }
                fact.category == FactCategory.PERSON && (fact.value.startsWith("@") || fact.kind.contains("Ник", true)) ->
                    handle(fact.value)?.let { Pivot("USERNAME", it, "@$it") }
                fact.kind.contains("Домен", true) -> domain(fact.value)?.let { Pivot("DOMAIN", it, it) }
                else -> null
            } ?: return@forEach
            if (pivot.key in exclude) return@forEach
            out.putIfAbsent(pivot.key, pivot)
        }
        return out.values
            .sortedByDescending { pivot -> facts.count { key(it.category, it.kind, it.value) == "${pivot.kind.lowercase()}:${pivot.value.lowercase()}" } }
            .take(limit)
    }
}
