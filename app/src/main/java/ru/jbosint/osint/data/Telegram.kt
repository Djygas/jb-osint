package ru.jbosint.osint.data

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.core.model.SourceRef
import ru.jbosint.osint.core.model.SearchStats
import ru.jbosint.osint.core.report.Csv

data class TgDossier(
    val input: String,
    val kind: TelegramRules.Kind,
    val title: String,
    val username: String,
    val facts: List<RawFact>,
    val members: List<TeleSint.Member>,
    val pagesLoaded: Int,
    val hasMore: Boolean,
    val backends: List<String>,
    val warnings: List<String> = emptyList(),
    val collectedAt: Long = System.currentTimeMillis()
) {
    val truncated: Boolean get() = hasMore
    val link: String get() = when {
        username.isNotBlank() -> "https://t.me/$username"
        input.startsWith("http") -> input
        else -> input
    }
}

object TgParser {
    const val DEFAULT_MAX_PAGES = 3

    suspend fun collect(
        telesintKey: String,
        telelogKey: String,
        funstatKey: String,
        input: String,
        kindHint: TelegramRules.Kind? = null,
        maxPages: Int = DEFAULT_MAX_PAGES,
        pageSize: Int = 200,
        onProgress: (String) -> Unit = {}
    ): TgDossier {
        val parsed = TelegramRules.parse(input, kindHint)
        if (!parsed.valid) throw TeleSint.ApiException(400, parsed.error.ifBlank { "Неверный ввод" })
        if (telesintKey.isBlank() && telelogKey.isBlank() && funstatKey.isBlank()) {
            throw TeleSint.ApiException(401, "Добавьте ключ TeleSINT, Telelog или Funstat в разделе «Модули»")
        }

        val facts = ArrayList<RawFact>()
        val warnings = ArrayList<String>()
        val backends = ArrayList<String>()
        val members = ArrayList<TeleSint.Member>()
        var pagesLoaded = 0
        var hasMore = false
        val target = parsed.username.ifBlank { parsed.numericId.ifBlank { parsed.normalized } }

        suspend fun <T> runBackend(name: String, block: suspend () -> T): Result<T> {
            val started = System.currentTimeMillis()
            return runCatching { block() }.also { r ->
                ModuleRunLog.add(ModuleRunEntry(moduleId = name.lowercase(), action = "telegram_parse", ok = r.isSuccess,
                    durationMs = System.currentTimeMillis() - started,
                    facts = (r.getOrNull() as? List<*>)?.size ?: (r.getOrNull() as? TsRes)?.facts?.size ?: 0,
                    message = r.exceptionOrNull()?.message.orEmpty()))
            }
        }
        if (telesintKey.isNotBlank()) {
            onProgress("TeleSINT: запрос…")
            runBackend("TeleSINT") { collectTeleSint(telesintKey, parsed, maxPages, pageSize, onProgress) }
                .onSuccess { res -> facts += res.facts; members += res.members; pagesLoaded = maxOf(pagesLoaded, res.pagesLoaded); hasMore = hasMore || res.hasMore; backends += "TeleSINT" }
                .onFailure { e -> warnings += "TeleSINT: ${e.message ?: "ошибка"}" }
        }
        if (telelogKey.isNotBlank()) {
            onProgress("Telelog: запрос…")
            runBackend("Telelog") { TelelogLike.queryDetailed("telelog", telelogKey, parsed) }
                .onSuccess { res -> facts += res.facts; warnings += res.warnings; if (res.facts.isNotEmpty()) backends += "Telelog" }
                .onFailure { e -> warnings += "Telelog: ${e.message ?: "ошибка"}" }
        }
        if (funstatKey.isNotBlank()) {
            onProgress("Funstat: запрос…")
            runBackend("Funstat") { TelelogLike.queryDetailed("funstat", funstatKey, parsed) }
                .onSuccess { res -> facts += res.facts; warnings += res.warnings; if (res.facts.isNotEmpty()) backends += "Funstat" }
                .onFailure { e -> warnings += "Funstat: ${e.message ?: "ошибка"}" }
        }

        if (facts.isEmpty() && members.isEmpty()) {
            val msg = warnings.firstOrNull() ?: "Нет данных по запросу. Проверьте ключи и формат ввода."
            throw TeleSint.ApiException(404, msg)
        }

        facts += privacyFacts(parsed)
        val uniqueMembers = members.distinctBy { it.id.ifBlank { it.username.ifBlank { it.displayName } } }
        uniqueMembers.forEach { m ->
            facts += RawFact(
                category = FactCategory.PERSON,
                kind = "Участник Telegram",
                value = m.displayName,
                subject = target,
                attributes = buildMap {
                    if (m.username.isNotBlank()) put("Ник", "@${m.username}")
                    if (m.id.isNotBlank()) put("ИД", m.id)
                    if (m.phone.isNotBlank()) put("Телефон", m.phone)
                    if (m.lastOnline.isNotBlank()) put("Последняя активность", m.lastOnline)
                    if (m.premium) put("Premium", "да")
                    if (m.bot) put("Бот", "да")
                    if (m.username.isNotBlank()) put("Ссылка", "https://t.me/${m.username}")
                },
                sourceId = "telesint",
                weight = 1,
                observedAt = System.currentTimeMillis(),
                apiConfirmed = true
            )
        }

        val title = facts.firstOrNull { it.kind.startsWith("Telegram-") || it.kind == "Статистика чата" }?.value
            ?: parsed.username.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: parsed.normalized
        onProgress("Готово")
        return TgDossier(
            input = parsed.normalized,
            kind = parsed.kind,
            title = title,
            username = parsed.username,
            facts = facts.distinctBy { it.sourceId to it.kind to it.value },
            members = uniqueMembers,
            pagesLoaded = pagesLoaded,
            hasMore = hasMore,
            backends = backends.distinct(),
            warnings = warnings.distinct()
        )
    }

    private data class TsRes(val facts: List<RawFact>, val members: List<TeleSint.Member>, val pagesLoaded: Int, val hasMore: Boolean)

    private suspend fun collectTeleSint(
        key: String, parsed: TelegramRules.Result, maxPages: Int, pageSize: Int, onProgress: (String) -> Unit
    ): TsRes {
        val facts = ArrayList<RawFact>()
        val members = ArrayList<TeleSint.Member>()
        val id = parsed.username.ifBlank { parsed.numericId.ifBlank { parsed.normalized } }
        when (parsed.kind) {
            TelegramRules.Kind.USERNAME, TelegramRules.Kind.USER_ID, TelegramRules.Kind.CONTACT_OR_RECENT -> {
                facts += TeleSint.search(key, id, limit = 20)
                if (parsed.username.isNotBlank() || parsed.numericId.isNotBlank()) {
                    runCatching { TeleSint.user(key, id) }.onSuccess { facts += it }
                }
            }
            TelegramRules.Kind.GROUP, TelegramRules.Kind.GROUP_ID, TelegramRules.Kind.GROUP_STATS -> {
                runCatching { TeleSint.group(key, id) }.onSuccess { facts += it }
                var page = 1
                val limit = maxPages.coerceIn(1, 20)
                var hasMore = false
                while (page <= limit) {
                    onProgress("TeleSINT: участники $page/$limit…")
                    val chunk = TeleSint.members(key, id, page, pageSize)
                    if (chunk.members.isEmpty()) break
                    members += chunk.members
                    hasMore = chunk.hasNext
                    if (!hasMore) break
                    page++
                }
                return TsRes(facts, members, page, hasMore)
            }
            TelegramRules.Kind.MESSAGE_LINK, TelegramRules.Kind.FORWARDED_GROUP_MESSAGE, TelegramRules.Kind.CHANNEL_FORWARD_POST -> {
                if (parsed.username.isNotBlank() || parsed.numericId.isNotBlank()) {
                    runCatching { TeleSint.group(key, id) }.onSuccess { facts += it }
                }
                facts += TeleSint.search(key, parsed.normalized.take(120), limit = 20)
                facts += RawFact(FactCategory.OTHER, "Вход: сообщение/пересылка", parsed.normalized, id,
                    mapOf("Тип" to parsed.kind.name, "Комментарий" to "Точный разбор пересылок зависит от поддержки API бэкенда"),
                    "telesint", 1, apiConfirmed = false,
                    note = "Нужна подтверждённая спецификация API для точного разбора пересылок")
            }
            TelegramRules.Kind.STICKER -> {
                facts += RawFact(FactCategory.OTHER, "Вход: стикер", parsed.normalized, id,
                    mapOf("Комментарий" to "Поиск автора/контекста стикера выполняется через доступные API бэкенды"),
                    "telesint", 1, apiConfirmed = false,
                    note = "Нужна подтверждённая спецификация API для стикеров")
                facts += TeleSint.search(key, id, limit = 20)
            }
            TelegramRules.Kind.INVALID -> {}
        }
        return TsRes(facts, members, 0, false)
    }

    private fun privacyFacts(parsed: TelegramRules.Result): List<RawFact> {
        if (parsed.kind != TelegramRules.Kind.USERNAME && parsed.kind != TelegramRules.Kind.USER_ID) return emptyList()
        val subject = parsed.username.ifBlank { parsed.numericId }
        return listOf(
            RawFact(
                category = FactCategory.OTHER,
                kind = "Приватность аккаунта",
                value = "Уровни скрытия (по данным сервисов)",
                subject = subject,
                attributes = mapOf(
                    "Ниндзя" to "максимальное скрытие, если поддерживается сервисом",
                    "Тень" to "частичное скрытие, если поддерживается сервисом",
                    "Бесплатно (РФ)" to "базовый уровень, если поддерживается сервисом",
                    "Команда скрытия" to "выполняется через команды/настройки соответствующего бота/сервиса",
                    "Лимиты" to "зависят от тарифа и правил сервиса",
                    "Уведомления" to "о смене статуса/лимитов — по настройкам сервиса",
                    "Призрак на аукционе" to "статус/доступность зависит от сервиса; требуется подтверждённый API"
                ),
                sourceId = "telelog",
                weight = 0,
                apiConfirmed = false,
                note = "Приватность/скрытие/аукцион требуют подтверждённой API-спецификации сервиса"
            )
        )
    }
}

object TgExport {
    fun data(dossier: TgDossier): AggregatedData {
        val merged = LinkedHashMap<Pair<String, String>, Fact>()
        dossier.facts.forEach { raw ->
            val key = raw.kind to raw.value
            val existing = merged[key]
            val observed = if (raw.observedAt > 0L) raw.observedAt else dossier.collectedAt
            merged[key] = if (existing == null) {
                Fact(
                    category = raw.category, kind = raw.kind, value = raw.value,
                    subject = raw.subject.ifBlank { dossier.title }, attributes = raw.attributes,
                    sourceIds = setOf(raw.sourceId), observations = 1, firstSeen = observed, lastSeen = observed,
                    urls = setOfNotNull(raw.sourceUrl.takeIf { it.isNotBlank() }),
                    apiConfirmed = raw.apiConfirmed, notes = setOfNotNull(raw.note.takeIf { it.isNotBlank() })
                )
            } else existing.copy(
                attributes = existing.attributes + raw.attributes,
                sourceIds = existing.sourceIds + raw.sourceId,
                observations = existing.observations + 1,
                lastSeen = maxOf(existing.lastSeen, observed),
                urls = existing.urls + setOfNotNull(raw.sourceUrl.takeIf { it.isNotBlank() }),
                apiConfirmed = existing.apiConfirmed && raw.apiConfirmed,
                notes = existing.notes + setOfNotNull(raw.note.takeIf { it.isNotBlank() })
            )
        }
        val sources = dossier.backends.map {
            when (it) {
                "Telelog" -> SourceRef("telelog", "Telelog", "https://telelog.info")
                "Funstat" -> SourceRef("funstat", "Funstat", "https://funstat.info")
                else -> SourceRef("telesint", "TeleSINT", TeleSint.SITE)
            }
        }.ifEmpty { listOf(SourceRef("telesint", "TeleSINT", TeleSint.SITE)) }
        return AggregatedData(
            query = dossier.link,
            facts = merged.values.toList(),
            stats = SearchStats(dossier.backends.size.coerceAtLeast(1), dossier.backends.size, dossier.warnings.size, dossier.facts.size, dossier.facts.size - merged.size, 0),
            sources = sources
        )
    }

    fun csv(dossier: TgDossier): String {
        val rows = ArrayList<List<String>>()
        rows += listOf("ID", "Ник", "Имя", "Фамилия", "Телефон", "Premium", "Бот", "Последняя активность", "Ссылка")
        dossier.members.forEach { m ->
            rows += listOf(m.id, if (m.username.isNotBlank()) "@${m.username}" else "", m.firstName, m.lastName, m.phone,
                if (m.premium) "да" else "", if (m.bot) "да" else "", m.lastOnline,
                if (m.username.isNotBlank()) "https://t.me/${m.username}" else "")
        }
        if (dossier.members.isEmpty()) {
            rows.clear()
            rows += listOf("Тип", "Значение", "Источник", "Атрибуты")
            dossier.facts.forEach { f ->
                rows += listOf(f.kind, f.value, f.sourceId, f.attributes.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
        return Csv.BOM + Csv.of(rows)
    }

    fun fileName(dossier: TgDossier, extension: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(dossier.collectedAt))
        val safe = dossier.username.ifBlank { dossier.input }.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40).ifBlank { "telegram" }
        return "tg_${safe}_$stamp.$extension"
    }
}
