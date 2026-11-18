package ru.jbosint.osint.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.RawFact

/**
 * Проверка автомобиля: VIN, номер кузова или госномер.
 *
 * Платный источник — TRONK.PRO (API отдаёт отчёт по VIN: ДТП, залоги, пробег,
 * владельцы, ремонты, средняя цена, фото, парковочные сессии). Ключ задаётся в
 * «Ключи → Проверка авто». Без ключа модуль не выдумывает данные: он отдаёт
 * только локальный разбор VIN (регион, год, производитель по WMI) и ссылки на
 * официальные сервисы проверки, которые пользователь открывает сам.
 *
 * Разбор ответа TRONK терпимый: сервис не публикует жёсткую схему, поэтому
 * поддерживаются распространённые имена полей (accidents/accidents_count,
 * pledge/pledges, mileage/mileage_km, owners/owners_count …).
 */
object AutoCheck {

    const val SITE = "https://tronk.pro"
    const val BASE = "https://api.tronk.pro/v1"

    /** Ссылки на официальные и публичные сервисы — открываются вручную. */
    fun manualLinks(vin: String, plate: String = ""): List<Pair<String, String>> {
        val query = vin.ifBlank { plate }
        return listOf(
            "ГИБДД — проверка автомобиля" to "https://гибдд.рф/check/auto",
            "Реестр залогов" to "https://www.reestr-zalogov.ru/search/index",
            "Autoteka" to "https://autoteka.ru/",
            "Авито — объявления" to "https://www.avito.ru/rossiya/avtomobili?q=$query",
            "Дром — объявления" to "https://www.drom.ru/search/?q=$query",
            "Авто.ру" to "https://auto.ru/cars/all/?query=$query"
        )
    }

    /**
     * Локальный разбор VIN: WMI (производитель и регион), 10-й символ — год выпуска.
     * Ничего не запрашивает в сеть, работает всегда.
     */
    fun decodeVin(vin: String): List<RawFact> {
        val clean = vin.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length != 17) return emptyList()
        val wmi = clean.take(3)
        val yearCode = clean[9]
        val year = YEAR_CODES[yearCode]
        val region = WMI_REGIONS.entries.firstOrNull { (prefix, _) -> wmi.startsWith(prefix) }
        val subject = "Автомобиль $clean"
        val facts = ArrayList<RawFact>()
        facts += RawFact(
            FactCategory.TECHNICAL, "VIN", clean, subject,
            buildMap {
                year?.let { put("Год выпуска", it.toString()) }
                region?.let { put("Регион сборки", it.value) }
                put("WMI", wmi)
                put("Проверка контрольной суммы", if (validChecksum(clean)) "пройдена" else "не пройдена")
            },
            sourceId = "vin", weight = 2, sourceUrl = ""
        )
        region?.let {
            facts += RawFact(FactCategory.LOCATION, "Регион сборки", it.value, subject, emptyMap(), "vin", 1)
        }
        return facts
    }

    private fun validChecksum(vin: String): Boolean {
        val transliteration = mapOf(
            'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
            'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
            'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9
        )
        val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
        var sum = 0
        for (index in 0 until 17) {
            val ch = vin[index]
            val value = when {
                ch.isDigit() -> ch.digitToInt()
                // В теле VIN буква X стоит 7 (как и в таблице выше). Значение 10 у неё
                // есть только в 9-й позиции — как контрольная цифра, но её вес равен 0,
                // поэтому на сумму она не влияет. Считать X десяткой везде — ошибка:
                // любой VIN с X вне 9-й позиции получал «контрольная сумма не пройдена».
                else -> transliteration[ch] ?: return false
            }
            sum += value * weights[index]
        }
        val remainder = sum % 11
        val check = vin[8]
        val expected = when {
            remainder == 10 -> 'X'
            else -> '0' + remainder
        }
        return check == expected
    }

    /** Запрос к TRONK.PRO по VIN или госномеру. Возвращает разобранные факты. */
    suspend fun tronk(
        key: String,
        vin: String = "",
        plate: String = "",
        baseUrl: String = ""
    ): List<RawFact> =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext emptyList()
            // Базовый адрес API задаёт пользователь: у сервиса он зависит от тарифа,
            // а зашитый по умолчанию хост может быть недоступен.
            val base = baseUrl.trim().trimEnd('/')
            if (base.isBlank() || !base.startsWith("https://")) return@withContext emptyList()
            val path = when {
                vin.isNotBlank() -> "$base/vin/${Net.urlEncode(vin.trim())}"
                plate.isNotBlank() -> "$base/plate/${Net.urlEncode(plate.trim())}"
                else -> return@withContext emptyList()
            }
            val response = runCatching {
                Net.fetch(path, headers = mapOf("Authorization" to "Bearer ${key.trim()}", "Accept" to "application/json"))
            }.getOrNull() ?: return@withContext emptyList()
            if (response.code !in 200..299) return@withContext emptyList()
            parseAuto(response.body, vin.ifBlank { plate })
        }

    /** Разбор ответа: объект автомобиля в любом конверте. */
    fun parseAuto(body: String, query: String): List<RawFact> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val car = findCar(root) ?: return emptyList()
        val subject = "Автомобиль ${car.optString("vin").ifBlank { query }}"
        val facts = ArrayList<RawFact>()

        fun add(kind: String, value: String, category: FactCategory = FactCategory.TECHNICAL, attrs: Map<String, String> = emptyMap()) {
            if (value.isBlank()) return
            facts += RawFact(category, kind, value, subject, attrs, sourceId = "tronk", weight = 3, sourceUrl = SITE)
        }

        val vin = car.optString("vin")
        val plate = car.optString("plate").ifBlank { car.optString("license_plate") }
        val model = car.optString("model").ifBlank { car.optString("title") }
        val year = car.optInt("year", 0).takeIf { it > 0 } ?: car.optString("year").toIntOrNull()
        val mileage = number(car, "mileage", "mileage_km", "odometer")
        val owners = number(car, "owners", "owners_count", "ownersCount")
        val accidents = number(car, "accidents", "accidents_count", "accidentsCount")
        val repairs = number(car, "repairs", "repairs_count")
        val pledges = number(car, "pledges", "pledge", "pledges_count", "lien")
        val price = number(car, "price", "average_price", "avg_price")
        val parking = number(car, "parking_sessions", "parking", "parkings")

        if (vin.isNotBlank()) add("VIN", vin)
        if (plate.isNotBlank()) add("Госномер", plate)
        if (model.isNotBlank()) add("Марка и модель", model)
        if (year != null && year > 0) add("Год выпуска", year.toString())
        if (mileage != null && mileage > 0) add("Пробег", "$mileage км")
        if (owners != null && owners > 0) add("Владельцев по данным сервиса", owners.toString())
        if (accidents != null && accidents > 0) {
            add("ДТП в истории", accidents.toString(), FactCategory.TECHNICAL)
        } else if (car.has("accidents")) {
            add("ДТП в истории", "не зафиксировано")
        }
        if (repairs != null && repairs > 0) add("Ремонтные работы", repairs.toString())
        if (pledges != null && pledges > 0) add("Залоги", pledges.toString(), FactCategory.TECHNICAL) else
            if (car.has("pledges") || car.has("pledge")) add("Залоги", "не найдены")
        if (price != null && price > 0) add("Средняя цена", "${price.toInt()} ₽", FactCategory.TECHNICAL)
        if (parking != null && parking > 0) add("Парковочные сессии", parking.toString())

        car.optJSONArray("photos")?.let { photos ->
            for (i in 0 until photos.length()) {
                val url = photos.optString(i)
                if (url.startsWith("http")) add("Фотография объявления", url, FactCategory.DOCUMENT)
            }
        }
        car.optJSONArray("owners_list")?.let { list ->
            for (i in 0 until list.length()) {
                val owner = list.optJSONObject(i) ?: continue
                val years = owner.optString("period").ifBlank { owner.optString("since") }
                val term = owner.optInt("duration_months", 0)
                if (years.isNotBlank() || term > 0) {
                    add(
                        "Период владения", years.ifBlank { "$term мес." }, FactCategory.OTHER,
                        mapOf("Владельцев" to "1")
                    )
                }
            }
        }
        val accidentsList = car.optJSONArray("accidents_list")
        if (accidentsList != null) {
            for (i in 0 until accidentsList.length()) {
                val item = accidentsList.optJSONObject(i) ?: continue
                val date = item.optString("date")
                val damage = item.optString("damage").ifBlank { item.optString("type") }
                if (date.isNotBlank() || damage.isNotBlank()) {
                    add("Запись о ДТП", listOf(date, damage).filter { it.isNotBlank() }.joinToString(" · "), FactCategory.OTHER)
                }
            }
        }
        return facts
    }

    private fun findCar(root: JSONObject): JSONObject? {
        for (key in listOf("data", "result", "car", "vehicle", "report")) {
            val nested = root.optJSONObject(key)
            if (nested != null) return findCar(nested)
        }
        return when {
            root.has("vin") || root.has("plate") || root.has("mileage") || root.has("model") -> root
            root.optJSONArray("items")?.optJSONObject(0) != null -> root.optJSONArray("items")!!.optJSONObject(0)
            else -> null
        }
    }

    private fun number(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            val raw = obj.opt(key) ?: continue
            val value = when (raw) {
                is Int -> raw
                is Long -> raw.toInt()
                is Double -> raw.toInt()
                is String -> Regex("\\d+").find(raw.replace(" ", ""))?.value?.toIntOrNull() ?: 0
                else -> 0
            }
            return value
        }
        return null
    }

    /** Регион сборки по префиксу WMI (основные производители и площадки). */
    private val WMI_REGIONS = linkedMapOf(
        "XTA" to "Россия (АвтоВАЗ)", "XTT" to "Россия (УАЗ)", "X9F" to "Россия",
        "WVW" to "Германия (Volkswagen)", "WBA" to "Германия (BMW)", "WDD" to "Германия (Mercedes-Benz)",
        "WAU" to "Германия (Audi)", "VIN" to "Европа", "VF1" to "Франция (Renault)",
        "VF7" to "Франция (Citroën)", "VF3" to "Франция (Peugeot)", "ZFA" to "Италия (Fiat)",
        "JHM" to "Япония (Honda)", "JTD" to "Япония (Toyota)", "JN1" to "Япония (Nissan)",
        "JT2" to "Япония (Toyota)", "KMH" to "Корея (Hyundai)", "KNA" to "Корея (Kia)",
        "1HG" to "США (Honda)", "1FT" to "США (Ford)", "2T1" to "Канада (Toyota)",
        "LVV" to "Китай (Chery)", "LSV" to "Китай/Фольксваген", "YV1" to "Швеция (Volvo)"
    )

    /** Коды года выпуска в 10-й позиции VIN (цикл 1980–2039). */
    private val YEAR_CODES: Map<Char, Int> = buildMap {
        val first = "ABCDEFGHJKLMNPRSTVWXY"
        first.forEachIndexed { index, ch -> put(ch, 1980 + index) }
        // Цифры в 10-й позиции: 2001–2009 (более частый случай, чем 2031–2039).
        "123456789".forEachIndexed { index, ch -> put(ch, 2001 + index) }
        first.forEachIndexed { index, ch -> put(ch, 2010 + index) }
    }

    /** Уровень доверия к записи о ДТП/залоге: показывается в отчёте отдельно. */
    fun confidenceFor(kind: String): Confidence = when {
        kind.startsWith("VIN") || kind.startsWith("Госномер") -> Confidence.CONFIRMED
        kind.contains("ДТП") || kind.contains("Залог") -> Confidence.PROBABLE
        else -> Confidence.UNVERIFIED
    }
}

/**
 * Коннектор проверки авто для общего движка поиска.
 * Реагирует только на VIN и госномер: остальные запросы пропускает,
 * чтобы не тратить платный вызов TRONK.
 */
object TronkConnector : Connector {
    override val id = "tronk"

    override suspend fun collect(query: String, key: KeySource): List<RawFact> {
        val token = key.key(id)
        if (token.isBlank()) return emptyList()
        val compact = query.filter { it.isLetterOrDigit() }.uppercase()
        return when {
            compact.length == 17 -> AutoCheck.decodeVin(compact) + AutoCheck.tronk(token, vin = compact)
            Regex("^[АВЕКМНОРСТУХA-Z]\\d{3}[АВЕКМНОРСТУХA-Z]{2}\\d{2,3}$", RegexOption.IGNORE_CASE)
                .matches(query.trim()) -> AutoCheck.tronk(token, plate = query.trim().uppercase())
            else -> emptyList()
        }
    }
}

/**
 * Бесплатная расшифровка VIN через NHTSA vPIC — открытый API без ключа.
 * Отдаёт марку, модель, год, страну и завод сборки, тип кузова, двигатель.
 * Используется вместе с локальным разбором VIN и TRONK.
 */
object VinDecoder {

    private const val BASE = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues"

    suspend fun decode(vin: String): List<RawFact> = withContext(Dispatchers.IO) {
        val clean = vin.trim().uppercase()
        if (clean.length != 17) return@withContext emptyList()
        val response = runCatching { Net.fetch("$BASE/$clean?format=json", attempts = 1) }
            .getOrElse { return@withContext emptyList() }
        if (response.code !in 200..299) return@withContext emptyList()
        parse(response.body, clean)
    }

    /** Разбор ответа vPIC: Results[0] с полями Make, Model, ModelYear и т. д. */
    fun parse(body: String, vin: String): List<RawFact> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("Results") ?: return emptyList()
        val car = results.optJSONObject(0) ?: return emptyList()
        fun field(name: String): String = car.optString(name).trim().takeIf { it.isNotBlank() && it != "0" }.orEmpty()

        val make = field("Make")
        val model = field("Model")
        val year = field("ModelYear")
        val plant = field("PlantCountry")
        val bodyClass = field("BodyClass")
        val engine = listOf(field("DisplacementCC"), field("EngineCylinders"), field("FuelTypePrimary"))
            .filter { it.isNotBlank() }.joinToString(", ")
        val subject = "Автомобиль $vin"
        val facts = ArrayList<RawFact>()
        fun add(kind: String, value: String, category: FactCategory = FactCategory.TECHNICAL, attrs: Map<String, String> = emptyMap()) {
            if (value.isBlank()) return
            facts += RawFact(category, kind, value, subject, attrs, sourceId = "nhtsa", weight = 3, sourceUrl = "$BASE/$vin?format=json")
        }
        add("Марка", make)
        add("Модель", model)
        add("Год выпуска", year)
        add("Страна сборки", plant, FactCategory.LOCATION)
        add("Тип кузова", bodyClass)
        add("Двигатель", engine)
        add("Производитель (NHTSA)", field("Manufacturer"))
        add("Тип транспортного средства", field("VehicleType"))
        if (facts.isNotEmpty()) {
            facts += RawFact(
                FactCategory.TECHNICAL, "VIN подтверждён", vin, subject,
                mapOf("Источник" to "NHTSA vPIC (открытые данные)"),
                sourceId = "nhtsa", weight = 3, sourceUrl = "$BASE/$vin?format=json"
            )
        }
        return facts.distinctBy { it.kind to it.value }
    }
}
