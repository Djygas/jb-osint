package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.source.DataSourceRegistry
import ru.jbosint.osint.data.source.SourceType

/**
 * Архитектура источников: вкладка «Утечки» должна работать только с
 * официальными API и не подключать неофициальные/скрейпинговые модули.
 */
class DataSourceRegistryTest {

    @Test
    fun leaksUseOnlyOfficialSources() {
        val sources = DataSourceRegistry.leakSources
        assertEquals(setOf("hibp", "leakcheck"), sources.map { it.info.id }.toSet())
        assertTrue(sources.all { it.info.officialApi })
        assertTrue(sources.all { it.supports(SourceType.LEAK) })
    }
}
