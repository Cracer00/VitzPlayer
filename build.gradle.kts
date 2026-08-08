// Все плагины объявлены здесь с apply false, а модули подключают их без версии.
// Иначе Gradle видит Kotlin-плагин на classpath от одного модуля и отказывается принимать
// версию от другого: «already on the classpath with an unknown version».
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
