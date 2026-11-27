package ru.jbosint.lab

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.jbosint.lab.model.LabApiConfig
import ru.jbosint.lab.model.LabProjectType
import ru.jbosint.lab.model.LabScript

/** Модель лаборатории: типы, команды и настройки по умолчанию. Без Android. */
class LabModelTest {

    @Test
    fun typeMapsFromServerWireValues() {
        assertEquals(LabProjectType.PYTHON, LabProjectType.from("python"))
        assertEquals(LabProjectType.NODE, LabProjectType.from("node"))
        assertEquals(LabProjectType.BASH, LabProjectType.from("bash"))
        assertEquals(LabProjectType.ANDROID, LabProjectType.from("android"))
        assertEquals(LabProjectType.OTHER, LabProjectType.from("unknown"))
        assertEquals(LabProjectType.OTHER, LabProjectType.from(null))
    }

    @Test
    fun scriptBuildsSafeCommand() {
        assertEquals(
            "python3 tools/a.py",
            LabScript("a.py", "tools/a.py", LabProjectType.PYTHON).command()
        )
        assertEquals(
            "sh dir/a.sh",
            LabScript("a.sh", "dir/a.sh", LabProjectType.BASH).command()
        )
        assertEquals(
            "node 'a b.js'",
            LabScript("a b.js", "a b.js", LabProjectType.NODE).command()
        )
    }

    @Test
    fun defaultServerIsLoopback() {
        assertEquals("http://127.0.0.1:8081", LabApiConfig().baseUrl)
        assertEquals(300, LabApiConfig.DEFAULT_TIMEOUT_SEC)
    }
}
