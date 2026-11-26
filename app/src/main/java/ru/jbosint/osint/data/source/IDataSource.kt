package ru.jbosint.osint.data.source

import ru.jbosint.osint.core.model.RawFact
import ru.jbosint.osint.data.Connector
import ru.jbosint.osint.data.Connectors
import ru.jbosint.osint.data.KeySource
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.QueryKind

/**
 * Смысловые типы данных, по которым источники умеют искать.
 * Используется, чтобы экран/агент запускал только релевантные модули.
 */
enum class SourceType(val title: String) {
    PERSON("Личность"),
    CONTACT("Контакты"),
    TRANSPORT("Транспорт"),
    SOCIAL("Социальные сети"),
    DOCUMENT("Документы"),
    LEAK("Утечки"),
    REALTY("Недвижимость"),
    COMPANY("Юрлица"),
    TECHNICAL("Онлайн-следы")
}

/** Параметры одного запроса. Тип определяется автоматически по значению. */
data class SearchParams(val query: String) {
    val kind: QueryKind = QueryDetector.detect(query)
}

/**
 * Описание источника для экрана настроек и отчёта.
 * [officialApi] = true только для официальных API; такие источники разрешены
 * политикой приложения, в отличие от скрейпинга закрытых страниц.
 */
data class SourceInfo(
    val id: String,
    val title: String,
    val types: Set<SourceType>,
    val description: String = "",
    val keyless: Boolean = false,
    val officialApi: Boolean = true
)

/**
 * Единый контракт источника данных: supports(type), search(params), info().
 * Модули подключаются по конфигу и не знают друг о друге.
 */
interface IDataSource {
    val info: SourceInfo

    /** Умеет ли источник отвечать на этот смысловой тип. */
    fun supports(type: SourceType): Boolean = type in info.types

    /** Умеет ли источник обрабатывать этот вид значения (email/phone/…). */
    fun supports(params: SearchParams): Boolean = info.types.isNotEmpty()

    suspend fun search(params: SearchParams, key: KeySource): List<RawFact>
}

/** Адаптер: любой существующий [Connector] становится [IDataSource] без переписывания. */
class ConnectorDataSource(
    private val connector: Connector,
    override val info: SourceInfo
) : IDataSource {
    override suspend fun search(params: SearchParams, key: KeySource): List<RawFact> =
        connector.collect(params.query, key)
}

/**
 * Реестр источников. Декларативный список "одной строкой" — новый модуль
 * достаточно описать в [definitions] и (при необходимости) добавить Connector.
 *
 * Политика: в реестр попадают только источники с официальным API и только
 * те, что не принимают и не возвращают пароли пользователей.
 */
object DataSourceRegistry {

    private data class Definition(
        val id: String,
        val title: String,
        val types: Set<SourceType>,
        val description: String
    )

    /** Источники проверки утечек. Пароли не запрашиваются и не сохраняются. */
    private val definitions = listOf(
        Definition(
            id = "hibp",
            title = "Have I Been Pwned",
            types = setOf(SourceType.LEAK),
            description = "Официальный API: в каких публичных утечках встречается email."
        ),
        Definition(
            id = "leakcheck",
            title = "LeakCheck",
            types = setOf(SourceType.LEAK),
            description = "Сводка по email/телефону: наборы данных, даты и типы полей."
        )
    )

    private fun build(definition: Definition): IDataSource? {
        val connector = Connectors.byId(definition.id) ?: return null
        return ConnectorDataSource(
            connector = connector,
            info = SourceInfo(
                id = definition.id,
                title = definition.title,
                types = definition.types,
                description = definition.description,
                keyless = false,
                officialApi = true
            )
        )
    }

    val all: List<IDataSource> = definitions.mapNotNull(::build)

    fun supports(type: SourceType): List<IDataSource> = all.filter { it.supports(type) }

    /** Источники только для проверки утечек — без паролей и без scrapping. */
    val leakSources: List<IDataSource> get() = supports(SourceType.LEAK)
}
