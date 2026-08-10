import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Автоматическая версия сборки — та же схема, что в Vitz Dashboard.
 *
 * Патч и versionCode растут, когда исходники действительно изменились: признак —
 * отпечаток содержимого файлов, а не время сборки. Повторный запуск без правок номер
 * не двигает, поэтому версия всегда означает «другой код», а не «ещё один билд».
 *
 * Считается на этапе конфигурации, до компиляции: иначе в APK попал бы предыдущий номер.
 */
data class AppVersion(val name: String, val code: Int)

fun sourceFingerprint(): String {
    val tracked = files(
        fileTree("src/main") { include("**/*.kt", "**/*.xml", "**/*.pro") },
        file("build.gradle.kts"),
        rootProject.file("gradle/libs.versions.toml"),
        // DTO общие с сервером: их правка меняет поведение приложения не меньше своего кода.
        fileTree(rootProject.file("shared/src/main")) { include("**/*.kt") },
    )
    val digest = MessageDigest.getInstance("SHA-256")
    tracked.files
        .filter { it.isFile }
        .sortedBy { it.invariantSeparatorsPath }
        .forEach { file ->
            digest.update(file.invariantSeparatorsPath.toByteArray())
            digest.update(file.readBytes())
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun resolveAppVersion(): AppVersion {
    val versionFile = rootProject.file("version.properties")
    val props = Properties().apply {
        if (versionFile.exists()) versionFile.inputStream().use { load(it) }
    }

    val currentName = props.getProperty("versionName", "0.1.0")
    val currentCode = props.getProperty("versionCode", "1").toIntOrNull() ?: 1
    val storedHash = props.getProperty("sourceHash", "")
    val actualHash = sourceFingerprint()

    if (actualHash == storedHash) return AppVersion(currentName, currentCode)

    val parts = currentName.split(".").mapNotNull { it.toIntOrNull() }
    val nextName = if (parts.size == 3) "${parts[0]}.${parts[1]}.${parts[2] + 1}" else currentName
    val next = AppVersion(nextName, currentCode + 1)

    versionFile.writeText(
        """
        # Версия приложения. Поднимается автоматически при сборке, если исходники изменились.
        # sourceHash — отпечаток исходников последней сборки, по нему и виден факт правки.
        versionName=${next.name}
        versionCode=${next.code}
        sourceHash=$actualHash
        """.trimIndent() + "\n",
    )
    logger.lifecycle("Версия приложения: ${next.name} (${next.code})")
    return next
}

val appVersion = resolveAppVersion()

/**
 * Ключ подписи релизов лежит вне репозитория, пути и пароли — в local.properties.
 *
 * Ключ обязан быть постоянным: Android ставит обновление поверх, только если новая сборка
 * подписана тем же ключом, что и установленная. Отладочный ключ генерируется на каждой машине
 * свой, поэтому для самообновления он не годится.
 */
fun releaseSigningProperties(): Properties? {
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.exists()) return null

    val props = Properties().apply { localProperties.inputStream().use { load(it) } }
    val storePath = props.getProperty("release.storeFile") ?: return null
    return if (rootProject.file(storePath).exists()) props else null
}

val releaseSigning = releaseSigningProperties()

android {
    namespace = "com.vitz.music.app"
    compileSdk = 36
    // SDK на машине сборки доступен только на чтение — Gradle не сможет доустановить
    // другую версию build-tools, поэтому фиксируем ту, что есть.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.vitz.music.app"
        // Тот же minSdk, что у приборки: приложения живут на одном планшете.
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name
    }

    signingConfigs {
        releaseSigning?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("release.storeFile"))
                storePassword = props.getProperty("release.storePassword")
                keyAlias = props.getProperty("release.keyAlias")
                keyPassword = props.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Ставится рядом с релизной, как и у приборки.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Без ключа сборка не падает, а подписывается отладочным: собрать проект должно
            // быть возможно и на машине, где ключа нет. Но такой APK обновлением не станет.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // Нужен, чтобы приложение знало свою версию и могло сравнить её с опубликованной.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties",
        )
    }
}

/**
 * Кладёт свежий debug-APK в dist/ под именем с версией и убирает предыдущий,
 * чтобы на диске всегда лежала ровно одна, заведомо актуальная сборка.
 */
val distDirectory = rootProject.layout.projectDirectory.dir("dist")

val copyDebugApkToDist = tasks.register<Copy>("copyDebugApkToDist") {
    from(layout.buildDirectory.file("outputs/apk/debug/android-debug.apk"))
    into(distDirectory)
    rename { "VitzMusic-${appVersion.name}-debug.apk" }
    doFirst {
        delete(fileTree(distDirectory) { include("VitzMusic-*-debug.apk") })
    }
}

// matching/configureEach, а не named: задачи вариантов регистрирует AGP уже после того,
// как этот файл выполнен, и обращение по имени здесь ещё не находит их.
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyDebugApkToDist) }

/**
 * Готовит артефакты релиза для публикации: APK под постоянным именем и манифест версии.
 *
 * Имена файлов постоянные, потому что приложение ходит по ссылкам вида
 * `/releases/latest/download/<имя>` — они всегда указывают на последний релиз и не требуют
 * обращения к API GitHub.
 */
val prepareReleaseArtifacts = tasks.register("prepareReleaseArtifacts") {
    group = "distribution"
    description = "Собирает подписанный APK и version.json в dist/release"

    dependsOn("assembleRelease")

    val outputDirectory = rootProject.layout.projectDirectory.dir("dist/release")
    val apkSource = layout.buildDirectory.file("outputs/apk/release/android-release.apk")

    // Описание релиза приходит из скрипта выпуска: -PreleaseNotes="...".
    val notes = (project.findProperty("releaseNotes") as String?).orEmpty()

    doLast {
        val target = outputDirectory.asFile
        target.mkdirs()

        val apk = apkSource.get().asFile
        val published = target.resolve("VitzMusic-release.apk")
        apk.copyTo(published, overwrite = true)

        val escapedNotes = notes
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        target.resolve("version.json").writeText(
            """
            {
              "versionName": "${appVersion.name}",
              "versionCode": ${appVersion.code},
              "sizeBytes": ${published.length()},
              "notes": "$escapedNotes"
            }
            """.trimIndent() + "\n"
        )

        logger.lifecycle("Артефакты релиза ${appVersion.name} (${appVersion.code}) готовы: ${target.absolutePath}")
        if (releaseSigning == null) {
            logger.warn("ВНИМАНИЕ: ключ подписи не найден, APK подписан отладочным — обновлением он не станет")
        }
    }
}

dependencies {
    // DTO API общие с сервером: контракт физически не может разъехаться.
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.ui.tooling)
}
