package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.HudsonRockApi
import ru.jbosint.osint.data.QueryKind

/**
 * Hudson Rock: бесплатный источник без ключа. Проверяем адреса и разбор
 * ответа с заражёнными инфостилером устройствами, включая логины и пароли.
 */
class HudsonRockTest {

    private val infected = """
        {"message":"This email address is associated with a computer infected by an info-stealer.",
         "stealers":[
          {"total_corporate_services":2,"total_user_services":14,
           "date_compromised":"2024-11-03T12:41:09.000Z","computer_name":"DESKTOP-8F3K2L",
           "operating_system":"Windows 10 Home","malware_path":"C:\\Users\\user\\AppData\\Roaming\\redline.exe",
           "antiviruses":["Windows Defender"],"ip":"95.24.11.7",
           "top_passwords":["Qwerty123","Passw0rd!","Qwerty123"],
           "top_logins":["user@example.com","user777"]},
          {"date_compromised":"2023-06-01T00:00:00.000Z","computer_name":"LAPTOP-1",
           "operating_system":"Windows 11","stealer":"Lumma","ip":"95.24.11.9",
           "top_passwords":[],"top_logins":["user777"]}
         ],
         "total_corporate_services":2,"total_user_services":14}
    """.trimIndent()

    @Test
    fun urlIsBuiltForEmailAndUsernameOnly() {
        assertTrue(HudsonRockApi.url("user@example.com", QueryKind.EMAIL)!!.contains("search-by-email?email=user%40example.com"))
        assertTrue(HudsonRockApi.url("@user777", QueryKind.USERNAME)!!.contains("search-by-username?username=user777"))
        assertNull(HudsonRockApi.url("+79161234567", QueryKind.PHONE))
        assertNull(HudsonRockApi.url("Иван Иванов", QueryKind.NAME))
    }

    @Test
    fun infectionBecomesFactWithDeviceAndPasswords() {
        val facts = HudsonRockApi.parse(infected, "user@example.com")
        val first = facts.first { it.kind == "Заражение инфостилером" }
        assertEquals("DESKTOP-8F3K2L", first.value)
        assertEquals("2024-11-03", first.attributes["Дата"])
        assertEquals("Windows 10 Home", first.attributes["ОС"])
        assertEquals("95.24.11.7", first.attributes["IP"])
        assertEquals("Windows Defender", first.attributes["Антивирусы"])
        // пароли не маскируются, дубликаты убраны
        assertEquals("Qwerty123, Passw0rd!", first.attributes["Пароли"])
        assertEquals("user@example.com, user777", first.attributes["Логины"])
    }

    @Test
    fun familyIsTakenFromEitherField() {
        val facts = HudsonRockApi.parse(infected, "user@example.com")
        assertEquals("Lumma", facts[1].attributes["Вредонос"])
    }

    @Test
    fun summaryCountsInfectionsAndServices() {
        val facts = HudsonRockApi.parse(infected, "user@example.com")
        val summary = facts.first { it.kind == "Всего заражений" }
        assertEquals("2", summary.value)
        assertEquals("14", summary.attributes["Личных сервисов"])
        assertEquals("2", summary.attributes["Корпоративных сервисов"])
    }

    @Test
    fun cleanAddressYieldsNothing() {
        val clean = """{"message":"not associated","stealers":[],"total_corporate_services":0,"total_user_services":0}"""
        assertTrue(HudsonRockApi.parse(clean, "x@example.com").isEmpty())
        assertTrue(HudsonRockApi.parse("<html>502</html>", "x@example.com").isEmpty())
        assertNotNull(QueryKind.EMAIL)
    }
}
