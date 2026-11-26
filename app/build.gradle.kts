import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.jbosint.osint"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.jbosint.osint"
        minSdk = 26
        targetSdk = 35
        versionCode = 46
        versionName = "1.30.0"
        resourceConfigurations += listOf("ru", "en")

        // ML Kit face-detection приносит нативные библиотеки под все ABI (33 МБ из 39 МБ APK).
        // Телефонам нужны только ARM: x86/x86_64 остаются лишь для эмулятора и включаются
        // флагом -PjbAllAbis=true при локальной отладке.
        val allAbis = (project.findProperty("jbAllAbis") as String?)?.toBoolean() == true
        ndk {
            abiFilters += if (allAbis) {
                listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            } else {
                listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    signingConfigs {
        // Ключ подписи НЕ хранится в репозитории, и пароля по умолчанию нет:
        // значения берутся только из keystore.properties (в .gitignore) или переменных
        // окружения JB_KEYSTORE_FILE / JB_KEYSTORE_PASSWORD / JB_KEY_ALIAS / JB_KEY_PASSWORD.
        // Нет ключа или пароля — release собирается неподписанным, а не падает.
        val signingProps = Properties()
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) propsFile.inputStream().use { signingProps.load(it) }
        val storePath = System.getenv("JB_KEYSTORE_FILE")
            ?: signingProps.getProperty("storeFile").orEmpty()
        val storePass = System.getenv("JB_KEYSTORE_PASSWORD")
            ?: signingProps.getProperty("storePassword").orEmpty()
        val aliasName = System.getenv("JB_KEY_ALIAS")
            ?: signingProps.getProperty("keyAlias")
            ?: "jbosint"
        val keyPass = System.getenv("JB_KEY_PASSWORD")
            ?: signingProps.getProperty("keyPassword")
            ?: storePass
        val storeFileRef = storePath.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
        create("release") {
            if (storeFileRef != null && storeFileRef.exists() && storePass.isNotBlank()) {
                storeFile = storeFileRef
                storePassword = storePass
                keyAlias = aliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись подключается только когда ключ и пароль реально заданы:
            // иначе APK остаётся неподписанным, а не падает на validateSigning.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Ошибки lint останавливают сборку: раньше abortOnError = false пропускал их в релиз.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        checkDependencies = true
        disable += setOf("GradleDependency")
    }
}

dependencies {
    implementation(project(":report-core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
