package ca.ilianokokoro.umihi.music.ui.screens.home

import ca.ilianokokoro.umihi.music.core.youtube.HomeShelf
import ca.ilianokokoro.umihi.music.models.Song

data class HomeState(
    /** Named shelves mapped to stable section keys, with deduplication across sections. */
    val shelves: List<HomeShelf> = emptyList(),
    val shelvesLoading: Boolean = true,
    /** True when the network fetch failed or timed out — show error/retry, not skeleton. */
    val shelvesError: Boolean = false,
    /** Whether device is currently offline. */
    val isOnline: Boolean = true,
    /** Top picks for the hero carousel — deduplicated, requires >=2 items to show. */
    val heroPicks: List<Song> = emptyList(),
)