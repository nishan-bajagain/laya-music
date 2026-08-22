package ca.ilianokokoro.umihi.music.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.ConnectivityHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.youtube.HomeShelf
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val FETCH_TIMEOUT_MS = 15_000L

/**
 * Stable section keys that Home always tries to show, in order.
 * Each section either comes from local data or from a matching YT Music shelf.
 * If the upstream shelf doesn't exist (e.g. user has no liked songs), the
 * section is simply omitted — but the ordering and naming stay consistent.
 */
private val SECTION_ORDER = listOf(
    SectionKey.HERO_PICKS,
    SectionKey.RECENTLY_PLAYED,
    SectionKey.DAILY_MIX,
    SectionKey.QUICK_PICKS,
    SectionKey.TRENDING,
    SectionKey.POPULAR,
    SectionKey.MOST_LISTENED,
    SectionKey.LIKED_SONGS,
)

private enum class SectionKey {
    HERO_PICKS, RECENTLY_PLAYED, DAILY_MIX, QUICK_PICKS,
    TRENDING, POPULAR, MOST_LISTENED, LIKED_SONGS,
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(HomeState())
    val uiState = _uiState.asStateFlow()

    private val datastoreRepository = DatastoreRepository(application)
    private val localSongDataSource = AppDatabase.getInstance(application).songRepository()
    private var feedJob: Job? = null

    init {
        loadHomeFeed()
    }

    /**
     * Loads the full Home feed with stable sections, deduplication, and error handling.
     */
    fun loadHomeFeed() {
        // Cancel any in-flight fetch so a stale response can't overwrite a fresh one
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _uiState.update { it.copy(shelvesLoading = true, shelvesError = false) }

            val isOnline = ConnectivityHelper.isNetworkAvailable(getApplication())
            _uiState.update { it.copy(isOnline = isOnline) }

            // ── Local data (always available, online or offline) ──────────
            val mostListened = runCatching {
                localSongDataSource.getMostListenedSongs(20)
            }.getOrDefault(emptyList())

            if (!isOnline) {
                buildOfflineFeed(mostListened)
                return@launch
            }

            // ── Online: fetch YT Music shelves with timeout ────────────────
            val rawShelves = withContext(Dispatchers.IO) {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    try {
                        val settings = datastoreRepository.getSettings()
                        val responseJson = YoutubeApiClient.browse(
                            browseId = Constants.YoutubeApi.Browse.HOME_BROWSE_ID,
                            settings = settings
                        )
                        val shelves = YoutubeDataExtractor.extractHomeShelves(responseJson)
                        printd("HomeViewModel: raw shelves=${shelves.size}: ${shelves.map { "${it.title}(${it.songs.size})" }}")
                        shelves
                    } catch (e: Exception) {
                        printe("HomeViewModel: shelf fetch failed: ${e.message}", exception = e)
                        null
                    }
                }
            }

            if (rawShelves == null) {
                // Timeout or exception — show error state, not infinite skeleton
                _uiState.update {
                    it.copy(shelvesLoading = false, shelvesError = true)
                }
                return@launch
            }

            // ── Build stable, deduplicated sections ────────────────────────
            buildOnlineFeed(rawShelves, mostListened)
        }
    }

    /**
     * Builds the online feed with a stable section order and cross-section deduplication.
     * Each section maps to a [SectionKey] so the UI always shows the same sections
     * in the same order, even if YT Music's shelf titles rotate.
     */
    private fun buildOnlineFeed(rawShelves: List<HomeShelf>, mostListened: List<Song>) {
        // Map raw YT Music shelves to our stable section keys by title matching
        val shelfByTitle = rawShelves.associateBy { it.title.lowercase() }

        // Global dedup set — once a song is used in any section, it's excluded from all later ones
        val usedIds = mutableSetOf<String>()
        fun deduplicate(songs: List<Song>): List<Song> {
            return songs.filter { song ->
                if (song.youtubeId in usedIds) return@filter false
                usedIds.add(song.youtubeId)
                true
            }
        }

        // Also exclude songs already used in hero picks from all rails
        // (hero picks are built first in the UI, but we dedup here in data)

        val allSections = mutableListOf<HomeShelf>()

        for (key in SECTION_ORDER) {
            when (key) {
                SectionKey.HERO_PICKS -> {
                    // Hero picks: combine Most Listened + all shelf songs, take top 10 distinct
                    val candidates = mutableListOf<Song>()
                    candidates.addAll(mostListened)
                    rawShelves.forEach { shelf ->
                        candidates.addAll(shelf.songs)
                    }
                    val picks = deduplicate(candidates).take(10)
                    if (picks.size >= 2) {
                        allSections.add(HomeShelf(title = "__hero__", songs = picks))
                    }
                }

                SectionKey.RECENTLY_PLAYED -> {
                    val songs = deduplicate(mostListened.take(10))
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Recently Played", songs = songs))
                    }
                }

                SectionKey.DAILY_MIX -> {
                    val songs = shelfByTitle["daily mix"]?.let { deduplicate(it.songs.take(15)) }
                        ?: emptyList()
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Daily Mix", songs = songs))
                    }
                }

                SectionKey.QUICK_PICKS -> {
                    val songs = shelfByTitle["quick picks"]?.let { deduplicate(it.songs.take(15)) }
                        ?: shelfByTitle["quickpicks"]?.let { deduplicate(it.songs.take(15)) }
                        ?: emptyList()
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Quick Picks", songs = songs))
                    }
                }

                SectionKey.TRENDING -> {
                    val songs = shelfByTitle["trending"]?.let { deduplicate(it.songs.take(15)) }
                        ?: emptyList()
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Trending", songs = songs))
                    }
                }

                SectionKey.POPULAR -> {
                    val songs = shelfByTitle["popular"]?.let { deduplicate(it.songs.take(15)) }
                        ?: emptyList()
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Popular", songs = songs))
                    }
                }

                SectionKey.MOST_LISTENED -> {
                    val songs = deduplicate(mostListened.take(15))
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Most Listened", songs = songs))
                    }
                }

                SectionKey.LIKED_SONGS -> {
                    // Liked songs come from a dedicated shelf or from the "Liked Music" shelf
                    val songs = shelfByTitle["liked music"]?.let { deduplicate(it.songs.take(15)) }
                        ?: shelfByTitle["liked songs"]?.let { deduplicate(it.songs.take(15)) }
                        ?: emptyList()
                    if (songs.isNotEmpty()) {
                        allSections.add(HomeShelf(title = "Liked Songs", songs = songs))
                    }
                }
            }
        }

        // Fallback: if NO sections have content (very thin data), use all raw shelf songs
        if (allSections.none { it.songs.isNotEmpty() }) {
            val fallback = deduplicate(
                rawShelves.flatMap { it.songs }.take(15)
            )
            if (fallback.isNotEmpty()) {
                allSections.add(HomeShelf(title = "For You", songs = fallback))
            }
        }

        val heroSection = allSections.find { it.title == "__hero__" }
        val rails = allSections.filter { it.title != "__hero__" }

        _uiState.update {
            it.copy(
                shelves = rails,
                shelvesLoading = false,
                shelvesError = false,
                heroPicks = heroSection?.songs ?: emptyList()
            )
        }
    }

    /**
     * Offline feed: only local data, no network calls.
     */
    private suspend fun buildOfflineFeed(mostListened: List<Song>) {
        val usedIds = mutableSetOf<String>()
        fun deduplicate(songs: List<Song>): List<Song> = songs.filter { song ->
            if (song.youtubeId in usedIds) return@filter false
            usedIds.add(song.youtubeId)
            true
        }

        val downloaded = runCatching {
            withContext(Dispatchers.IO) {
                localSongDataSource.getDownloadedSongs().take(15)
            }
        }.getOrDefault(emptyList())

        val heroPicks = deduplicate(
            mostListened + downloaded
        ).take(10)

        val sections = mutableListOf<HomeShelf>()

        // Recently Played (local)
        val recent = deduplicate(mostListened.take(10))
        if (recent.isNotEmpty()) {
            sections.add(HomeShelf(title = "Recently Played", songs = recent))
        }

        // Most Listened (local)
        val listened = deduplicate(mostListened.take(15))
        if (listened.isNotEmpty()) {
            sections.add(HomeShelf(title = "Most Listened", songs = listened))
        }

        // Downloaded (local)
        val dl = deduplicate(downloaded)
        if (dl.isNotEmpty()) {
            sections.add(HomeShelf(title = "Downloaded", songs = dl))
        }

        _uiState.update {
            it.copy(
                shelves = sections,
                shelvesLoading = false,
                shelvesError = false,
                heroPicks = if (heroPicks.size >= 2) heroPicks else emptyList()
            )
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(application) }
        }
    }
}
