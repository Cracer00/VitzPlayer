rootProject.name = "vitz-music"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":server")

// Серверные сборки (образ в Docker, тесты в CI) идут без Android SDK, а Gradle по умолчанию
// конфигурирует все модули — плагин AGP там просто не найдёт SDK и уронит сборку.
// Поэтому такие сборки запускаются с -PskipAndroid и модуль не подключается вовсе.
if (!providers.gradleProperty("skipAndroid").isPresent) {
    include(":android")
}
