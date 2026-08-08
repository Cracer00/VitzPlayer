package com.vitz.music.app.data

import com.vitz.music.api.ErrorDto
import com.vitz.music.api.LoginRequest
import com.vitz.music.api.MeResponse
import com.vitz.music.api.RefreshRequest
import com.vitz.music.api.TokenPair
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ApiException(val status: Int, val code: String, override val message: String?) : RuntimeException(message)

/**
 * Клиент API.
 *
 * Обновление access-токена сделано вручную, а не плагином Auth: логика ровно одна —
 * получили 401, обновились, повторили запрос один раз. Мьютекс не даёт десяти параллельным
 * запросам устроить десять одновременных обновлений и сжечь друг другу refresh-токен,
 * который на сервере ротируется при каждом использовании.
 */
class ApiClient(private val store: TokenStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    private val refreshLock = Mutex()

    @Volatile
    var session: Session? = store.load()
        private set

    val isLoggedIn: Boolean get() = session != null

    fun serverUrl(): String? = session?.serverUrl ?: store.lastServerUrl()

    suspend fun login(serverUrl: String, email: String, password: String, device: String): MeResponse {
        val base = normalizeServerUrl(serverUrl)
        val response = http.request("$base/api/v1/auth/login") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email.trim(), password = password, device = device))
        }
        if (!response.status.isSuccess()) throw response.toApiException()

        val tokens: TokenPair = response.body()
        val fresh = Session(base, tokens.accessToken, tokens.refreshToken)
        store.save(fresh)
        session = fresh
        return me()
    }

    suspend fun me(): MeResponse = get("/api/v1/me")

    suspend fun logout() {
        runCatching { authorized(HttpMethod.Post, "/api/v1/auth/logout") }
        store.clearTokens()
        session = null
    }

    suspend inline fun <reified T> get(path: String, noinline block: HttpRequestBuilder.() -> Unit = {}): T {
        val response = authorized(HttpMethod.Get, path, block = block)
        if (!response.status.isSuccess()) throw response.toApiException()
        return response.body()
    }

    suspend fun authorized(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val current = session ?: throw ApiException(401, "no_session", "Нет активной сессии")
        var response = send(method, current.serverUrl, path, current.accessToken, body, block)
        if (response.status == HttpStatusCode.Unauthorized && refresh()) {
            val renewed = session ?: throw ApiException(401, "no_session", "Сессия оборвалась")
            response = send(method, renewed.serverUrl, path, renewed.accessToken, body, block)
        }
        return response
    }

    private suspend fun send(
        method: HttpMethod,
        base: String,
        path: String,
        token: String,
        body: Any?,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = http.request("$base$path") {
        this.method = method
        header(HttpHeaders.Authorization, "Bearer $token")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        block()
    }

    /** true — токен обновлён и запрос можно повторить. false — сессия умерла, нужен вход заново. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        val current = session ?: return false
        val response = runCatching {
            http.request("${current.serverUrl}/api/v1/auth/refresh") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(current.refreshToken))
            }
        }.getOrElse { return false }

        if (!response.status.isSuccess()) {
            // Refresh отвергнут — сессию отозвали или сменили пароль. Токены пора выбросить.
            if (response.status == HttpStatusCode.Unauthorized) {
                store.clearTokens()
                session = null
            }
            return false
        }

        val tokens: TokenPair = runCatching { response.body<TokenPair>() }.getOrElse { return false }
        val fresh = current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
        store.save(fresh)
        session = fresh
        return true
    }

    suspend fun HttpResponse.toApiException(): ApiException {
        val error = runCatching { body<ErrorDto>() }.getOrNull()
        return ApiException(status.value, error?.error ?: "http_${status.value}", error?.message ?: status.description)
    }

    companion object {
        /** «music.nethound.ru» → «https://music.nethound.ru», хвостовой слэш убираем. */
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "https://$trimmed"
            }
        }
    }
}
