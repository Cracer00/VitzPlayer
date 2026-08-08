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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LibraryTab { ALL, LIKED }

class LibraryViewModel(private val catalog: CatalogRepository) : ViewModel() {

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

    init {
        reload()
        loadPlaylists()
    }

    fun switchTab(next: LibraryTab) {
        if (tab == next) return
        tab = next
        reload()
    }

    fun reload() {
        tracks.clear()
        endReached = false
        error = null
        loadMore()
    }

    /** Подгрузка следующей страницы. Вызывается, когда список докручен почти до конца. */
    fun loadMore() {
        if (loading || endReached) return
        loading = true
        viewModelScope.launch {
            runCatching {
                val filter = libraryQuery.trim().takeIf { it.isNotEmpty() }
                when (tab) {
                    LibraryTab.ALL -> catalog.tracks(offset = tracks.size, query = filter)
                    LibraryTab.LIKED -> catalog.likedTracks(offset = tracks.size, query = filter)
                }
            }.onSuccess { page ->
                tracks.addAll(page.items)
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
        fun factory(catalog: CatalogRepository) = viewModelFactory {
            initializer { LibraryViewModel(catalog) }
        }
    }
}
