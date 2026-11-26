package ru.jbosint.osint.data

import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Разбор чужого отчёта (PDF/HTML/текст) на контакты.
 *
 * Частный детектив получает отчёты из внешних сервисов, где поиск шёл по ФИО и дате
 * рождения. Из них нужно вытащить почты, телефоны, ники и пары «ресурс — логин»,
 * чтобы прогнать их по базам утечек. Здесь только извлечение и нормализация:
 * никаких сетевых вызовов, поэтому разбор проверяется тестами на строках.
 */
object ContactsExtractor {

    data class Account(val resource: String, val login: String)

    data class Extracted(
        val emails: List<String> = emptyList(),
        val phones: List<String> = emptyList(),
        val usernames: List<String> = emptyList(),
        val names: List<String> = emptyList(),
        val dates: List<String> = emptyList(),
        val accounts: List<Account> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = emails.isEmpty() && phones.isEmpty() && usernames.isEmpty() && accounts.isEmpty()

        /** Зацепки для второго прохода: только точные идентификаторы. */
        fun pivots(): List<EntityResolver.Pivot> {
            val out = ArrayList<EntityResolver.Pivot>()
            emails.forEach { out += EntityResolver.Pivot("EMAIL", it, it) }
            phones.forEach { out += EntityResolver.Pivot("PHONE", it, it) }
            usernames.forEach { out += EntityResolver.Pivot("USERNAME", it, "@$it") }
            return out.distinctBy { it.key }
        }
    }

    private val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val phone = Regex("(?:\\+7|8)[\\s(\\-]*\\d{3}[\\s)\\-]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2}")
    private val usernameLine = Regex(
        "(?:[Лл]огин|[Uu]sername|[Лл]огин/пароль|[Нн]ик|[Nn]ick|[Aa]ccount)\\s*[:=—–-]\\s*@?([A-Za-z0-9_.\\-]{2,32})"
    )
    private val atHandle = Regex("(?<![A-Za-z0-9._%+\\-])@([A-Za-z0-9_.\\-]{3,32})")
    private val isoDate = Regex("\\b(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])\\b")
    private val ruDate = Regex("\\b(0?[1-9]|[12]\\d|3[01])[.\\-/](0?[1-9]|1[0-2])[.\\-/](19|20)\\d{2}\\b")
    private val nameLine = Regex(
        "(?:[Фф][Ии][Оо]|[Фф]амилия\\s+[Ии]мя|[Ии]мя|[Nn]ame)\\s*[:=—–-]\\s*([А-ЯЁ][а-яё\\-]+(?:\\s+[А-ЯЁ][а-яё\\-]+){1,2})"
    )
    private val accountLine = Regex(
        "(?m)^\\s*([a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)*\\.[a-z]{2,})\\s*[:—–\\-]\\s*([A-Za-z0-9_.\\-@+]{3,64})\\s*$"
    )

    fun extract(text: String, limit: Int = 60): Extracted {
        if (text.isBlank()) return Extracted()

        val emails = LinkedHashSet<String>()
        email.findAll(text).forEach { match ->
            EntityResolver.email(match.value)?.let { emails += it }
        }

        val phones = LinkedHashSet<String>()
        phone.findAll(text).forEach { match ->
            EntityResolver.phone(match.value)?.let { phones += it }
        }

        val usernames = LinkedHashSet<String>()
        usernameLine.findAll(text).forEach { match ->
            EntityResolver.handle(match.groupValues[1])?.let { usernames += it }
        }
        atHandle.findAll(text).forEach { match ->
            EntityResolver.handle(match.groupValues[1])?.let { usernames += it }
        }

        val names = LinkedHashSet<String>()
        nameLine.findAll(text).forEach { match -> names += match.groupValues[1].trim() }

        val dates = LinkedHashSet<String>()
        isoDate.findAll(text).forEach { dates += it.value }
        ruDate.findAll(text).forEach { dates += it.value }

        val accounts = LinkedHashMap<String, Account>()
        accountLine.findAll(text).forEach { match ->
            val resource = match.groupValues[1].lowercase()
            val login = match.groupValues[2].trim()
            if (login.length < 3) return@forEach
            accounts.putIfAbsent("$resource|${login.lowercase()}", Account(resource, login))
        }

        return Extracted(
            emails = emails.take(limit),
            phones = phones.take(limit),
            usernames = usernames.take(limit),
            names = names.take(limit),
            dates = dates.take(limit),
            accounts = accounts.values.take(limit)
        )
    }

    /** Контакты из отчёта в виде фактов — чтобы они попали в общий список результатов. */
    fun toFacts(extracted: Extracted, source: String = "import"): List<RawFact> {
        val facts = ArrayList<RawFact>()
        extracted.emails.forEach { value ->
            facts += RawFact(
                category = FactCategory.CONTACT,
                kind = "Почта",
                value = value,
                subject = "Отчёт",
                sourceId = source
            )
        }
        extracted.phones.forEach { value ->
            facts += RawFact(
                category = FactCategory.CONTACT,
                kind = "Телефон",
                value = value,
                subject = "Отчёт",
                sourceId = source
            )
        }
        extracted.usernames.forEach { value ->
            facts += RawFact(
                category = FactCategory.PERSON,
                kind = "Никнейм",
                value = "@$value",
                subject = "Отчёт",
                sourceId = source
            )
        }
        return facts
    }
}
