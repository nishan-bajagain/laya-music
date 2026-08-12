package ca.ilianokokoro.umihi.music.ui.screens.player


import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


class PlayerViewModel(application: Application) :
    AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PlayerState())
    val uiState = _uiState.asStateFlow()
    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    val playbackProgress = _playbackProgress.asStateFlow()
    private val datastoreRepository = DatastoreRepository(application)

    /** Track which controller we've registered our listener on to avoid duplicates. */
    private var registeredListenerController: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateCurrentSong()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateIsPlayingState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateIsLoadingState()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            updateQueue()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val artworkUri = mediaMetadata.artworkUri ?: return
            updateThumbnail(artworkUri)
        }
    }

    init {
        // Observe controller state — re-register listener whenever the controller changes.
        // This handles both the initial connection and any reconnection.
        viewModelScope.launch {
            PlayerManager.controllerState.collect { controller ->
                if (controller !== registeredListenerController) {
                    registeredListenerController?.removeListener(playerListener)
                    registeredListenerController = controller
                    controller?.addListener(playerListener)
                }
                if (controller != null) {
                    updateCurrentSong()
                    updateIsLoadingState()
                    updateIsPlayingState()
                }
            }
        }

        startProgressUpdate()

        viewModelScope.launch {
            PlayerManager.sleepTimerRemainingSeconds.collect { seconds ->
                _uiState.update { it.copy(sleepTimerRemainingSeconds = seconds) }
            }
        }

        viewModelScope.launch {
            PlayerManager.playbackSpeed.collect { speed ->
                _uiState.update { it.copy(playbackSpeed = speed) }
            }
        }

        viewModelScope.launch {
            val settings = datastoreRepository.getSettings()
            _uiState.update { it.copy(isLoggedIn = !settings.cookies.isEmpty()) }
        }
    }


    fun toggleLike() {
        val currentSong = _uiState.value.queue.getOrNull(_uiState.value.currentIndex) ?: return
        if (_uiState.value.isLiking) {
            return
        }

        viewModelScope.launch {
            val settings = datastoreRepository.getSettings()
            if (settings.cookies.isEmpty()) {
                return@launch
            }

            val isCurrentlyLiked = _uiState.value.isLiked
            val newLiked = !isCurrentlyLiked

            _uiState.update { it.copy(isLiked = newLiked, isLiking = true) }

            try {
                YoutubeApiClient.setLike(
                    currentSong.youtubeId,
                    liked = newLiked,
                    settings
                )

                _uiState.update { state ->
                    val updatedQueue = state.queue.toMutableList().apply {
                        val index = state.currentIndex
                        if (index in indices) {
                            set(index, this[index].copy(isLiked = newLiked))
                        }
                    }
                    state.copy(isLiked = newLiked, isLiking = false, queue = updatedQueue)
                }
            } catch (e: Exception) {
                // Revert on failure
                _uiState.update { it.copy(isLiked = isCurrentlyLiked, isLiking = false) }
                printe(message = "Failed to toggle like: ${e.message}", exception = e)
            }
        }
    }

    fun setSleepTimerSheetVisibility(show: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSleepTimerModalShown = show) }
        }
    }

    fun startSleepTimer(minutes: Int) {
        PlayerManager.startSleepTimer(minutes)
    }

    fun startSleepTimerEndOfSong() {
        PlayerManager.startSleepTimerEndOfSong()
    }

    fun cancelSleepTimer() {
        PlayerManager.cancelSleepTimer()
    }

    fun setSpeedSelectorVisibility(show: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSpeedSelectorShown = show) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        PlayerManager.setPlaybackSpeed(speed)
    }

    fun seekPlayer() {
        PlayerManager.currentController?.seekTo(_playbackProgress.value.position.toLong())
    }

    fun seek(location: Float) {
        _playbackProgress.update { it.copy(position = location) }
    }

    fun updateSeekBarHeldState(isHeld: Boolean) {
        viewModelScope.launch {
            if (_uiState.value.isSeekBarHeld == isHeld) {
                return@launch
            }


            _uiState.update {
                it.copy(
                    isSeekBarHeld = isHeld,
                )
            }
        }
    }

    fun setQueueVisibility(show: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isQueueModalShown = show
                )
            }
        }
    }

    private fun updateCurrentSong() {
        val index = PlayerManager.getCurrentIndex()
        val freshQueue = PlayerManager.getQueue()
        _playbackProgress.value = PlaybackProgress()

        _uiState.update { state ->
            val mergedQueue = mergeQueuePreservingLocalState(state.queue, freshQueue)

            state.copy(
                currentIndex = index,
                queue = mergedQueue,
                isLiked = mergedQueue.getOrNull(index)?.isLiked ?: false
            )
        }
    }

    private fun updateQueue() {
        _uiState.update { state ->
            val mergedQueue = mergeQueuePreservingLocalState(state.queue, PlayerManager.getQueue())

            state.copy(
                currentIndex = PlayerManager.getCurrentIndex(),
                queue = mergedQueue
            )
        }
    }

    /**
     * Media3 can dispatch timeline updates repeatedly while a queue is being
     * prepared. Build an ID map once instead of scanning the existing queue for
     * every fresh item.
     */
    private fun mergeQueuePreservingLocalState(
        existingQueue: List<ca.ilianokokoro.umihi.music.models.Song>,
        freshQueue: List<ca.ilianokokoro.umihi.music.models.Song>
    ): List<ca.ilianokokoro.umihi.music.models.Song> {
        val existingById = existingQueue.associateBy { it.youtubeId }
        return freshQueue.map { freshSong ->
            val existing = existingById[freshSong.youtubeId]
            if (existing != null && existing.isLiked != freshSong.isLiked) {
                freshSong.copy(isLiked = existing.isLiked)
            } else {
                freshSong
            }
        }
    }

    private fun startProgressUpdate() {
        viewModelScope.launch {
            while (true) {
                val state = _uiState.value
                val controller = PlayerManager.currentController
                val playing = PlayerManager.isPlaying

                if (!state.isSeekBarHeld && !state.isLoading) {
                    val rawPosition = controller?.currentPosition
                    val rawDuration = controller?.duration

                    val current = _playbackProgress.value

                    val safeDuration = when {
                        rawDuration == null -> current.duration
                        rawDuration == C.TIME_UNSET -> 0f
                        rawDuration <= 0 -> 0f
                        else -> rawDuration.toFloat()
                    }

                    val safePosition = when {
                        rawPosition == null -> current.position
                        rawPosition < 0 -> 0f
                        rawDuration == null || rawDuration == C.TIME_UNSET -> 0f
                        else -> rawPosition
                            .coerceAtMost(rawDuration)
                            .toFloat()
                    }.coerceIn(0f, safeDuration)

                    if (
                        safePosition != current.position ||
                        safeDuration != current.duration
                    ) {
                        _playbackProgress.value = PlaybackProgress(
                            position = safePosition,
                            duration = safeDuration
                        )
                    }
                }

                // Battery: poll every 250ms while actively playing (the seek bar
                // needs smooth updates), but back off to 2s when paused or idle.
                // This loop otherwise wakes the CPU every 250ms for the whole app
                // session — even with the player closed or in the background.
                delay(
                    if (playing) {
                        Constants.Player.PROGRESS_UPDATE_DELAY.milliseconds
                    } else {
                        2000.milliseconds
                    }
                )
            }
        }
    }

    private fun updateIsLoadingState() {
        viewModelScope.launch {
            when (PlayerManager.playbackState) {
                Player.STATE_BUFFERING -> {
                    _uiState.update {
                        it.copy(
                            isLoading = true
                        )
                    }
                }

                Player.STATE_READY -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false
                        )
                    }
                }

                else -> {
                }
            }
        }
    }

    private fun updateIsPlayingState() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPlaying = PlayerManager.isPlaying
                )
            }
        }
    }


    private fun updateThumbnail(newUri: Uri) {
        _uiState.update { state ->
            val index = state.currentIndex
            val queue = state.queue

            if (index !in queue.indices) {
                return@update state
            }

            val currentSong = queue[index]
            if (currentSong.thumbnailHref == newUri.toString()) {
                return@update state
            }

            val updatedQueue = queue.toMutableList().apply {
                set(index, currentSong.copy(thumbnailHref = newUri.toString()))
            }

            state.copy(queue = updatedQueue)
        }
    }

    override fun onCleared() {
        super.onCleared()
        registeredListenerController?.removeListener(playerListener)
        registeredListenerController = null
    }

    companion object {
        fun Factory(
            application: Application,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PlayerViewModel(application)
                }
            }
    }
}
