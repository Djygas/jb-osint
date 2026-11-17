package ru.jbosint.osint.profile

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.Link
import ru.jbosint.osint.data.HistoryEntry
import ru.jbosint.osint.data.TgDossier
import ru.jbosint.osint.data.TgExport
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Сборка единого профиля из всех источников приложения.
 *
 * Вход: снимок поиска (факты всех модулей), результат Telegram-парсера,
 * импортированная выгрузка сообщений. Выход: [ProfileData] — один объект,
 * который экран профиля рисует без дополнительных вычислений.
 *
 * Правило: ничего не выдумывать. Если данных нет — блок помечается как
 * отсутствующий ([ProfileData.missing]) и на экране показывается заглушка.
 */
object ProfileBuilder {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))

    fun build(
        entry: HistoryEntry?,
        dossier: TgDossier? = null,
        messages: List<ProfileMessage> = emptyList(),
        demo: Boolean = false,
        query: String = ""
    ): ProfileData {
        val data: AggregatedData? = when {
            dossier != null -> TgExport.data(dossier)
            entry != null -> entry.data
            else -> null
        }
        val facts = data?.facts.orEmpty()
        val queryText = query.ifBlank { entry?.query ?: dossier?.link ?: facts.firstOrNull()?.value ?: "" }

        val subject = subjectOf(queryText, facts, dossier)
        val sorted = messages.sortedBy { it.at }
        val identifiers = identifiersOf(facts)
        val names = nameHistoryOf(facts)
        val groups = groupsOf(facts, dossier)
        val contacts = contactsOf(sorted, facts, subject)
        val graph = graphOf(subject, contacts, data?.links.orEmpty())
        val behavior = behaviorOf(sorted)
        val content = contentOf(sorted, facts)
        val external = externalOf(subject, facts, identifiers)
        val reputation = reputationOf(subject, sorted, facts)
        val mix = mixOf(sorted)
        val summary = summaryOf(subject, sorted, contacts, groups, behavior, reputation, facts)

        val missing = ArrayList<String>()
        if (identifiers.isEmpty()) missing += "Идентификаторы"
        if (names.isEmpty()) missing += "История имён"
        if (contacts.isEmpty()) missing += "Круг общения"
        if (!behavior.hasData) missing += "Поведенческий анализ"
        if (!content.hasData) missing += "Контент-анализ"
        if (external.isEmpty()) missing += "Внешние связи"
        if (groups.isEmpty()) missing += "Общие группы"
        if (reputation.notes.isEmpty()) missing += "Репутационный скоринг"
        if (sorted.isEmpty()) missing += "Медиа и сообщения"

        return ProfileData(
            id = entry?.id ?: dossier?.channel ?: "profile_${System.currentTimeMillis()}",
            query = queryText,
            subject = subject,
            summary = summary,
            mix = mix,
            identifiers = identifiers,
            names = names,
            contacts = contacts,
            nodes = graph.first,
            edges = graph.second,
            groups = groups,
            behavior = behavior,
            content = content,
            external = external,
            reputation = reputation,
            messages = sorted,
            sources = data?.sources?.map { it.name }.orEmpty().ifEmpty {
                if (dossier != null) listOf("TeleSINT") else emptyList()
            },
            missing = missing,
            demo = demo
        )
    }

    // ------------------------------------------------------------ карточка

    private fun subjectOf(query: String, facts: List<Fact>, dossier: TgDossier?): ProfileSubject {
        val profile = facts.firstOrNull { it.kind == "Telegram-профиль" }
            ?: facts.firstOrNull { it.category == FactCategory.PERSON && it.value.startsWith("@") }
        val username = profile?.value?.trimStart('@')
            ?: query.removePrefix("https://t.me/").removePrefix("t.me/").trimStart('@').takeIf { it.startsWith("@") || it.matches(USERNAME) }.orEmpty()
                .trimStart('@')
        val fullName = facts.firstOrNull { it.kind == "Полное имя" }?.value
            ?: profile?.subject.orEmpty()
        val id = profile?.attributes?.get("ИД")
            ?: facts.firstOrNull { it.attributes["ИД"] != null && it.category == FactCategory.PERSON }?.attributes?.get("ИД")
            ?: ""
        val link = profile?.attributes?.get("Ссылка")
            ?: profile?.urls?.firstOrNull { it.contains("t.me") }
            ?: if (username.isNotBlank()) "https://t.me/$username" else dossier?.link.orEmpty()
        val status = statusOf(profile, facts)
        val premium = profile?.attributes?.get("Premium") == "да"
        val bot = profile?.attributes?.get("Бот") == "да"
        val title = fullName.ifBlank {
            if (username.isNotBlank()) "@$username" else dossier?.title ?: query.ifBlank { "Объект" }
        }
        val subtitle = buildList {
            if (username.isNotBlank()) add("@$username")
            if (id.isNotBlank()) add("ID $id")
            if (premium) add("Premium")
            if (bot) add("бот")
        }.joinToString(" · ")
        return ProfileSubject(
            title = title,
            username = username,
            id = id,
            link = link,
            status = status,
            premium = premium,
            bot = bot,
            subtitle = subtitle.ifBlank { if (dossier != null) "канал ${dossier.title}" else "" }
        )
    }

    private fun statusOf(profile: Fact?, facts: List<Fact>): ProfileStatus {
        val raw = profile?.attributes?.get("Статус").orEmpty().lowercase()
        val deleted = profile?.attributes?.get("Удалён") == "да" ||
            facts.any { it.attributes["Статус"]?.contains("deleted", true) == true }
        return when {
            deleted || raw.contains("deleted") || raw.contains("удал") -> ProfileStatus.DELETED
            raw.contains("block") || raw.contains("заблок") || raw.contains("banned") -> ProfileStatus.BLOCKED
            profile != null || raw.contains("active") || raw.contains("актив") -> ProfileStatus.ACTIVE
            else -> ProfileStatus.UNKNOWN
        }
    }

    // ------------------------------------------------------------ идентификаторы

    private fun identifiersOf(facts: List<Fact>): List<ProfileIdentity> {
        val out = LinkedHashMap<String, ProfileIdentity>()
        fun add(kind: String, value: String, platform: String, evidence: String, confidence: Confidence) {
            if (value.isBlank()) return
            val key = "$kind|${value.lowercase()}"
            val existing = out[key]
            if (existing == null || existing.confidence.level < confidence.level) {
                out[key] = ProfileIdentity(kind, value, platform, evidence, confidence)
            }
        }
        facts.forEach { fact ->
            val platform = platformOf(fact)
            val evidence = fact.urls.firstOrNull().orEmpty()
            when {
                fact.kind.contains("Ник", true) || fact.value.startsWith("@") ->
                    add("Никнейм", fact.value, platform, evidence, fact.confidence)
                fact.kind.contains("Телефон", true) -> add("Телефон", fact.value, platform, evidence, fact.confidence)
                fact.kind.contains("Почт", true) || fact.value.contains("@") && !fact.value.startsWith("@") ->
                    add("Email", fact.value, platform, evidence, fact.confidence)
                fact.kind.contains("Полное имя", true) -> add("Имя", fact.value, platform, evidence, fact.confidence)
                fact.kind.contains("Домен", true) -> add("Домен", fact.value, platform, evidence, fact.confidence)
                fact.category == FactCategory.TECHNICAL && fact.value.isNotBlank() ->
                    add(fact.kind, fact.value, platform, evidence, fact.confidence)
            }
            fact.attributes["ИД"]?.takeIf { it.isNotBlank() }?.let {
                add("ID ${platform}", it, platform, evidence, fact.confidence)
            }
        }
        return out.values.toList()
    }

    private fun platformOf(fact: Fact): String = when {
        fact.sourceIds.contains("telesint") -> "Telegram"
        fact.sourceIds.contains("github") -> "GitHub"
        fact.sourceIds.contains("gitlab") -> "GitLab"
        fact.sourceIds.contains("vk") -> "VK"
        fact.urls.any { it.contains("github.com") } -> "GitHub"
        fact.urls.any { it.contains("vk.com") } -> "VK"
        fact.urls.any { it.contains("t.me") } -> "Telegram"
        else -> fact.sourceIds.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "OSINT"
    }

    // ------------------------------------------------------------ история имён

    private fun nameHistoryOf(facts: List<Fact>): List<NameHistoryItem> {
        val raw = facts.firstNotNullOfOrNull { it.attributes["История имён"] } ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val parts = raw.split("→", "->", ";").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        return parts.map { part ->
            val match = Regex("^(\\d{4}-\\d{2}-\\d{2}|\\d{2}\\.\\d{2}\\.\\d{4})\\s*[:—–-]?\\s*(.+)$").find(part)
            if (match != null) NameHistoryItem(match.groupValues[1], match.groupValues[2].trim())
            else NameHistoryItem("", part)
        }
    }

    // ------------------------------------------------------------ группы

    private fun groupsOf(facts: List<Fact>, dossier: TgDossier?): List<String> {
        val set = LinkedHashSet<String>()
        facts.filter { it.kind == "Членство в канале" }.forEach { set += it.value }
        facts.filter { it.kind.startsWith("Telegram-канал") || it.kind.startsWith("Telegram-чат") }
            .forEach { set += it.value }
        if (dossier != null && dossier.title.isNotBlank()) set += dossier.title
        return set.toList()
    }

    // ------------------------------------------------------------ круг общения

    private fun contactsOf(messages: List<ProfileMessage>, facts: List<Fact>, subject: ProfileSubject): List<ProfileContact> {
        val out = LinkedHashMap<String, ProfileContact>()
        val self = listOf(subject.username, subject.title, subject.id)
            .filter { it.isNotBlank() }
            .map { it.trimStart('@').lowercase() }
        fun isSelf(name: String): Boolean {
            val clean = name.trim().trimStart('@').lowercase()
            return clean.isBlank() || clean == "я" || self.any { it == clean }
        }
        fun keyOf(name: String, username: String, id: String) =
            (username.ifBlank { id.ifBlank { name } }).lowercase()

        // Участники канала/профили из фактов — базовый круг общения.
        facts.filter { it.kind == "Участник Telegram" || it.kind == "Telegram-профиль" }.forEach { fact ->
            if (isSelf(fact.value) || isSelf(fact.attributes["Ник"].orEmpty())) return@forEach
            val username = fact.attributes["Ник"]?.trimStart('@').orEmpty()
            val id = fact.attributes["ИД"].orEmpty()
            val key = keyOf(fact.value, username, id)
            if (key.isBlank()) return@forEach
            val previous = out[key]
            out[key] = ProfileContact(
                name = previous?.name ?: fact.value,
                username = username.ifBlank { previous?.username.orEmpty() },
                id = id.ifBlank { previous?.id.orEmpty() },
                bot = fact.attributes["Бот"] == "да",
                link = fact.attributes["Ссылка"].orEmpty().ifBlank {
                    if (username.isNotBlank()) "https://t.me/$username" else ""
                }
            )
        }

        // Сообщения: кто писал и кому отвечали.
        messages.forEach { message ->
            val author = message.author.trim()
            if (!isSelf(author)) {
                val key = author.lowercase().trimStart('@')
                val previous = out[key]
                out[key] = (previous ?: ProfileContact(name = author, username = author.trimStart('@'), id = ""))
                    .copy(
                        messages = (previous?.messages ?: 0) + 1,
                        lastAt = max(previous?.lastAt ?: 0L, message.at)
                    )
            }
            val replyTo = message.replyTo.trim()
            if (replyTo.isNotBlank()) {
                val key = replyTo.lowercase().trimStart('@')
                val previous = out[key]
                out[key] = (previous ?: ProfileContact(name = replyTo, username = replyTo.trimStart('@'), id = ""))
                    .copy(replies = (previous?.replies ?: 0) + 1)
            }
        }
        return out.values
            .sortedWith(compareByDescending<ProfileContact> { it.replies + it.messages }.thenByDescending { it.lastAt })
            .take(60)
    }

    private fun graphOf(
        subject: ProfileSubject,
        contacts: List<ProfileContact>,
        links: List<Link>
    ): Pair<List<GraphNode>, List<GraphEdge>> {
        val centerId = subject.username.ifBlank { subject.id.ifBlank { subject.title } }
        if (centerId.isBlank()) return emptyList<GraphNode>() to emptyList()
        val nodes = ArrayList<GraphNode>()
        val edges = ArrayList<GraphEdge>()
        nodes += GraphNode(centerId, subject.title, max(1, contacts.size), center = true, bot = subject.bot)
        contacts.take(18).forEach { contact ->
            val id = contact.username.ifBlank { contact.id.ifBlank { contact.name } }
            if (id.isBlank()) return@forEach
            val weight = max(1, contact.replies + contact.messages)
            nodes += GraphNode(id, contact.name.take(24), weight, bot = contact.bot)
            edges += GraphEdge(centerId, id, weight, if (contact.replies > 0) "цитирование" else "общение")
        }
        // Связи между контактами из общего агрегатора.
        links.forEach { link ->
            val from = nodes.firstOrNull { it.id.equals(link.from, true) || it.label.equals(link.from, true) }?.id
                ?: return@forEach
            val to = nodes.firstOrNull { it.id.equals(link.to, true) || it.label.equals(link.to, true) }?.id
                ?: return@forEach
            if (from != to) edges += GraphEdge(from, to, max(1, link.weight), link.relation.ifBlank { "связь" })
        }
        return nodes to edges.distinctBy { it.from to it.to }.take(80)
    }

    // ------------------------------------------------------------ поведение

    private fun behaviorOf(messages: List<ProfileMessage>): BehaviorBlock {
        if (messages.isEmpty()) return BehaviorBlock()
        val cells = Array(7) { IntArray(24) }
        val calendar = Calendar.getInstance()
        var night = 0
        val dayCounts = IntArray(24)
        var minGap = Long.MAX_VALUE
        var longestPause = 0L
        var previous = 0L
        messages.forEach { message ->
            calendar.timeInMillis = message.at
            val day = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 0 = понедельник
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            cells[day][hour] = cells[day][hour] + 1
            dayCounts[hour] = dayCounts[hour] + 1
            if (hour in 0..5 || hour >= 23) night++
            if (previous > 0L) {
                val gap = message.at - previous
                if (gap in 1..(60L * 60 * 1000)) minGap = minOf(minGap, gap)
                if (gap > longestPause) longestPause = gap
            }
            previous = message.at
        }
        val maxCell = cells.maxOf { row -> row.max() }
        val peakHour = dayCounts.indices.maxByOrNull { dayCounts[it] } ?: -1
        return BehaviorBlock(
            heatmap = Heatmap(
                cells = cells,
                max = maxCell,
                nightShare = night * 100 / messages.size,
                peakHour = peakHour,
                avgGapMinutes = if (minGap == Long.MAX_VALUE) 0 else (minGap / 60000.0).roundToInt(),
                longestPauseHours = (longestPause / 3600000.0).roundToInt()
            ),
            sentiment = sentimentOf(messages),
            topics = topicsOf(messages),
            hasData = true
        )
    }

    private fun sentimentOf(messages: List<ProfileMessage>): Sentiment {
        var positive = 0
        var negative = 0
        var neutral = 0
        messages.forEach { message ->
            val text = message.text.lowercase()
            if (text.isBlank()) return@forEach
            val pos = POSITIVE.count { text.contains(it) }
            val neg = NEGATIVE.count { text.contains(it) }
            when {
                neg > pos -> negative++
                pos > neg -> positive++
                else -> neutral++
            }
        }
        return Sentiment(positive, negative, neutral)
    }

    private fun topicsOf(messages: List<ProfileMessage>): List<TopicSlice> {
        val counts = LinkedHashMap<String, Int>()
        messages.forEach { message ->
            val text = message.text.lowercase()
            if (text.isBlank()) return@forEach
            val topic = TOPICS.entries.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.key
            if (topic != null) counts[topic] = (counts[topic] ?: 0) + 1
        }
        if (counts.isEmpty()) return emptyList()
        val total = counts.values.sum().coerceAtLeast(1)
        return counts.entries
            .sortedByDescending { it.value }
            .map { TopicSlice(it.key, it.value, it.value * 100 / total) }
    }

    // ------------------------------------------------------------ контент

    private fun contentOf(messages: List<ProfileMessage>, facts: List<Fact>): ContentBlock {
        val mediaCounts = messages.groupingBy { it.type }.eachCount()
        val media = mediaCounts.entries
            .sortedByDescending { it.value }
            .map { MediaRow(it.key, it.value) }
        val words = wordCloudOf(messages)
        val links = linksOf(messages)
        val exif = facts.mapNotNull { fact ->
            fact.attributes["EXIF"]?.takeIf { it.isNotBlank() }?.let { "${fact.value}: $it" }
        }
        return ContentBlock(
            words = words,
            links = links,
            media = media,
            exif = exif,
            transcription = "",
            hasData = messages.isNotEmpty() && (words.isNotEmpty() || links.isNotEmpty() || media.isNotEmpty())
        )
    }

    private fun wordCloudOf(messages: List<ProfileMessage>): List<WordWeight> {
        val counts = LinkedHashMap<String, Int>()
        messages.forEach { message ->
            TOKEN.findAll(message.text.lowercase()).forEach { match ->
                val word = match.value
                if (word.length < 4 || word in STOP_WORDS || word.all { it.isDigit() }) return@forEach
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
        if (counts.isEmpty()) return emptyList()
        val top = counts.entries.sortedByDescending { it.value }.take(30)
        val maxCount = top.first().value.coerceAtLeast(1)
        return top.map { WordWeight(it.key, it.value, it.value * 100 / maxCount) }
    }

    private fun linksOf(messages: List<ProfileMessage>): List<LinkRow> {
        val counts = LinkedHashMap<String, Int>()
        messages.forEach { message ->
            URL.findAll(message.text).forEach { match ->
                val url = match.value.trimEnd('.', ',', ')', ';', '»', '"')
                counts[url] = (counts[url] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(40)
            .map { (url, count) -> LinkRow(url, domainOf(url), count) }
    }

    private fun domainOf(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.").orEmpty().ifBlank { url }
    }.getOrDefault(url)

    // ------------------------------------------------------------ внешние связи

    private fun externalOf(
        subject: ProfileSubject,
        facts: List<Fact>,
        identifiers: List<ProfileIdentity>
    ): List<ExternalHit> {
        val out = LinkedHashMap<String, ExternalHit>()
        fun platformOfUrl(url: String): String? = when {
            url.contains("github.com") -> "GitHub"
            url.contains("gitlab.com") -> "GitLab"
            url.contains("vk.com") -> "VK"
            url.contains("instagram.com") -> "Instagram"
            url.contains("twitter.com") || url.contains("x.com") -> "X"
            url.contains("t.me") -> "Telegram"
            url.contains("ok.ru") -> "OK"
            url.contains("facebook.com") -> "Facebook"
            else -> null
        }
        facts.forEach { fact ->
            fact.urls.forEach { url ->
                val platform = platformOfUrl(url) ?: return@forEach
                out[platform] = ExternalHit(platform, fact.value, url, true, fact.kind)
            }
        }
        // Готовые точки проверки ника на других площадках (без запросов — открываются вручную).
        val handle = subject.username.ifBlank { identifiers.firstOrNull { it.kind == "Никнейм" }?.value?.trimStart('@').orEmpty() }
        if (handle.isNotBlank()) {
            CHECK_PLATFORMS.forEach { (platform, template) ->
                if (out[platform] == null) {
                    out[platform] = ExternalHit(platform, handle, template.replace("{q}", handle), false, "ссылка для проверки")
                }
            }
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------ репутация

    private fun reputationOf(
        subject: ProfileSubject,
        messages: List<ProfileMessage>,
        facts: List<Fact>
    ): Reputation {
        if (messages.isEmpty() && facts.isEmpty()) return Reputation()
        val notes = ArrayList<String>()
        val total = messages.size.coerceAtLeast(1)

        val toxicHits = messages.count { comment ->
            val text = comment.text.lowercase()
            TOXIC_WORDS.any { text.contains(it) }
        }
        val toxicity = if (messages.isEmpty()) 0 else (toxicHits * 100.0 / total).roundToInt()

        val texts = messages.map { it.text.trim().lowercase() }.filter { it.length > 12 }
        val repeats = texts.groupingBy { it }.eachCount().values.count { it > 2 }
        val spam = if (texts.isEmpty()) 0 else (repeats * 100.0 / texts.size).roundToInt()

        var trust = 100
        if (toxicity > 30) { trust -= 25; notes += "много агрессивных сообщений" }
        if (spam > 20) { trust -= 25; notes += "повторяющиеся сообщения — признак рассылки" }
        if (subject.status == ProfileStatus.BLOCKED || subject.status == ProfileStatus.DELETED) {
            trust -= 30
            notes += "аккаунт заблокирован или удалён"
        }
        if (subject.bot) { trust -= 40; notes += "аккаунт помечен как бот" }
        if (facts.any { it.attributes["Признак скама"] == "да" }) { trust -= 30; notes += "источник отметил признак скама" }
        if (facts.any { it.attributes["Признак фейка"] == "да" }) { trust -= 15; notes += "источник отметил признак фейка" }
        if (facts.any { it.attributes["Premium"] == "да" }) { trust += 5; notes += "Telegram Premium" }
        val id = subject.id.toLongOrNull()
        if (id != null && id > 6_000_000_000L) { trust -= 10; notes += "аккаунт создан недавно (высокий ID)" }
        if (messages.isEmpty()) notes += "нет выгрузки сообщений — скоринг по метаданным"
        trust = trust.coerceIn(0, 100)
        if (notes.isEmpty()) notes += "признаков токсичности и спама не найдено"
        return Reputation(toxicity = toxicity, spam = spam, trust = trust, notes = notes)
    }

    // ------------------------------------------------------------ сводка

    private fun mixOf(messages: List<ProfileMessage>): ContentMix {
        if (messages.isEmpty()) return ContentMix()
        val total = messages.size
        fun share(count: Int) = (count * 100.0 / total).roundToInt()
        val replies = messages.count { it.replyTo.isNotBlank() }
        val media = messages.count {
            it.type == MsgType.PHOTO || it.type == MsgType.VIDEO || it.type == MsgType.FILE || it.type == MsgType.STICKER
        }
        val rounds = messages.count { it.type == MsgType.ROUND }
        val voice = messages.count { it.type == MsgType.VOICE }
        val links = messages.count { it.type == MsgType.LINK || URL.containsMatchIn(it.text) }
        val text = messages.count { it.type == MsgType.TEXT && !URL.containsMatchIn(it.text) }
        return ContentMix(share(replies), share(media), share(rounds), share(voice), share(text), share(links))
    }

    private fun summaryOf(
        subject: ProfileSubject,
        messages: List<ProfileMessage>,
        contacts: List<ProfileContact>,
        groups: List<String>,
        behavior: BehaviorBlock,
        reputation: Reputation,
        facts: List<Fact>
    ): ProfileSummary {
        val geo = facts.filter { it.category == FactCategory.LOCATION }
            .map { it.value }
            .distinct()
            .take(2)
            .joinToString(", ")
        val score = when {
            messages.isEmpty() -> reputation.trust
            else -> (reputation.trust - reputation.toxicity / 4 - reputation.spam / 5).coerceIn(0, 100)
        }
        return ProfileSummary(
            messages = messages.size,
            posts = messages.count { it.type != MsgType.TEXT },
            comments = messages.count { it.replyTo.isNotBlank() },
            replies = messages.count { it.replyTo.isNotBlank() },
            media = messages.count { it.type != MsgType.TEXT },
            from = messages.minOfOrNull { it.at } ?: 0L,
            to = messages.maxOfOrNull { it.at } ?: 0L,
            geo = geo,
            score = score,
            contacts = contacts.size,
            groups = groups.size
        )
    }

    fun dateText(at: Long): String = if (at <= 0L) "" else dateFormat.format(Date(at))
    fun timeText(at: Long): String = if (at <= 0L) "" else timeFormat.format(Date(at))

    /** Возраст в днях между двумя отметками. */
    fun daysBetween(from: Long, to: Long): Long =
        if (from <= 0L || to <= 0L) 0 else abs(to - from) / 86_400_000L

    private val USERNAME = Regex("[A-Za-z0-9_]{4,32}")
    private val TOKEN = Regex("[\\p{L}][\\p{L}\\d_-]{3,}")
    private val URL = Regex("https?://[^\\s<>\"']+")

    private val CHECK_PLATFORMS = listOf(
        "GitHub" to "https://github.com/{q}",
        "VK" to "https://vk.com/{q}",
        "Instagram" to "https://instagram.com/{q}",
        "X" to "https://x.com/{q}",
        "TikTok" to "https://tiktok.com/@{q}",
        "OK" to "https://ok.ru/{q}",
        "GitLab" to "https://gitlab.com/{q}"
    )

    private val POSITIVE = listOf(
        "спасибо", "благодар", "отлично", "супер", "класс", "круто", "рад", "нравится", "люблю",
        "хорошо", "полезно", "согласен", "поздравляю", "удачи", "приятно", "интересно", "рекомендую"
    )

    private val NEGATIVE = listOf(
        "плохо", "ужас", "ненавижу", "дурак", "идиот", "тупой", "бред", "развод", "обман", "скам",
        "ненависть", "достал", "надоел", "проблема", "ошибка", "сломал", "не работает", "жалоб"
    )

    private val TOXIC_WORDS = listOf(
        "дурак", "идиот", "тупой", "дебил", "кретин", "мразь", "сдохни", "убей", "ненавижу",
        "заткнись", "пошёл ты", "пошел ты", "тварь", "урод", "дегенерат", "придурок"
    )

    private val TOPICS = linkedMapOf(
        "Работа и деньги" to listOf("работ", "зарплат", "проект", "клиент", "заказ", "деньг", "оплат", "бизнес", "созвон", "вакансия"),
        "Технологии" to listOf("код", "сервер", "программ", "прилож", "андроид", "телефон", "айфон", "интернет", "баг", "api", "vpn", "подписк"),
        "Личное" to listOf("семья", "жена", "муж", "дом", "дет", "отпуск", "устал", "настроен", "чувств", "друг"),
        "Новости и политика" to listOf("новост", "войн", "политик", "власт", "закон", "курс", "санкц", "выбор"),
        "Мемы и юмор" to listOf("мем", "смешн", "шутк", "ахах", "хаха", "прикол", "лол")
    )

    private val STOP_WORDS = setOf(
        "этот", "это", "чтобы", "когда", "только", "также", "очень", "просто", "вообще", "который",
        "которая", "которые", "почему", "потому", "может", "можно", "нужно", "надо", "если", "есть",
        "было", "были", "будет", "будут", "меня", "тебя", "себя", "них", "нами", "вами", "свой",
        "свои", "свое", "такой", "такая", "такие", "здесь", "там", "тогда", "сейчас", "сегодня",
        "вчера", "завтра", "пока", "уже", "ещё", "еще", "или", "либо", "через", "после", "перед",
        "между", "этому", "этого", "этом", "всем", "всех", "весь", "вся", "всё", "все", "что",
        "как", "для", "при", "без", "над", "под", "про", "эта", "эти", "тот", "те", "да", "нет",
        "привет", "спасибо", "пожалуйста", "хорошо", "ладно", "ясно", "конечно", "кстати", "воть"
    )
}
