package ru.jbosint.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.lab.server.LabModuleScan

class LabModuleScanTest {

    @Test
    fun commandIsReadOnlyAndBounded() {
        val command = LabModuleScan.command()
        assertTrue(command.startsWith("find . -type f"))
        assertTrue(command.contains("head -80"))
        assertTrue(command.contains("head -12"))
        assertFalse(command.contains("rm "))
        assertFalse(command.contains("pip install"))
        assertFalse(command.contains("curl "))
    }

    @Test
    fun candidatesAreNormalizedAndDeduplicated() {
        val output = listOf(
            "JB_LAB_MODULE_URL=https://api.example.com/search?q={query}",
            "JB_LAB_MODULE_URL='https://api.example.com/search?q={query}'`",
            "JB_LAB_MODULE_URL=https://other.example.org/v1?q={query};",
            "JB_LAB_MODULE_URL=https://plain.example.org/docs",
            "some unrelated log line"
        ).joinToString("\n")
        assertEquals(
            listOf(
                "https://api.example.com/search?q={query}",
                "https://other.example.org/v1?q={query}"
            ),
            LabModuleScan.candidates(output)
        )
    }
}
