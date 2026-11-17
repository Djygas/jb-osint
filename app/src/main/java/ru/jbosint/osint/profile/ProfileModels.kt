package ru.jbosint.osint.profile

import ru.jbosint.osint.core.model.Confidence

/**
 * Модель единого профиля объекта исследования.
 *
 * Профиль собирается из всех модулей приложения: обычного поиска (факты),
 * Telegram-парсера (карточка канала и участники), TeleSINT (карточки людей,
 * история имён, связи) и импортированной выгрузки сообщений. Каждый блок
 * знает, есть ли по нему данные: если модули ничего не вернули, блок попадает
 * в [ProfileData.missing] и в интерфейсе показывается честная заглушка
 * «Данные по этому параметру не найдены».
 */

/** Состояние аккаунта в источнике. */
enum class ProfileStatus(val title: String) {
    ACTIVE("Активен"),
    BLOCKED("Заблокирован"),
    DELETED("Удалён"),
    UNKNOWN("Не определён");
}

/** Тип медиа в сообщении — используется и в фильтрах, и в статистике. */
enum class MsgType(val title: String) {
    TEXT("Текст"),
    PHOTO("Фото"),
    VIDEO("Видео"),
    VOICE("Голосовое"),
    ROUND("Кружок"),
    STICKER("Стикер"),
    FILE("Файл"),
    LINK("Ссылка");

    companion object {
        fun from(raw: String): MsgType = when (raw.trim().lowercase()) {
            "photo", "image", "фото", "picture" -> PHOTO
            "video", "видео", "видеосообщение" -> VIDEO
            "voice", "голосовое", "audio" -> VOICE
            "round", "кружок", "video_note", "video note" -> ROUND
            "sticker", "стикер" -> STICKER
            "file", "document", "файл", "документ" -> FILE
            "link", "ссылка", "url" -> LINK
            else -> TEXT
        }
    }
}

/** Одно сообщение объекта или его окружения (из парсера либо из импорта CSV). */
data class ProfileMessage(
    val at: Long,
    val author: String,
    val text: String,
    val type: MsgType = MsgType.TEXT,
    /** Кому отвечали (для блока цитирований), пусто — обычное сообщение. */
    val replyTo: String = "",
    val url: String = ""
) {
    val isOutgoing: Boolean get() = replyTo.isNotBlank()
}

/** Карточка объекта в верхней панели. */
data class ProfileSubject(
    val title: String,
    val username: String,
    val id: String,
    val link: String,
    val avatar: String = "",
    val status: ProfileStatus = ProfileStatus.UNKNOWN,
    val platform: String = "Telegram",
    val premium: Boolean = false,
    val bot: Boolean = false,
    val subtitle: String = ""
)

/** Найденный идентификатор: ник, ID, телефон, почта, домен. */
data class ProfileIdentity(
    val kind: String,
    val value: String,
    val platform: String,
    val evidence: String = "",
    val confidence: Confidence = Confidence.UNVERIFIED,
    val copyable: Boolean = true
)

/** Смена имени/ника с датой (если источник вернул историю). */
data class NameHistoryItem(val date: String, val name: String)

/** Узел графа связей. */
data class GraphNode(
    val id: String,
    val label: String,
    val weight: Int = 1,
    val center: Boolean = false,
    val bot: Boolean = false
)

/** Ребро графа связей: кто с кем и как часто. */
data class GraphEdge(
    val from: String,
    val to: String,
    val weight: Int = 1,
    val relation: String = "общение"
)

/** Контакт в блоке «Круг общения». */
data class ProfileContact(
    val name: String,
    val username: String,
    val id: String,
    val replies: Int = 0,
    val messages: Int = 0,
    val lastAt: Long = 0L,
    val commonGroups: List<String> = emptyList(),
    val bot: Boolean = false,
    val link: String = ""
)

/** Ключевые метрики блока «Сводка». */
data class ProfileSummary(
    val messages: Int = 0,
    val posts: Int = 0,
    val comments: Int = 0,
    val replies: Int = 0,
    val media: Int = 0,
    val from: Long = 0L,
    val to: Long = 0L,
    val geo: String = "",
    val score: Int = 0,
    val contacts: Int = 0,
    val groups: Int = 0
) {
    val hasRange: Boolean get() = from > 0L && to > 0L
}

/** Доли типов контента в процентах (сумма <= 100). */
data class ContentMix(
    val replies: Int = 0,
    val media: Int = 0,
    val rounds: Int = 0,
    val voice: Int = 0,
    val text: Int = 0,
    val links: Int = 0
)

/** Тепловая карта активности: 7 дней × 24 часа. */
data class Heatmap(
    val cells: Array<IntArray> = Array(7) { IntArray(24) },
    val max: Int = 0,
    val nightShare: Int = 0,
    val peakHour: Int = -1,
    val avgGapMinutes: Int = 0,
    val longestPauseHours: Int = 0
) {
    fun value(day: Int, hour: Int): Int = cells[day][hour]
}

/** Тональность сообщений по словарю. */
data class Sentiment(
    val positive: Int = 0,
    val negative: Int = 0,
    val neutral: Int = 0
) {
    val total: Int get() = positive + negative + neutral
    val tone: String get() = when {
        total == 0 -> "нет данных"
        negative * 100 / total > 45 -> "негативный"
        positive * 100 / total > 45 -> "позитивный"
        else -> "нейтральный"
    }
}

/** Тема сообщений (кластеризация по ключевым словам). */
data class TopicSlice(val name: String, val count: Int, val share: Int)

/** Слово для облака. */
data class WordWeight(val word: String, val count: Int, val share: Int)

/** Ссылка из переписки. */
data class LinkRow(val url: String, val domain: String, val count: Int, val title: String = "")

/** Медиа-метаданные/агрегаты. */
data class MediaRow(val type: MsgType, val count: Int, val note: String = "")

/** Совпадение никнейма на внешней площадке. */
data class ExternalHit(
    val platform: String,
    val handle: String,
    val url: String,
    val found: Boolean,
    val source: String = ""
)

/** Репутационный скоринг: 0..100, больше — хуже для токсичности и спама. */
data class Reputation(
    val toxicity: Int = 0,
    val spam: Int = 0,
    val trust: Int = 0,
    val notes: List<String> = emptyList()
)

/** Блок «Поведенческий анализ». */
data class BehaviorBlock(
    val heatmap: Heatmap = Heatmap(),
    val sentiment: Sentiment = Sentiment(),
    val topics: List<TopicSlice> = emptyList(),
    val hasData: Boolean = false
)

/** Блок «Контент-анализ». */
data class ContentBlock(
    val words: List<WordWeight> = emptyList(),
    val links: List<LinkRow> = emptyList(),
    val media: List<MediaRow> = emptyList(),
    val exif: List<String> = emptyList(),
    val transcription: String = "",
    val hasData: Boolean = false
)

/** Полный профиль. */
data class ProfileData(
    val id: String,
    val query: String,
    val subject: ProfileSubject,
    val summary: ProfileSummary,
    val mix: ContentMix,
    val identifiers: List<ProfileIdentity> = emptyList(),
    val names: List<NameHistoryItem> = emptyList(),
    val contacts: List<ProfileContact> = emptyList(),
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val groups: List<String> = emptyList(),
    val behavior: BehaviorBlock = BehaviorBlock(),
    val content: ContentBlock = ContentBlock(),
    val external: List<ExternalHit> = emptyList(),
    val reputation: Reputation = Reputation(),
    val messages: List<ProfileMessage> = emptyList(),
    val sources: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val builtAt: Long = System.currentTimeMillis(),
    val demo: Boolean = false
) {
    val hasMessages: Boolean get() = messages.isNotEmpty()

    /** Оценка полноты профиля в процентах — для бейджа в карточке. */
    val completeness: Int
        get() {
            val total = 9
            val filled = listOf(
                identifiers.isNotEmpty(),
                names.isNotEmpty(),
                contacts.isNotEmpty(),
                behavior.hasData,
                content.hasData,
                external.isNotEmpty(),
                groups.isNotEmpty(),
                reputation.notes.isNotEmpty(),
                hasMessages
            ).count { it }
            return filled * 100 / total
        }
}
