package de.christinecoenen.code.zapp.repositories

import de.christinecoenen.code.zapp.app.mediathek.api.IMediathekApiService
import de.christinecoenen.code.zapp.app.mediathek.api.request.QueryRequest
import de.christinecoenen.code.zapp.app.settings.repository.SettingsRepository
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.models.collections.excludedSearchTerms
import de.christinecoenen.code.zapp.models.collections.isExcluded
import de.christinecoenen.code.zapp.models.collections.matches
import de.christinecoenen.code.zapp.models.shows.MediathekShow
import de.christinecoenen.code.zapp.persistence.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import timber.log.Timber

/**
 * Stores user defined show collections and handles the mediathek searches
 * that belong to them.
 */
class ShowCollectionRepository(
	private val database: Database,
	private val mediathekApi: IMediathekApiService,
	private val mediathekRepository: MediathekRepository,
	private val settingsRepository: SettingsRepository,
) {

	companion object {
		/**
		 * Number of shows requested per mediathek page.
		 */
		private const val PAGE_SIZE = 100

		/**
		 * Time after which the total number of found shows is fetched again.
		 */
		private const val COUNT_MAX_AGE_HOURS = 6
	}

	private val refreshTrigger = MutableStateFlow(0)
	private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val refreshMutex = Mutex()

	/**
	 * Number of shows that are shown for a collection and how many of them are watched.
	 */
	private data class Counts(val total: Int, val watched: Int)

	/**
	 * Result of processing all shows of a collection.
	 *
	 * @param changedCount Number of shows that have been changed by the action.
	 * @param visibleCount Number of shows that are shown for the collection (excluded shows
	 *   are not counted).
	 * @param isComplete Whether the whole mediathek result set has been processed. False if
	 *   the configured limit or a network error stopped the run.
	 */
	private data class UpdateResult(
		val changedCount: Int,
		val visibleCount: Int,
		val isComplete: Boolean,
	)

	/**
	 * A collection that contains shows which have not been seen before.
	 */
	data class CollectionUpdate(val collection: ShowCollection, val newShowCount: Int)

	/**
	 * Observes all collections and keeps their unwatched/total counters up to date.
	 */
	fun getAllWithCounts(): Flow<List<ShowCollection>> {
		return combine(getAll(), refreshTrigger) { collections, _ -> collections }
			.onEach { collections -> requestCountRefreshFor(collections) }
	}

	/**
	 * Observes the most recent collections and keeps their counters up to date.
	 */
	fun getRecentWithCounts(limit: Int): Flow<List<ShowCollection>> {
		return combine(getRecent(limit), refreshTrigger) { collections, _ -> collections }
			.onEach { collections -> requestCountRefreshFor(collections) }
	}

	/**
	 * Refreshes the counters in the background, so that observers of the collection flow
	 * do not have to wait for network requests.
	 */
	private fun requestCountRefreshFor(collections: List<ShowCollection>) {
		if (collections.isEmpty()) {
			return
		}

		refreshScope.launch {
			refreshMutex.withLock {
				collections.forEach { refreshCount(it) }
			}
		}
	}

	/**
	 * Requests a refresh of all cached counters, e.g. when an overview becomes visible again.
	 */
	fun requestCountRefresh() {
		refreshTrigger.value++
	}

	fun getAll(): Flow<List<ShowCollection>> {
		return database
			.showCollectionDao()
			.getAll()
			.distinctUntilChanged()
			.flowOn(Dispatchers.IO)
	}

	fun getRecent(limit: Int): Flow<List<ShowCollection>> {
		return database
			.showCollectionDao()
			.getRecent(limit)
			.distinctUntilChanged()
			.flowOn(Dispatchers.IO)
	}

	fun getFromId(id: Int): Flow<ShowCollection?> {
		return database
			.showCollectionDao()
			.getFromId(id)
			.distinctUntilChanged()
			.flowOn(Dispatchers.IO)
	}

	suspend fun save(
		id: Int,
		name: String,
		searchQuery: String,
		excludeTerms: String,
	) = withContext(Dispatchers.IO) {
		val dao = database.showCollectionDao()
		val trimmedName = name.trim()
		val trimmedQuery = searchQuery.trim()
		val trimmedExcludeTerms = excludeTerms.trim()

		if (id == 0) {
			dao.insert(
				ShowCollection(
					name = trimmedName,
					searchQuery = trimmedQuery,
					excludeTerms = trimmedExcludeTerms
				)
			)
		} else {
			val existing = dao.getFromIdSync(id) ?: return@withContext
			val queryChanged = existing.searchQuery != trimmedQuery ||
				existing.excludeTerms != trimmedExcludeTerms

			dao.update(
				existing.copy(
					name = trimmedName,
					searchQuery = trimmedQuery,
					excludeTerms = trimmedExcludeTerms,
					totalCount = if (queryChanged) 0 else existing.totalCount,
					unwatchedCount = if (queryChanged) 0 else existing.unwatchedCount,
					countUpdatedAt = if (queryChanged) null else existing.countUpdatedAt,
					lastKnownShowTimestamp =
						if (queryChanged) 0 else existing.lastKnownShowTimestamp
				)
			)
		}
	}

	suspend fun delete(collection: ShowCollection) = withContext(Dispatchers.IO) {
		database.showCollectionDao().delete(collection)
	}

	/**
	 * Searches the mediatheks for the collection query and marks all found
	 * shows as watched. Shows that are already marked as watched are skipped.
	 *
	 * The mediathek search is paginated, so *all* results are processed, not only
	 * the first page.
	 *
	 * @return The number of shows that have been marked as watched.
	 */
	suspend fun markAllAsWatched(collection: ShowCollection): Int = withContext(Dispatchers.IO) {
		val result = updateAllFoundShows(collection) { show ->
			mediathekRepository.insertOrUpdateShow(show)
			mediathekRepository.markAsPlayedIfUnwatched(show.apiId) > 0
		}

		updateCountsAfterAction(collection, result)

		result.changedCount
	}

	/**
	 * Searches the mediatheks for the collection query and marks all found
	 * shows as unwatched. Shows that are not watched are skipped.
	 *
	 * The mediathek search is paginated, so *all* results are processed, not only
	 * the first page.
	 *
	 * @return The number of shows that have been marked as unwatched.
	 */
	suspend fun markAllAsUnwatched(collection: ShowCollection): Int = withContext(Dispatchers.IO) {
		val result = updateAllFoundShows(collection) { show ->
			// shows that are not stored locally cannot be watched
			mediathekRepository.resetPlaybackPositionIfPlayed(show.apiId) > 0
		}

		updateCountsAfterAction(collection, result)

		result.changedCount
	}

	/**
	 * Checks all collections for shows that are newer than the last known one and updates
	 * the stored state. The first check of a collection only stores the current state and
	 * does not report anything.
	 *
	 * @return All collections that contain new shows.
	 */
	suspend fun findCollectionsWithNewShows(): List<CollectionUpdate> = withContext(Dispatchers.IO) {
		val updates = mutableListOf<CollectionUpdate>()

		for (collection in database.showCollectionDao().getAllSync()) {
			try {
				val newShowCount = findNewShowCount(collection)
				if (newShowCount > 0) {
					updates += CollectionUpdate(collection, newShowCount)
				}
			} catch (e: Exception) {
				Timber.e(e, "Could not check collection ${collection.id} for new shows")
			}
		}

		updates
	}

	/**
	 * Number of shows of the collection that are newer than the last known one.
	 *
	 * The mediathek results are sorted by timestamp (newest first), so paging can stop at
	 * the first already known show.
	 */
	private suspend fun findNewShowCount(collection: ShowCollection): Int {
		if (collection.lastKnownShowTimestamp <= 0) {
			// first check: only remember the current state, the user just created the collection
			val newestTimestamp = searchMediathek(collection.searchQuery, 1)
				.firstOrNull()
				?.timestamp
				?: return 0

			if (newestTimestamp > 0) {
				database.showCollectionDao()
					.updateLastKnownShowTimestamp(collection.id, newestTimestamp)
			}

			return 0
		}

		val handledApiIds = mutableSetOf<String>()
		val maxProcessedShows = settingsRepository.maxProcessedShows
		var newShowCount = 0
		var newestTimestamp = 0
		var offset = 0
		var reachedKnownShow = false

		while (!reachedKnownShow && offset < maxProcessedShows) {
			val page = searchMediathek(collection.searchQuery, PAGE_SIZE, offset)

			if (page.isEmpty()) {
				break
			}

			for (show in page) {
				if (!handledApiIds.add(show.apiId)) {
					continue
				}

				if (show.timestamp > newestTimestamp) {
					newestTimestamp = show.timestamp
				}

				if (show.timestamp <= collection.lastKnownShowTimestamp) {
					reachedKnownShow = true
					break
				}

				if (!collection.isExcluded(show)) {
					newShowCount++
				}
			}

			if (page.size < PAGE_SIZE) {
				break
			}

			offset += page.size
		}

		if (newestTimestamp > collection.lastKnownShowTimestamp) {
			database.showCollectionDao()
				.updateLastKnownShowTimestamp(collection.id, newestTimestamp)
		}

		return newShowCount
	}

	/**
	 * Pages through all mediathek results of the given collection and calls [update] for
	 * every show. Excluded shows and duplicates are skipped.
	 *
	 * @return The number of shows for which [update] returned true.
	 */
	private suspend fun updateAllFoundShows(
		collection: ShowCollection,
		update: suspend (MediathekShow) -> Boolean,
	): UpdateResult {
		val handledApiIds = mutableSetOf<String>()
		val maxProcessedShows = settingsRepository.maxProcessedShows
		var changedCount = 0
		var visibleCount = 0
		var offset = 0
		var isComplete = false

		while (offset < maxProcessedShows) {
			val page = try {
				searchMediathek(
					searchQuery = collection.searchQuery,
					size = PAGE_SIZE,
					offset = offset
				)
			} catch (e: Exception) {
				Timber.e(e, "Could not load shows of collection ${collection.id} (offset $offset)")
				break
			}

			if (page.isEmpty()) {
				isComplete = true
				break
			}

			for (show in page) {
				if (collection.isExcluded(show) || !handledApiIds.add(show.apiId)) {
					continue
				}

				visibleCount++

				try {
					if (update(show)) {
						changedCount++
					}
				} catch (e: Exception) {
					Timber.e(e, "Could not update show ${show.apiId}")
				}
			}

			if (page.size < PAGE_SIZE) {
				// last page reached
				isComplete = true
				break
			}

			offset += page.size
		}

		return UpdateResult(changedCount, visibleCount, isComplete)
	}

	/**
	 * Updates the cached counters of a collection after "mark all as watched/unwatched".
	 * If all shows have been processed, the result of that run is used directly instead of
	 * the cached total, so the counters are correct immediately.
	 */
	private suspend fun updateCountsAfterAction(
		collection: ShowCollection,
		result: UpdateResult,
	) {
		if (!result.isComplete) {
			// only partial knowledge - fall back to a regular refresh
			database.showCollectionDao().getFromIdSync(collection.id)?.let { refreshCount(it) }
			return
		}

		val watchedCount = countWatchedShowsOf(collection)
		val unwatchedCount = (result.visibleCount - watchedCount).coerceAtLeast(0)

		if (result.visibleCount != collection.totalCount ||
			unwatchedCount != collection.unwatchedCount
		) {
			database.showCollectionDao().updateCounts(
				collection.id,
				result.visibleCount,
				unwatchedCount,
				DateTime.now()
			)
		}
	}

	/**
	 * Recalculates the unwatched/total counters of a collection. The total number of
	 * found shows is only fetched from the mediatheks when the cached value is stale,
	 * the number of watched shows is always recalculated.
	 */
	private suspend fun refreshCount(collection: ShowCollection) {
		val storedUpdatedAt = collection.countUpdatedAt
		val isStale = storedUpdatedAt == null ||
			storedUpdatedAt.plusHours(COUNT_MAX_AGE_HOURS).isBeforeNow()

		val counts = if (isStale) {
			val fetchedCounts = try {
				determineCounts(collection)
			} catch (e: Exception) {
				Timber.e(e, "Could not determine counts of collection ${collection.id}")
				null
			}

			fetchedCounts ?: return
		} else {
			Counts(collection.totalCount, countWatchedShowsOf(collection))
		}

		val unwatchedCount = (counts.total - counts.watched).coerceAtLeast(0)

		if (isStale ||
			counts.total != collection.totalCount ||
			unwatchedCount != collection.unwatchedCount
		) {
			database.showCollectionDao().updateCounts(
				collection.id,
				counts.total,
				unwatchedCount,
				DateTime.now()
			)
		}
	}

	/**
	 * Determines the total number of shows that are actually shown for the collection and
	 * how many of them are already watched.
	 *
	 * Without exclusion terms the total can be taken from the mediathek answer. With
	 * exclusion terms the results have to be counted by paging through them, because the
	 * mediathek API cannot exclude anything - excluded shows must not be counted as unseen.
	 */
	private suspend fun determineCounts(collection: ShowCollection): Counts? {
		if (collection.excludedSearchTerms.isNotEmpty()) {
			return countVisibleShows(collection)
		}

		val totalCount = searchMediathekTotalCount(collection.searchQuery) ?: return null
		return Counts(totalCount, countWatchedShowsOf(collection))
	}

	/**
	 * Number of locally stored shows that are watched and belong to the collection.
	 */
	private suspend fun countWatchedShowsOf(collection: ShowCollection): Int =
		database
			.mediathekShowDao()
			.getWatchedShows()
			.count { persistedShow -> collection.matches(persistedShow.mediathekShow) }

	/**
	 * Pages through all mediathek results and counts the shows that are shown (i.e. not
	 * excluded) and how many of them are already watched.
	 */
	private suspend fun countVisibleShows(collection: ShowCollection): Counts {
		val watchedApiIds = database
			.mediathekShowDao()
			.getWatchedShows()
			.map { persistedShow -> persistedShow.mediathekShow.apiId }
			.toSet()

		val handledApiIds = mutableSetOf<String>()
		val maxProcessedShows = settingsRepository.maxProcessedShows
		var totalCount = 0
		var watchedCount = 0
		var offset = 0

		while (offset < maxProcessedShows) {
			val page = try {
				searchMediathek(
					searchQuery = collection.searchQuery,
					size = PAGE_SIZE,
					offset = offset
				)
			} catch (e: Exception) {
				Timber.e(e, "Could not load shows of collection ${collection.id} (offset $offset)")
				break
			}

			if (page.isEmpty()) {
				break
			}

			for (show in page) {
				if (!handledApiIds.add(show.apiId) || collection.isExcluded(show)) {
					continue
				}

				totalCount++
				if (show.apiId in watchedApiIds) {
					watchedCount++
				}
			}

			if (page.size < PAGE_SIZE) {
				break
			}

			offset += page.size
		}

		return Counts(totalCount, watchedCount)
	}

	private suspend fun searchMediathekTotalCount(searchQuery: String): Int? =
		mediathekApi
			.listShows(
				QueryRequest().apply {
					size = 1
					offset = 0
					setQueryString(searchQuery)
				}
			)
			.result
			?.queryInfo
			?.totalResults

	private suspend fun searchMediathek(
		searchQuery: String,
		size: Int,
		offset: Int = 0,
	) = mediathekApi
		.listShows(
			QueryRequest().apply {
				this.size = size
				this.offset = offset
				setQueryString(searchQuery)
			}
		)
		.result
		?.results
		?: emptyList()
}
