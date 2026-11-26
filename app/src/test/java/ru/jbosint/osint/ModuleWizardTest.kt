package ru.jbosint.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.module.ModuleInfo
import ru.jbosint.osint.data.module.ModuleKind
import ru.jbosint.osint.data.module.ModuleStatus
import ru.jbosint.osint.data.module.WizardFilter

/** Мастер модулей: статусы, фильтры и потребность в ключе. Без Android. */
class ModuleWizardTest {

    private fun info(
        id: String = "m",
        status: ModuleStatus = ModuleStatus.UNKNOWN,
        hasKey: Boolean = false,
        keyless: Boolean = false,
        active: Boolean = true,
        hidden: Boolean = false
    ) = ModuleInfo(
        id = id,
        name = id,
        kind = ModuleKind.BUILT_IN,
        isActive = active,
        isHidden = hidden,
        hasKey = hasKey,
        keyless = keyless,
        status = status
    )

    @Test
    fun keyStateMapsToWizardStatus() {
        assertEquals(ModuleStatus.ACTIVE, ModuleStatus.fromKeyState(KeyState.VALID))
        assertEquals(ModuleStatus.NEEDS_KEY, ModuleStatus.fromKeyState(KeyState.EMPTY))
        assertEquals(ModuleStatus.NO_NETWORK, ModuleStatus.fromKeyState(KeyState.OFFLINE))
        assertEquals(ModuleStatus.FAILED, ModuleStatus.fromKeyState(KeyState.INVALID))
        assertEquals(ModuleStatus.UNKNOWN, ModuleStatus.fromKeyState(KeyState.UNCHECKED))
    }

    @Test
    fun filtersSelectExpectedModules() {
        assertTrue(WizardFilter.ALL.matches(info(status = ModuleStatus.ACTIVE)))
        assertTrue(WizardFilter.WORKING.matches(info(status = ModuleStatus.ACTIVE)))
        assertFalse(WizardFilter.WORKING.matches(info(status = ModuleStatus.NEEDS_KEY)))
        assertTrue(WizardFilter.NEEDS_KEY.matches(info(status = ModuleStatus.NEEDS_KEY)))
        assertTrue(WizardFilter.NEEDS_KEY.matches(info(status = ModuleStatus.ACTIVE, hasKey = false)))
        assertTrue(WizardFilter.NO_NETWORK.matches(info(status = ModuleStatus.NO_NETWORK)))
    }

    @Test
    fun needsKeyRespectsKeylessAndSavedKey() {
        assertTrue(info(hasKey = false, keyless = false).needsKey)
        assertFalse(info(hasKey = true, keyless = false).needsKey)
        assertFalse(info(hasKey = false, keyless = true).needsKey)
    }
}
