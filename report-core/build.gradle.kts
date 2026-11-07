plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
    // Артефакты отчётов для внешней проверки (pdftoppm / openpyxl)
    systemProperty("jb.testOutput", layout.buildDirectory.dir("test-reports").get().asFile.absolutePath)
}
