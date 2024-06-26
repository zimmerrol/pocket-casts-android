package au.com.shiftyjelly.pocketcasts.repositories.nova

import au.com.shiftyjelly.pocketcasts.models.db.dao.EpisodeDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.PodcastDao
import javax.inject.Inject

class NovaLauncherManagerImpl @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) : NovaLauncherManager {
    override suspend fun getSubscribedPodcasts() = podcastDao.getNovaLauncherSubscribedPodcasts()
    override suspend fun getRecentlyPlayedPodcasts() = podcastDao.getNovaLauncherRecentlyPlayedPodcasts()
    override suspend fun getTrendingPodcasts() = podcastDao.getNovaLauncherTrendingPodcasts()
    override suspend fun getNewEpisodes() = episodeDao.getNovaLauncherNewEpisodes()
    override suspend fun getInProgressEpisodes() = episodeDao.getNovaLauncherInProgressEpisodes()
}
