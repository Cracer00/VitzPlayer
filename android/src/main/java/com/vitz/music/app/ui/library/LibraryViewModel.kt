package com.vitz.music.app.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitz.music.api.PlaylistDto
import com.vitz.music.api.SearchResponse
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.CatalogRepository
import com.vitz.music.app.data.Downloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

enum class LibraryTab { ALL, LIKED }

class LibraryViewModel(
    private val catalog: CatalogRepository,
    private val downloads: Downloads,
) : ViewModel() {

    /** Что уже лежит на устройстве: список перечитывается после каждой загрузки и удаления. */
    var downloadedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Треки, которые качаются прямо сейчас, — чтобы показать это в строке. */
    var downloading by mutableStateOf<Set<String>>(emptySet())
        private set

    val tracks = mutableStateListOf<TrackDto>()
    var playlists by mutableStateOf<List<PlaylistDto>>(emptyList())
        private set
    var tab by mutableStateOf(LibraryTab.ALL)
        private set
    var total by mutableStateOf(0L)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf("")
        private set
    var searchResult by mutableStateOf<SearchResponse?>(null)
        private set

    /** Фильтр внутри вкладки — отдельный от полноценного поиска на своей вкладке. */
    var libraryQuery by mutableStateOf("")
        private set

    private var searchJob: Job? = null
    private var filterJob: Job? = null
    private var endReached = false

    /** Номер последнего запроса: ответ, который обогнали, не должен переписывать список. */
    private var requestId = 0

    init {
        reload()
        loadPlaylists()
        refreshDownloads()
        observeMirror()
    }

    /**
     * Подписка на изменения зеркала.
     *
     * Без неё список остаётся снимком, сделанным при открытии экрана: синхронизация приходит
     * следом за первым чтением, и новые треки не появлялись бы до смены вкладки. Пауза перед
     * перечитыванием — потому что синхронизация пишет постранично и дёргает счётчик на каждой
     * странице; [collectLatest] отменяет ожидание, если пришла следующая.
     */
    private fun observeMirror() {
        viewModelScope.launch {
            catalog.catalogRevision.drop(1).collectLatest {
                delay(SETTLE_MS)
                refreshTracks()
                loadPlaylists()
            }
        }
        viewModelScope.launch {
            catalog.downloadsRevision.drop(1).collectLatest {
                delay(SETTLE_MS)
                refreshDownloads()
            }
        }
    }

    fun switchTab(next: LibraryTab) {
        if (tab == next) return
        tab = next
        reload()
    }

    fun reload() {
        endReached = false
        error = null
        load(offset = 0, limit = CatalogRepository.PAGE_SIZE, replace = true)
    }

    /**
     * Перечитывание уже показанного куска на месте — на столько же строк, сколько загружено.
     * Список не очищаем: обновление приезжает само, и выбрасывать человека в начало каталога
     * из-за фоновой синхронизации нельзя.
     */
    private fun refreshTracks() {
        endReached = false
        load(offset = 0, limit = tracks.size.coerceAtLeast(CatalogRepository.PAGE_SIZE), replace = true)
    }

    /** Подгрузка следующей страницы. Вызывается, когда список докручен почти до конца. */
    fun loadMore() {
        if (loading || endReached) return
        load(offset = tracks.size, limit = CatalogRepository.PAGE_SIZE, replace = false)
    }

    private fun load(offset: Int, limit: Int, replace: Boolean) {
        val id = ++requestId
        loading = true
        viewModelScope.launch {
            val result = runCatching {
                val filter = libraryQuery.trim().takeIf { it.isNotEmpty() }
                when (tab) {
                    LibraryTab.ALL -> catalog.tracks(offset = offset, limit = limit, query = filter)
                    LibraryTab.LIKED -> catalog.likedTracks(offset = offset, limit = limit, query = filter)
                }
            }
            if (id != requestId) return@launch

            result.onSuccess { page ->
                if (replace) {
                    tracks.clear()
                    tracks.addAll(page.items)
                } else {
                    // Каталог мог пополниться между страницами, и окно по offset сдвинулось:
                    // повтор уронил бы LazyColumn, у которого ключ — идентификатор трека.
                    val known = tracks.mapTo(HashSet()) { it.id }
                    tracks.addAll(page.items.filter { known.add(it.id) })
                }
                total = page.total
                endReached = page.items.isEmpty() || tracks.size >= page.total
                error = null
            }.onFailure {
                error = it.message ?: "Не удалось получить каталог"
            }
            loading = false
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            runCatching { catalog.playlists() }.onSuccess { playlists = it }
        }
    }

    fun createPlaylist(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching { catalog.createPlaylist(title) }
                .onSuccess { playlists = playlists + it }
                .onFailure { error = it.message }
        }
    }

    /**
     * Лайк переключается сразу в списке, до ответа сервера: ждать сети ради галочки незачем.
     * Если запрос не прошёл — возвращаем как было, иначе список врёт о том, что сохранено.
     */
    fun toggleLike(track: TrackDto) {
        val index = tracks.indexOfFirst { it.id == track.id }
        val desired = !track.liked
        if (index >= 0) tracks[index] = tracks[index].copy(liked = desired)
        viewModelScope.launch {
            runCatching { catalog.setLiked(track.id, desired) }
                .onFailure {
                    if (index >= 0 && index < tracks.size) tracks[index] = tracks[index].copy(liked = !desired)
                    error = "Не удалось сохранить отметку"
                }
        }
    }

    /**
     * Фильтр вкладки уходит на сервер, а не режет уже загруженную страницу: нужное может
     * лежать на сотой странице, и фильтр по видимому куску врал бы «ничего не найдено».
     */
    fun onLibraryQueryChange(next: String) {
        libraryQuery = next
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun refreshDownloads() {
        viewModelScope.launch { downloadedIds = downloads.downloadedIds() }
    }

    /**
     * Скачать или удалить с устройства. Повторное нажатие на скачанном треке удаляет файл:
     * отдельная кнопка удаления в списке заняла бы место, а действие обратное и очевидное.
     */
    fun toggleDownload(track: TrackDto) {
        if (track.id in downloading) return
        viewModelScope.launch {
            if (track.id in downloadedIds) {
                downloads.remove(track.id)
            } else {
                downloading = downloading + track.id
                downloads.download(track)
                downloading = downloading - track.id
            }
            downloadedIds = downloads.downloadedIds()
        }
    }

    fun onQueryChange(next: String) {
        query = next
        searchJob?.cancel()
        if (next.trim().length < 2) {
            searchResult = null
            return
        }
        // Пауза перед запросом: иначе на каждую букву уходит обращение к серверу.
        searchJob = viewModelScope.launch {
            delay(300)
            runCatching { catalog.search(next.trim()) }
                .onSuccess { searchResult = it }
                .onFailure { error = it.message }
        }
    }

    companion object {
        /** Сколько ждать после сигнала об изменении зеркала, прежде чем перечитывать. */
        private const val SETTLE_MS = 400L

        fun factory(catalog: CatalogRepository, downloads: Downloads) = viewModelFactory {
            initializer { LibraryViewModel(catalog, downloads) }
        }
    }
}
