package au.com.shiftyjelly.pocketcasts.player.view.chapters

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTrackerWrapper
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.models.to.Chapter
import au.com.shiftyjelly.pocketcasts.player.view.PlayerContainerFragment
import au.com.shiftyjelly.pocketcasts.player.view.chapters.ChaptersViewModel.NavigationState
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingFlow
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingLauncher
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingUpgradeSource
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.ui.theme.ThemeColor
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureTier
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.views.helper.UiUtil
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class ChaptersFragment : BaseFragment() {

    @Inject lateinit var analyticsTracker: AnalyticsTrackerWrapper

    private val chaptersViewModel: ChaptersViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = ComposeView(requireContext()).apply {
        setContent {
            val uiState by chaptersViewModel.uiState.collectAsStateWithLifecycle()

            AppTheme(Theme.ThemeType.DARK) {
                ChaptersThemeForPlayer(theme, uiState.podcast) {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

                    val lazyListState = rememberLazyListState()
                    val context = LocalContext.current
                    val currentView = LocalView.current

                    val scrollToChapter by chaptersViewModel.scrollToChapterState.collectAsState()
                    LaunchedEffect(scrollToChapter) {
                        scrollToChapter?.let {
                            delay(250)
                            lazyListState.animateScrollToItem(it.index - 1)

                            // Need to clear this so that if the user taps on the same chapter a second time we
                            // still get the scrollTo behavior.
                            chaptersViewModel.setScrollToChapter(null)
                        }
                    }

                    LaunchedEffect(Unit) {
                        chaptersViewModel
                            .navigationState
                            .collectLatest { event ->
                                when (event) {
                                    is NavigationState.StartUpsell -> startUpsell()
                                }
                            }
                    }

                    LaunchedEffect(Unit) {
                        chaptersViewModel
                            .snackbarMessage
                            .collectLatest { message ->
                                Snackbar.make(currentView, context.getString(message), Snackbar.LENGTH_SHORT)
                                    .setBackgroundTint(ThemeColor.playerContrast01(Theme.ThemeType.DARK))
                                    .setTextColor(ThemeColor.playerBackground01(Theme.ThemeType.DARK, theme.playerBackgroundColor(uiState.podcast)))
                                    .show()
                            }
                    }

                    Surface(modifier = Modifier.nestedScroll(rememberNestedScrollInteropConnection())) {
                        ChaptersPage(
                            lazyListState = lazyListState,
                            chapters = uiState.displayChapters,
                            showHeader = uiState.showHeader,
                            totalChaptersCount = uiState.totalChaptersCount,
                            onSelectionChange = { selected, chapter -> chaptersViewModel.onSelectionChange(selected, chapter) },
                            onChapterClick = ::onChapterClick,
                            onUrlClick = ::onUrlClick,
                            onSkipChaptersClick = { chaptersViewModel.onSkipChaptersClick(it) },
                            isTogglingChapters = uiState.isTogglingChapters,
                            showSubscriptionIcon = uiState.showSubscriptionIcon,
                        )
                    }
                }
            }
        }
    }

    private fun onChapterClick(chapter: Chapter, isPlaying: Boolean) {
        analyticsTracker.track(AnalyticsEvent.PLAYER_CHAPTER_SELECTED)
        if (isPlaying) {
            showPlayer()
        } else {
            chaptersViewModel.skipToChapter(chapter)
        }
    }

    private fun onUrlClick(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            UiUtil.displayAlertError(requireContext(), getString(LR.string.player_open_url_failed, url), null)
        }
    }

    private fun showPlayer() {
        (parentFragment as? PlayerContainerFragment)?.openPlayer()
    }

    private fun startUpsell() {
        val source = OnboardingUpgradeSource.SKIP_CHAPTERS
        val onboardingFlow = OnboardingFlow.Upsell(
            source = source,
            showPatronOnly = Feature.DESELECT_CHAPTERS.tier == FeatureTier.Patron ||
                Feature.DESELECT_CHAPTERS.isCurrentlyExclusiveToPatron(),
        )
        OnboardingLauncher.openOnboardingFlow(activity, onboardingFlow)
    }
}
