package com.vitz.music.admin

import com.vitz.music.AppServices
import com.vitz.music.auth.Passwords
import com.vitz.music.auth.Users
import com.vitz.music.catalog.Catalog
import com.vitz.music.db.count
import com.vitz.music.db.dbRead
import com.vitz.music.db.dbTx
import com.vitz.music.db.select
import com.vitz.music.db.uuid
import com.vitz.music.forbidden
import com.vitz.music.jobs.IngestPayload
import com.vitz.music.jobs.JOB_INGEST
import com.vitz.music.jobs.JobRow
import com.vitz.music.media.coverUrl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.InputType
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault())

@Suppress("LongMethod")
fun Route.adminRoutes(services: AppServices) = route("/admin") {
    val cfg = services.cfg

    get("/app.css") { call.respondText(ADMIN_CSS, ContentType.Text.CSS) }
    get("/app.js") { call.respondText(ADMIN_JS, ContentType.Text.JavaScript) }

    get("/login") {
        call.respondHtml {
            head {
                meta(charset = "utf-8")
                title { +"Vitz Music — вход" }
                link(rel = "stylesheet", href = "/admin/app.css")
            }
            body {
                main {
                    h1 { +"Vitz Music" }
                    p("muted") { +"Панель управления каталогом" }
                    form(action = "/admin/login", method = FormMethod.post) {
                        div("row") {
                            textInput(name = "email") { placeholder = "почта"; attributes["autocomplete"] = "username" }
                        }
                        div("row") {
                            style = "margin-top:10px"
                            passwordInput(name = "password") {
                                placeholder = "пароль"
                                attributes["autocomplete"] = "current-password"
                            }
                            button(classes = "primary", type = ButtonType.submit) { +"Войти" }
                        }
                    }
                    if (call.request.queryParameters["error"] != null) {
                        p { style = "color:#ff6b6b"; +"Неверная почта или пароль" }
                    }
                }
            }
        }
    }

    post("/login") {
        val params = call.receiveParameters()
        val email = params["email"].orEmpty()
        val password = params["password"].orEmpty()
        val user = dbRead { c -> Users.findByEmail(c, email) }
        val ok = user != null && user.role == "admin" && user.disabledAt == null &&
            Passwords.verify(password, user.passwordHash)
        if (user == null || !ok) {
            call.respondRedirect("/admin/login?error=1")
            return@post
        }
        call.sessions.set(AdminSession(user.id.toString(), AdminSession.newCsrf(), Instant.now().epochSecond))
        call.respondRedirect("/admin")
    }

    post("/logout") {
        call.sessions.clear<AdminSession>()
        call.respondRedirect("/admin/login")
    }

    // ---------- сводка ----------

    get {
        val session = call.requireAdmin() ?: return@get
        val stats = dbRead { c ->
            mapOf(
                "Треков" to c.count("select count(*) from tracks where deleted_at is null"),
                "Исполнителей" to c.count("select count(*) from artists"),
                "Альбомов" to c.count("select count(*) from albums"),
                "Пользователей" to c.count("select count(*) from users"),
                "Плейлистов" to c.count("select count(*) from playlists where deleted_at is null"),
            )
        }
        val bytes = dbRead { c ->
            c.count("select coalesce(sum(size_bytes), 0) from renditions")
        }
        val free = runCatching { Files.getFileStore(cfg.mediaRoot).usableSpace }.getOrDefault(0L)
        val jobs = dbRead { c -> services.queue.counts(c) }

        call.page("Сводка", session) {
            h1 { +"Сводка" }
            div("cards") {
                stats.forEach { (label, value) ->
                    div("card") {
                        div("value") { +value.toString() }
                        div("label") { +label }
                    }
                }
                div("card") {
                    div("value") { +formatBytes(bytes) }
                    div("label") { +"Занято медиа" }
                }
                div("card") {
                    div("value") { +formatBytes(free) }
                    div("label") { +"Свободно на диске" }
                }
            }
            h2 { +"Очередь" }
            if (jobs.isEmpty()) {
                p("muted") { +"Заданий пока не было" }
            } else {
                div("row") {
                    jobs.forEach { (status, n) ->
                        span("pill $status") { +"$status: $n" }
                    }
                }
            }
        }
    }

    // ---------- загрузка ----------

    get("/upload") {
        val session = call.requireAdmin() ?: return@get
        call.page("Загрузка", session) {
            h1 { +"Загрузка треков" }
            div {
                attributes["id"] = "dropzone"
                +"Перетащите файлы сюда или нажмите, чтобы выбрать"
            }
            input(type = InputType.file, name = "files") {
                attributes["id"] = "picker"
                style = "display:none"
                attributes["multiple"] = "multiple"
                attributes["accept"] = "audio/*,.mp3,.flac,.m4a,.ogg,.opus,.wav"
            }
            ul {
                attributes["id"] = "queue"
                style = "list-style:none;padding:0"
            }
            h2 { +"Очередь обработки" }
            div { attributes["id"] = "jobs" }
        }
    }

    post("/upload") {
        val session = call.requireAdminApi() ?: return@post
        if (call.request.headers["X-CSRF"] != session.csrf) forbidden()

        val name = call.request.queryParameters["name"]?.takeIf { it.isNotBlank() } ?: "upload.bin"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 } ?: "bin"
        val target = services.store.incoming(ext)

        val written = withContext(Dispatchers.IO) {
            var total = 0L
            call.receiveChannel().toInputStream().use { input ->
                Files.newOutputStream(target).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > cfg.uploadMaxBytes) {
                            out.close()
                            Files.deleteIfExists(target)
                            return@withContext -1L
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            total
        }

        if (written < 0) {
            call.respondText("""{"error":"too_large"}""", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
            return@post
        }

        val jobId = services.queue.enqueue(
            JOB_INGEST,
            json.encodeToString(
                IngestPayload(
                    temp = target.toString(),
                    name = name,
                    addedBy = session.userId,
                    source = "upload",
                ),
            ),
        )
        call.respondText("""{"jobId":"$jobId"}""", ContentType.Application.Json, HttpStatusCode.Accepted)
    }

    get("/jobs/fragment") {
        val session = call.requireAdminApi() ?: return@get
        val jobs = dbRead { c -> services.queue.recent(c, 25) }
        val html = buildString {
            appendHTML().table {
                thead {
                    tr {
                        th { +"Когда" }; th { +"Файл" }; th { +"Статус" }; th { +"Подробности" }; th { }
                    }
                }
                tbody {
                    jobs.forEach { job -> jobRow(job, session.csrf) }
                }
            }
        }
        call.respondText(html, ContentType.Text.Html)
    }

    post("/jobs/{id}/retry") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        val id = UUID.fromString(call.parameters["id"])
        val force = params["force"] == "1"
        dbTx { c ->
            val job = services.queue.byId(c, id) ?: return@dbTx
            if (force && job.kind == JOB_INGEST) {
                val payload = json.decodeFromString<IngestPayload>(job.payload).copy(force = true)
                c.prepareStatement("update jobs set payload = ?::jsonb where id = ?").use { st ->
                    st.setString(1, json.encodeToString(payload))
                    st.setObject(2, id)
                    st.executeUpdate()
                }
            }
            services.queue.retry(c, id)
        }
        call.respondRedirect("/admin/upload")
    }

    // ---------- каталог ----------

    get("/tracks") {
        val session = call.requireAdmin() ?: return@get
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        val rows = dbRead { c ->
            if (query.length >= 2) Catalog.searchTracks(c, query, 200)
            else Catalog.listTracks(c, null, null, 100, 0)
        }
        call.page("Каталог", session) {
            h1 { +"Каталог" }
            form(action = "/admin/tracks", method = FormMethod.get) {
                div("row") {
                    textInput(name = "q") { placeholder = "поиск по названию и исполнителю"; value = query }
                    button(type = ButtonType.submit) { +"Найти" }
                }
            }
            table {
                thead {
                    tr { th { }; th { +"Исполнитель" }; th { +"Название" }; th { +"Альбом" }; th { +"Длина" }; th { } }
                }
                tbody {
                    rows.forEach { track ->
                        tr {
                            td {
                                coverUrl(cfg.publicUrl, track.coverSha, 64)?.let { url ->
                                    img(classes = "cover", src = url, alt = "")
                                }
                            }
                            td { +track.artistName }
                            td { +track.title }
                            td { +(track.albumTitle ?: "—") }
                            td { +formatDuration(track.durationMs) }
                            td {
                                a(href = "/admin/tracks/${track.id}/edit") { +"править" }
                                +" "
                                form(action = "/admin/tracks/${track.id}/delete", method = FormMethod.post, classes = "inline") {
                                    hiddenInput(name = "csrf") { value = session.csrf }
                                    button(classes = "danger", type = ButtonType.submit) { +"удалить" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    get("/tracks/{id}/edit") {
        val session = call.requireAdmin() ?: return@get
        val id = UUID.fromString(call.parameters["id"])
        val track = dbRead { c -> Catalog.trackById(c, id) }
        if (track == null) {
            call.respondRedirect("/admin/tracks")
            return@get
        }
        call.page("Правка трека", session) {
            h1 { +"${track.artistName} — ${track.title}" }
            form(action = "/admin/tracks/$id", method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = session.csrf }
                labeled("Исполнитель") { textInput(name = "artist") { value = track.artistName } }
                labeled("Название") { textInput(name = "title") { value = track.title } }
                labeled("Альбом") { textInput(name = "album") { value = track.albumTitle.orEmpty() } }
                labeled("Год") { textInput(name = "year") { value = track.year?.toString().orEmpty() } }
                labeled("Жанр") { textInput(name = "genre") { value = track.genre.orEmpty() } }
                labeled("Номер в альбоме") { textInput(name = "trackNo") { value = track.trackNo?.toString().orEmpty() } }
                div("row") {
                    style = "margin-top:14px"
                    button(classes = "primary", type = ButtonType.submit) { +"Сохранить" }
                    a(href = "/admin/tracks") { +"назад" }
                }
            }
            p("muted") {
                +"Длительность ${formatDuration(track.durationMs)}, "
                +track.renditions.joinToString { "${it.kind} ${formatBytes(it.sizeBytes)}" }
            }
        }
    }

    post("/tracks/{id}") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        val id = UUID.fromString(call.parameters["id"])
        dbTx { c ->
            Catalog.updateTrack(
                c = c,
                id = id,
                title = params["title"].orEmpty().ifBlank { "Без названия" },
                artistName = params["artist"].orEmpty().ifBlank { "Неизвестный исполнитель" },
                albumTitle = params["album"]?.takeIf { it.isNotBlank() },
                year = params["year"]?.toIntOrNull(),
                genre = params["genre"],
                trackNo = params["trackNo"]?.toIntOrNull(),
            )
        }
        call.respondRedirect("/admin/tracks")
    }

    post("/tracks/{id}/delete") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        val id = UUID.fromString(call.parameters["id"])
        dbTx { c -> Catalog.softDeleteTrack(c, id) }
        call.respondRedirect("/admin/tracks")
    }

    // ---------- исполнители ----------

    get("/artists") {
        val session = call.requireAdmin() ?: return@get
        val artists = dbRead { c ->
            c.select("select id, name from artists order by name") { rs -> rs.uuid("id") to rs.getString("name") }
        }
        val withCounts = dbRead { c -> Catalog.listArtists(c, 500, 0) }
        call.page("Исполнители", session) {
            h1 { +"Исполнители" }
            p("muted") { +"Склейка дублей: всё из первого переезжает во второго, первый удаляется." }
            form(action = "/admin/artists/merge", method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = session.csrf }
                div("row") {
                    select {
                        name = "from"
                        artists.forEach { (id, title) -> option { value = id.toString(); +title } }
                    }
                    span { +"→" }
                    select {
                        name = "into"
                        artists.forEach { (id, title) -> option { value = id.toString(); +title } }
                    }
                    button(classes = "primary", type = ButtonType.submit) { +"Склеить" }
                }
            }
            table {
                thead { tr { th { +"Имя" }; th { +"Треков" } } }
                tbody {
                    withCounts.forEach { artist ->
                        tr {
                            td { a(href = "/admin/tracks?q=${artist.name}") { +artist.name } }
                            td { +artist.trackCount.toString() }
                        }
                    }
                }
            }
        }
    }

    post("/artists/merge") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        val from = params["from"]?.let { UUID.fromString(it) }
        val into = params["into"]?.let { UUID.fromString(it) }
        if (from != null && into != null) dbTx { c -> Catalog.mergeArtists(c, from, into) }
        call.respondRedirect("/admin/artists")
    }

    // ---------- пользователи и инвайты ----------

    get("/users") {
        val session = call.requireAdmin() ?: return@get
        val users = dbRead { c -> Users.list(c) }
        val invites = dbRead { c -> Users.listInvites(c) }
        call.page("Пользователи", session) {
            h1 { +"Пользователи" }
            table {
                thead { tr { th { +"Почта" }; th { +"Имя" }; th { +"Роль" }; th { +"Статус" }; th { } } }
                tbody {
                    users.forEach { user ->
                        tr {
                            td { +user.email }
                            td { +user.displayName }
                            td { +user.role }
                            td { +(if (user.disabledAt == null) "активен" else "заблокирован") }
                            td {
                                form(action = "/admin/users/${user.id}/toggle", method = FormMethod.post, classes = "inline") {
                                    hiddenInput(name = "csrf") { value = session.csrf }
                                    button(type = ButtonType.submit) {
                                        +(if (user.disabledAt == null) "заблокировать" else "разблокировать")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            h2 { +"Инвайты" }
            form(action = "/admin/invites", method = FormMethod.post) {
                hiddenInput(name = "csrf") { value = session.csrf }
                div("row") {
                    textInput(name = "note") { placeholder = "для кого (необязательно)" }
                    textInput(name = "days") { placeholder = "срок, дней"; style = "width:120px" }
                    button(classes = "primary", type = ButtonType.submit) { +"Выпустить код" }
                }
            }
            table {
                thead { tr { th { +"Код" }; th { +"Кому" }; th { +"Выпущен" }; th { +"Состояние" }; th { } } }
                tbody {
                    invites.forEach { invite ->
                        tr {
                            td { +invite.code }
                            td { +(invite.note ?: "—") }
                            td { +timeFormat.format(invite.createdAt) }
                            td {
                                +when {
                                    invite.usedAt != null -> "использован"
                                    invite.expiresAt != null && invite.expiresAt.isBefore(Instant.now()) -> "просрочен"
                                    else -> "свободен"
                                }
                            }
                            td {
                                if (invite.usedAt == null) {
                                    form(action = "/admin/invites/delete", method = FormMethod.post, classes = "inline") {
                                        hiddenInput(name = "csrf") { value = session.csrf }
                                        hiddenInput(name = "code") { value = invite.code }
                                        button(classes = "danger", type = ButtonType.submit) { +"отозвать" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    post("/users/{id}/toggle") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        val id = UUID.fromString(call.parameters["id"])
        dbTx { c ->
            val user = Users.findById(c, id) ?: return@dbTx
            Users.setDisabled(c, id, user.disabledAt == null)
        }
        call.respondRedirect("/admin/users")
    }

    post("/invites") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        dbTx { c ->
            Users.createInvite(
                c,
                UUID.fromString(session.userId),
                params["note"]?.takeIf { it.isNotBlank() },
                params["days"]?.toLongOrNull(),
            )
        }
        call.respondRedirect("/admin/users")
    }

    post("/invites/delete") {
        val session = call.requireAdmin() ?: return@post
        val params = call.receiveParameters()
        if (params["csrf"] != session.csrf) forbidden()
        params["code"]?.let { code -> dbTx { c -> Users.deleteInvite(c, code) } }
        call.respondRedirect("/admin/users")
    }
}

// ---------- вспомогательное ----------

private suspend fun ApplicationCall.requireAdmin(): AdminSession? {
    val session = sessions.get<AdminSession>()
    if (session == null || !isAdmin(session)) {
        sessions.clear<AdminSession>()
        respondRedirect("/admin/login")
        return null
    }
    return session
}

/** То же, но для XHR: редирект в ответ на fetch бесполезен, отвечаем кодом. */
private suspend fun ApplicationCall.requireAdminApi(): AdminSession? {
    val session = sessions.get<AdminSession>()
    if (session == null || !isAdmin(session)) {
        respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        return null
    }
    return session
}

private suspend fun isAdmin(session: AdminSession): Boolean {
    val id = runCatching { UUID.fromString(session.userId) }.getOrNull() ?: return false
    val user = dbRead { c -> Users.findById(c, id) }
    return user != null && user.role == "admin" && user.disabledAt == null
}

private suspend fun ApplicationCall.page(
    pageTitle: String,
    session: AdminSession,
    block: FlowContent.() -> Unit,
) = respondHtml {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +"Vitz Music — $pageTitle" }
        link(rel = "stylesheet", href = "/admin/app.css")
    }
    body {
        attributes["data-csrf"] = session.csrf
        header {
            span("brand") { +"Vitz Music" }
            nav {
                a(href = "/admin") { +"Сводка" }
                a(href = "/admin/upload") { +"Загрузка" }
                a(href = "/admin/tracks") { +"Каталог" }
                a(href = "/admin/artists") { +"Исполнители" }
                a(href = "/admin/users") { +"Пользователи" }
            }
            div("spacer")
            form(action = "/admin/logout", method = FormMethod.post, classes = "inline") {
                hiddenInput(name = "csrf") { value = session.csrf }
                button(type = ButtonType.submit) { +"Выйти" }
            }
        }
        main { block() }
        script(src = "/admin/app.js") {}
    }
}

private fun FlowContent.labeled(label: String, block: FlowContent.() -> Unit) {
    div("row") {
        style = "margin-top:10px"
        span("muted") { style = "width:150px"; +label }
        block()
    }
}

private fun kotlinx.html.TBODY.jobRow(job: JobRow, csrf: String) {
    val payload = runCatching { json.decodeFromString<IngestPayload>(job.payload) }.getOrNull()
    tr {
        td { +timeFormat.format(job.createdAt) }
        td { +(payload?.name ?: job.kind) }
        td { span("pill ${job.status}") { +job.status } }
        td {
            val summary = job.error ?: job.result ?: job.log.trim().lines().lastOrNull().orEmpty()
            span("log") { +summary.take(200) }
        }
        td {
            if (job.status == "failed" || job.status == "needs_attention") {
                form(action = "/admin/jobs/${job.id}/retry", method = FormMethod.post, classes = "inline") {
                    hiddenInput(name = "csrf") { value = csrf }
                    hiddenInput(name = "force") { value = "1" }
                    button(type = ButtonType.submit) { +"всё равно добавить" }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes Б"
}
