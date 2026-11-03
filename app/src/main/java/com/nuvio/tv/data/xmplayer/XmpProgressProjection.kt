package com.nuvio.tv.data.xmplayer

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/**
 * What Nuvio shows from the connected XMPlayer profile's history:
 *  • Continue Watching — started (2%+) and not finished (under 95%, XMPlayer's own rule), newest first, one per
 *    movie / episode; nothing on the profile's Dropped or Hidden lists (XMPlayer leaves those out of its own row too);
 *  • watched — movies and episodes at 95%+, for the watched marks and the episode lists;
 *  • Next Up — each show's watched episodes (season 1+) seed the next one.
 * Every lookup accepts any of a title's ids (IMDb, tmdb:, tvdb:, mal:, anilist:), so a show opened from any catalog matches.
 */
internal class XmpProgressProjection private constructor(
    val progress: List<WatchProgress>,
    val watchedItems: List<WatchedItem>,
    val watchedMovieIds: Set<String>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val showIdSiblings: Map<String, Set<String>>,
    private val episodeProgressById: Map<String, List<WatchProgress>>,
    private val seeds: List<WatchProgress>,
    private val hiddenIds: Set<String>,
    private val historyByAlias: Map<String, List<XmpHistoryEntry>>
) {
    fun nextUp(preferFurthest: Boolean): List<WatchProgress> = selectPreferredTrackingNextUpSeeds(seeds, preferFurthest)

    fun isHidden(contentId: String): Boolean = contentId in hiddenIds

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) season to episode in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    /** The history entries for a title (any alias), optionally one episode — what a removal or a mark acts on. */
    fun entriesFor(contentId: String, season: Int? = null, episode: Int? = null): List<XmpHistoryEntry> =
        historyByAlias[contentId].orEmpty().filter { season == null || episode == null || (it.season == season && it.episode == episode) }

    companion object {
        const val STARTED = 0.02
        val Empty = XmpProgressProjection(XmpSyncSnapshot(xmpProfileId = ""))

        operator fun invoke(snapshot: XmpSyncSnapshot): XmpProgressProjection {
            val entries = snapshot.history.values.toList()
            val hiddenIds = (snapshot.collectionsOf("dropped") + snapshot.collectionsOf("hidden"))
                .flatMap { list -> list.items.flatMap { it.media.aliases() } }.toSet()
            val historyByAlias = entries.flatMap { e -> e.media.aliases().map { it to e } }.groupBy({ it.first }, { it.second })
            val showIdSiblings = buildMap<String, Set<String>> {
                for (e in entries) if (e.media.type == "series") {
                    val aliases = e.media.aliases()
                    for (a in aliases) put(a, (get(a).orEmpty() + aliases))
                }
            }

            val progress = entries
                .filter { !it.completed && it.progress >= STARTED && it.media.aliases().none(hiddenIds::contains) }
                .map { it.toProgress(it.progress * 100.0, WatchProgress.SOURCE_REMOTE_PLAYBACK) }
                .groupBy { Triple(it.contentId, it.season, it.episode) }
                .map { (_, list) -> list.maxBy(WatchProgress::lastWatched) }
                .sortedByDescending(WatchProgress::lastWatched)

            val episodeProgressById = progress.filter { it.season != null && it.episode != null }
                .flatMap { item -> (showIdSiblings[item.contentId] ?: setOf(item.contentId)).map { it to item } }
                .groupBy({ it.first }, { it.second })

            val completed = entries.filter { it.completed }
            val watchedMovieIds = completed.filter { !it.isEpisode }.flatMapTo(linkedSetOf()) { it.media.aliases() }
            val watchedShowEpisodes = completed.filter { it.isEpisode }
                .flatMap { e -> e.media.aliases().map { it to (e.season!! to e.episode!!) } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, eps) -> eps.toSet() }
            val watchedItems = completed.map { e ->
                WatchedItem(
                    contentId = e.media.contentId,
                    contentType = if (e.isEpisode) "series" else "movie",
                    title = e.media.title ?: e.media.contentId,
                    season = e.season,
                    episode = e.episode,
                    watchedAt = e.lastWatchedAt,
                    poster = e.media.poster,
                    releaseInfo = e.media.year,
                    trackingProviderId = XMP_PROVIDER_ID,
                    trackingProviderItemId = e.key
                )
            }.sortedByDescending(WatchedItem::watchedAt)
            val seeds = completed.filter { it.isEpisode && (it.season ?: 0) > 0 && it.media.aliases().none(hiddenIds::contains) }
                .map { it.toProgress(100.0, WatchProgress.SOURCE_REMOTE_HISTORY) }

            return XmpProgressProjection(
                progress = progress,
                watchedItems = watchedItems,
                watchedMovieIds = watchedMovieIds,
                watchedShowEpisodes = watchedShowEpisodes,
                showIdSiblings = showIdSiblings,
                episodeProgressById = episodeProgressById,
                seeds = seeds,
                hiddenIds = hiddenIds,
                historyByAlias = historyByAlias
            )
        }

        private fun XmpHistoryEntry.toProgress(percent: Double, source: String): WatchProgress {
            val id = media.contentId
            return WatchProgress(
                contentId = id,
                contentType = if (isEpisode) "series" else "movie",
                name = media.title ?: id,
                poster = media.poster,
                backdrop = null,
                logo = null,
                videoId = if (isEpisode) "$id:$season:$episode" else id,
                season = season,
                episode = episode,
                episodeTitle = null,
                position = 0L,
                duration = 0L,
                lastWatched = lastWatchedAt,
                progressPercent = percent.toFloat().coerceIn(0f, 100f),
                source = source,
                trackingProviderId = XMP_PROVIDER_ID,
                trackingProviderItemId = key,
                completionThresholdOverride = XMP_COMPLETED.toFloat()
            )
        }
    }
}

internal const val XMP_PROVIDER_ID = "xmplayer"
