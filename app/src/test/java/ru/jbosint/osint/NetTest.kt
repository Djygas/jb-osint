package ru.jbosint.osint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.Net

/** Защита от утечки ключа: редирект на чужой хост не выполняется. */
class NetTest {

    private val keyUrl = "https://api.example.com/v1/search?q=osint&key=SECRET"

    @Test
    fun followsRedirectOnSameHost() {
        assertTrue(
            Net.sameHost(
                keyUrl,
                "https://api.example.com/v2/search?q=osint&key=SECRET"
            )
        )
    }

    @Test
    fun blocksRedirectToOtherHost() {
        assertFalse(
            Net.sameHost(
                keyUrl,
                "https://collect.evil.example.net/collect?q=osint&key=SECRET"
            )
        )
    }

    @Test
    fun blocksSubdomainTrick() {
        assertFalse(
            Net.sameHost(
                keyUrl,
                "https://api.example.com.evil.net/search?key=SECRET"
            )
        )
    }

    @Test
    fun blocksDowngradeToHttp() {
        assertFalse(
            Net.sameHost(
                keyUrl,
                "http://api.example.com/v1/search?q=osint&key=SECRET"
            )
        )
    }
}
