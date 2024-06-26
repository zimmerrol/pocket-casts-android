package au.com.shiftyjelly.pocketcasts.player.view.chapters

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTrackerWrapper
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.Chapter
import au.com.shiftyjelly.pocketcasts.models.to.Chapters
import au.com.shiftyjelly.pocketcasts.models.to.SubscriptionStatus
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.featureflag.UserTier
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlow
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@HiltViewModel
class ChaptersViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val settings: Settings,
    private val analyticsTracker: AnalyticsTrackerWrapper,
) : ViewModel(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    data class UiState(
        val allChapters: List<ChapterState> = emptyList(),
        val displayChapters: List<ChapterState> = emptyList(),
        val totalChaptersCount: Int = 0,
        val isTogglingChapters: Boolean = false,
        val userTier: UserTier = UserTier.Free,
        val canSkipChapters: Boolean = false,
        val podcast: Podcast? = null,
        val isSkippingToNextChapter: Boolean = false,
        val showHeader: Boolean = false,
    ) {
        val showSubscriptionIcon
            get() = !isTogglingChapters && !canSkipChapters
    }

    sealed class ChapterState {
        abstract val chapter: Chapter

        data class Played(override val chapter: Chapter) : ChapterState()
        data class Playing(val progress: Float, override val chapter: Chapter) : ChapterState()
        data class NotPlayed(override val chapter: Chapter) : ChapterState()
    }

    private val _scrollToChapterState = MutableStateFlow<Chapter?>(null)
    val scrollToChapterState = _scrollToChapterState.asStateFlow()

    fun setScrollToChapter(chapter: Chapter?) {
        _scrollToChapterState.value = chapter
    }

    private val playbackStateObservable: Observable<PlaybackState> = playbackManager.playbackStateRelay
        .observeOn(Schedulers.io())

    private val _uiState = MutableStateFlow(
        UiState(
            userTier = settings.userTier,
            canSkipChapters = canSkipChapters(settings.userTier),
        ),
    )
    val uiState: StateFlow<UiState>
        get() = _uiState

    private val _navigationState: MutableSharedFlow<NavigationState> = MutableSharedFlow()
    val navigationState = _navigationState.asSharedFlow()

    private val _snackbarMessage: MutableSharedFlow<Int> = MutableSharedFlow()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private var numberOfDeselectedChapters = 0

    init {
        viewModelScope.launch {
            combine(
                playbackStateObservable.asFlow(),
                settings.cachedSubscriptionStatus.flow,
                this@ChaptersViewModel::combineUiState,
            )
                .distinctUntilChanged()
                .stateIn(viewModelScope)
                .collectLatest {
                    _uiState.value = it
                }
        }
    }

    fun skipToChapter(chapter: Chapter) {
        launch {
            playbackManager.skipToChapter(chapter)
        }
    }

    private fun combineUiState(
        playbackState: PlaybackState,
        cachedSubscriptionStatus: SubscriptionStatus?,
    ): UiState {
        val chapters = buildChaptersWithState(
            chapters = playbackState.chapters,
            playbackPositionMs = playbackState.positionMs,
            lastChangeFrom = playbackState.lastChangeFrom,
        )
        val currentUserTier = (cachedSubscriptionStatus as? SubscriptionStatus.Paid)?.tier?.toUserTier() ?: UserTier.Free
        val lastUserTier = _uiState.value.userTier
        val canSkipChapters = canSkipChapters(currentUserTier)
        val isTogglingChapters = ((lastUserTier != currentUserTier) && canSkipChapters) || _uiState.value.isTogglingChapters

        return UiState(
            allChapters = chapters,
            displayChapters = getFilteredChaptersIfNeeded(
                chapters = chapters,
                isTogglingChapters = isTogglingChapters,
                userTier = currentUserTier,
            ),
            totalChaptersCount = chapters.size,
            isTogglingChapters = isTogglingChapters,
            userTier = currentUserTier,
            canSkipChapters = canSkipChapters,
            podcast = playbackState.podcast,
            isSkippingToNextChapter = _uiState.value.isSkippingToNextChapter,
            showHeader = (playbackManager.getCurrentEpisode()?.let { it is PodcastEpisode } ?: false) &&
                FeatureFlag.isEnabled(Feature.DESELECT_CHAPTERS),
        )
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun buildChaptersWithState(
        chapters: Chapters,
        playbackPositionMs: Int,
        lastChangeFrom: String? = null,
    ): List<ChapterState> {
        val chapterStates = mutableListOf<ChapterState>()
        var currentChapter: Chapter? = null
        for (chapter in chapters.getList()) {
            val chapterState = if (currentChapter != null) {
                // a chapter that hasn't been played
                ChapterState.NotPlayed(chapter)
            } else if (playbackPositionMs.milliseconds in chapter) {
                if (chapter.selected || !FeatureFlag.isEnabled(Feature.DESELECT_CHAPTERS)) {
                    // the chapter currently playing
                    currentChapter = chapter
                    _uiState.value = _uiState.value.copy(isSkippingToNextChapter = false)
                    val progress = chapter.calculateProgress(playbackPositionMs.milliseconds)
                    ChapterState.Playing(chapter = chapter, progress = progress)
                } else {
                    if (!listOf(
                            PlaybackManager.LastChangeFrom.OnUserSeeking.value,
                            PlaybackManager.LastChangeFrom.OnSeekComplete.value,
                        ).contains(lastChangeFrom)
                    ) {
                        if (!_uiState.value.isSkippingToNextChapter) {
                            _uiState.value = _uiState.value.copy(isSkippingToNextChapter = true)
                            playbackManager.skipToNextSelectedOrLastChapter()
                        }
                    }
                    ChapterState.NotPlayed(chapter)
                }
            } else {
                // a chapter that has been played
                ChapterState.Played(chapter)
            }
            chapterStates.add(chapterState)
        }
        return chapterStates
    }

    fun onSelectionChange(selected: Boolean, chapter: Chapter) {
        val selectedChapters = _uiState.value.allChapters.filter { it.chapter.selected }
        if (!selected && selectedChapters.size == 1) {
            viewModelScope.launch {
                _snackbarMessage.emit(LR.string.select_one_chapter_message)
            }
        } else {
            playbackManager.toggleChapter(selected, chapter)
            trackChapterSelectionToggled(selected)
        }
    }

    fun onSkipChaptersClick(checked: Boolean) {
        if (_uiState.value.canSkipChapters) {
            _uiState.value = _uiState.value.copy(
                isTogglingChapters = checked,
                displayChapters = getFilteredChaptersIfNeeded(
                    chapters = _uiState.value.allChapters,
                    isTogglingChapters = checked,
                    userTier = _uiState.value.userTier,
                ),
            )
            trackSkipChaptersToggled(checked)
        } else {
            viewModelScope.launch {
                _navigationState.emit(NavigationState.StartUpsell)
            }
        }
    }

    private fun getFilteredChaptersIfNeeded(
        chapters: List<ChapterState>,
        isTogglingChapters: Boolean,
        userTier: UserTier,
    ): List<ChapterState> {
        val shouldFilterChapters = canSkipChapters(userTier) &&
            !isTogglingChapters

        return if (shouldFilterChapters) {
            chapters.filter { it.chapter.selected }
        } else {
            chapters
        }
    }

    private fun canSkipChapters(userTier: UserTier) = FeatureFlag.isEnabled(Feature.DESELECT_CHAPTERS) &&
        Feature.isUserEntitled(Feature.DESELECT_CHAPTERS, userTier)

    private fun trackChapterSelectionToggled(selected: Boolean) {
        val currentEpisode = playbackManager.getCurrentEpisode()
        analyticsTracker.track(
            if (selected) {
                AnalyticsEvent.DESELECT_CHAPTERS_CHAPTER_SELECTED
            } else {
                AnalyticsEvent.DESELECT_CHAPTERS_CHAPTER_DESELECTED
            },
            Analytics.chapterSelectionToggled(currentEpisode),
        )
    }

    private fun trackSkipChaptersToggled(checked: Boolean) {
        val selectedChaptersCount = _uiState.value.allChapters.filter { it.chapter.selected }.size
        if (checked) {
            numberOfDeselectedChapters = selectedChaptersCount
            analyticsTracker.track(AnalyticsEvent.DESELECT_CHAPTERS_TOGGLED_ON)
        } else {
            numberOfDeselectedChapters -= selectedChaptersCount
            analyticsTracker.track(
                AnalyticsEvent.DESELECT_CHAPTERS_TOGGLED_OFF,
                Analytics.skipChaptersToggled(numberOfDeselectedChapters),
            )
        }
    }

    private object Analytics {
        private const val EPISODE_UUID = "episode_uuid"
        private const val PODCAST_UUID = "podcast_uuid"
        private const val NUMBER_OF_DESELECTED_CHAPTERS = "number_of_deselected_chapters"
        private const val UNKNOWN = "unknown"

        fun chapterSelectionToggled(episode: BaseEpisode?) =
            mapOf(
                EPISODE_UUID to (episode?.uuid ?: UNKNOWN),
                PODCAST_UUID to (episode?.podcastOrSubstituteUuid ?: UNKNOWN),
            )

        fun skipChaptersToggled(count: Int) =
            mapOf(NUMBER_OF_DESELECTED_CHAPTERS to count)
    }

    sealed class NavigationState {
        data object StartUpsell : NavigationState()
    }
}
