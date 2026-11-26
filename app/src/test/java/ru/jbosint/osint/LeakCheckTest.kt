package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.LeakCheckApi
import ru.jbosint.osint.data.LeakCheckPro
import ru.jbosint.osint.data.QueryDetector
import ru.jbosint.osint.data.QueryKind

/**
 * LeakCheck Public API: разбор сводки и построение запроса. Проверяется, что модуль
 * не выдумывает утечки, когда сервис их не нашёл, и не теряет типы данных.
 */
class LeakCheckTest {

    private val found = """
        {"success":true,"found":1675,
         "fields":["email","password","dob","phone","unknown_field"],
         "sources":[{"name":"KomplettFritid.no","date":"2021-02"},
                    {"name":"Carhartt.com","date":"2026-08"}]}
    """.trimIndent()

    @Test
    fun urlCarriesKeyCheckAndType() {
        val url = LeakCheckApi.url("example@example.com", QueryKind.EMAIL, "abc123")
        assertTrue(url!!.startsWith(LeakCheckApi.BASE + "?"))
        assertTrue(url.contains("key=abc123"))
        assertTrue(url.contains("check=example%40example.com"))
        assertTrue(url.contains("type=email"))
    }

    @Test
    fun domainAndPhoneUseTheirOwnType() {
        assertTrue(LeakCheckApi.url("example.com", QueryKind.DOMAIN, "k")!!.contains("type=domain"))
        assertTrue(LeakCheckApi.url("+79161234567", QueryKind.PHONE, "k")!!.contains("type=phone"))
    }

    @Test
    fun nameIsNotSentToService() {
        assertNull(LeakCheckApi.url("Иван Иванов", QueryDetector.detect("Иван Иванов"), "k"))
    }

    @Test
    fun sourcesBecomeFactsWithDate() {
        val facts = LeakCheckApi.parse(found, "example@example.com")
        val breaches = facts.filter { it.kind == "Утечка" }
        assertEquals(2, breaches.size)
        assertEquals("KomplettFritid.no", breaches.first().value)
        assertEquals("2021-02", breaches.first().attributes["Дата"])
    }

    @Test
    fun recordCountAndFieldTypesAreReported() {
        val facts = LeakCheckApi.parse(found, "example@example.com")
        assertEquals("1675", facts.first { it.kind == "Записей в утечках" }.value)
        val types = facts.first { it.kind == "Типы данных" }.value
        assertTrue(types.contains("почта"))
        assertTrue(types.contains("дата рождения"))
        // неизвестное поле остаётся как есть, но ничего не теряется
        assertTrue(types.contains("unknown_field"))
    }

    @Test
    fun passwordsAppearOnlyAsFieldNames() {
        val facts = LeakCheckApi.parse(found, "example@example.com")
        // отдельного факта с паролем нет — только перечисление типов данных
        assertTrue(facts.none { it.kind == "Пароль" })
        val types = facts.first { it.kind == "Типы данных" }.value
        assertEquals("пароль", types.split(", ").first { it == "пароль" })
    }

    @Test
    fun notFoundYieldsNothing() {
        assertTrue(LeakCheckApi.parse("""{"success":false,"error":"Not found"}""", "x@example.com").isEmpty())
    }

    @Test
    fun brokenBodyYieldsNothing() {
        assertTrue(LeakCheckApi.parse("<html>502</html>", "x@example.com").isEmpty())
    }

    @Test
    fun sourcesAreCappedAndDeduplicated() {
        val many = (1..20).joinToString(",") { """{"name":"Leak$it","date":"2020-01"}""" }
        val body = """{"success":true,"found":900,"fields":[],"sources":[$many]}"""
        val breaches = LeakCheckApi.parse(body, "x@example.com").filter { it.kind == "Утечка" }
        assertEquals(12, breaches.size)
        val summary = LeakCheckApi.parse(body, "x@example.com").first { it.kind == "Записей в утечках" }
        assertEquals("12 из 20", summary.attributes["Показано наборов"])
    }
}

/**
 * LeakCheck Pro API v2: полные записи с паролями.
 * Проверяем адрес (ключ не в URL), склейку записей по источнику и разбор ошибок.
 */
class LeakCheckProTest {

    private val pro = """
        {"success":true,"found":12,"quota":197,
         "result":[
          {"email":"test@example.com","password":"Qwerty123","username":"tester",
           "source":{"name":"Collection#1","breach_date":"2019-01-07"},
           "fields":["email","password","username","ip","dob"]},
          {"email":"test@example.com","password":"Passw0rd!","username":"tester",
           "source":{"name":"Collection#1","breach_date":"2019-01-07"},
           "fields":["email","password","username"]},
          {"phone":"+79161234567","passwordless":1,
           "source":{"name":"Mamba.ru","breach_date":"2021-03-02"},
           "fields":["phone","name"]}
         ]}
    """.trimIndent()

    @Test
    fun keyGoesToHeaderNotToUrl() {
        val url = LeakCheckPro.url("test@example.com", QueryKind.EMAIL)!!
        assertTrue(url.startsWith(LeakCheckPro.BASE))
        assertFalse(url.contains("key="))
        assertEquals("secret", LeakCheckPro.headers("secret")["X-API-Key"])
    }

    @Test
    fun typesAreMappedForEveryKind() {
        assertTrue(LeakCheckPro.url("test@example.com", QueryKind.EMAIL)!!.contains("type=email"))
        assertTrue(LeakCheckPro.url("+79161234567", QueryKind.PHONE)!!.contains("type=phone"))
        assertTrue(LeakCheckPro.url("tester", QueryKind.USERNAME)!!.contains("type=username"))
        assertTrue(LeakCheckPro.url("mamba.ru", QueryKind.DOMAIN)!!.contains("type=domain"))
        assertTrue(LeakCheckPro.url("Иван Иванов", QueryKind.NAME)!!.contains("type=keyword"))
        assertNull(LeakCheckPro.url("8.8.8.8", QueryKind.IP))
    }

    @Test
    fun recordsAreGroupedBySourceWithLoginAndPassword() {
        val facts = LeakCheckPro.parse(pro, "test@example.com")
        val breach = facts.first { it.kind == "Утечка" && it.value == "Collection#1" }
        assertEquals("2019-01-07", breach.attributes["Дата"])
        assertEquals("test@example.com, tester", breach.attributes["Логин"])
        assertEquals("Qwerty123, Passw0rd!", breach.attributes["Пароль"])
        assertEquals("2", breach.attributes["Записей"])
        assertTrue(breach.attributes["Ещё"]!!.contains("дата рождения"))
    }

    @Test
    fun passwordlessRecordIsShownWithoutInventedPassword() {
        val facts = LeakCheckPro.parse(pro, "test@example.com")
        val mamba = facts.first { it.kind == "Утечка" && it.value == "Mamba.ru" }
        assertEquals(null, mamba.attributes["Пароль"])
        assertEquals("1", mamba.attributes["Без пароля"])
        assertEquals("+79161234567", mamba.attributes["Логин"])
    }

    @Test
    fun summaryKeepsFoundQuotaAndShown() {
        val facts = LeakCheckPro.parse(pro, "test@example.com")
        val summary = facts.first { it.kind == "Записей в утечках" }
        assertEquals("12", summary.value)
        assertEquals("197", summary.attributes["Остаток запросов"])
        assertEquals("3 из 12", summary.attributes["Показано записей"])
    }

    @Test
    fun emptyAndBrokenBodiesYieldNothing() {
        assertTrue(LeakCheckPro.parse("""{"success":true,"found":0,"result":[]}""", "x@example.com").isEmpty())
        assertTrue(LeakCheckPro.parse("""{"success":false,"error":"Not found"}""", "x@example.com").isEmpty())
        assertTrue(LeakCheckPro.parse("<html>502</html>", "x@example.com").isEmpty())
    }
}
