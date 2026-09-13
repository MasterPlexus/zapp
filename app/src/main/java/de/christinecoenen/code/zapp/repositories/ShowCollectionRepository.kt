package de.christinecoenen.code.zapp.repositories

import de.christinecoenen.code.zapp.app.mediathek.api.IMediathekApiService
import de.christinecoenen.code.zapp.app.mediathek.api.request.QueryRequest
import de.christinecoenen.code.zapp.app.settings.repository.SettingsRepository
import de.christinecoenen.code.zapp.models.collections.ShowCollection
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
					countUpdatedAt = if (queryChanged) null else existing.countUpdatedAt
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
		val markedCount = updateAllFoundShows(collection) { show ->
			mediathekRepository.insertOrUpdateShow(show)
			mediathekRepository.markAsPlayedIfUnwatched(show.apiId) > 0
		}

		// update the cached counters
		database.showCollectionDao().getFromIdSync(collection.id)?.let { refreshCount(it) }

		markedCount
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
		val markedCount = updateAllFoundShows(collection) { show ->
			// shows that are not stored locally cannot be watched
			mediathekRepository.resetPlaybackPositionIfPlayed(show.apiId) > 0
		}

		// update the cached counters
		database.showCollectionDao().getFromIdSync(collection.id)?.let { refreshCount(it) }

		markedCount
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
	): Int {
		val handledApiIds = mutableSetOf<String>()
		var changedCount = 0
		var offset = 0
		val maxProcessedShows = settingsRepository.maxProcessedShows

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
				if (collection.isExcluded(show) || !handledApiIds.add(show.apiId)) {
					continue
				}

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
				break
			}

			offset += page.size
		}

		return changedCount
	}

	/**
	 * Recalculates the unwatched/total counters of a collection. The total number of
	 * found shows is only fetched from the mediatheks when the cached value is stale,
	 * the number of watched shows is always recalculated.
	 */
	private suspend fun refreshCount(collection: ShowCollection) {
		val watchedCount = database
			.mediathekShowDao()
			.getWatchedShows()
			.count { persistedShow -> collection.matches(persistedShow.mediathekShow) }

		val storedUpdatedAt = collection.countUpdatedAt
		val isStale = storedUpdatedAt == null ||
			storedUpdatedAt.plusHours(COUNT_MAX_AGE_HOURS).isBeforeNow()

		val totalCount = if (isStale) {
			val fetchedTotalCount = try {
				searchMediathekTotalCount(collection.searchQuery)
			} catch (e: Exception) {
				Timber.e(e, "Could not load show count of collection ${collection.id}")
				null
			}

			fetchedTotalCount ?: return
		} else {
			collection.totalCount
		}

		val unwatchedCount = (totalCount - watchedCount).coerceAtLeast(0)

		if (isStale ||
			totalCount != collection.totalCount ||
			unwatchedCount != collection.unwatchedCount
		) {
			database.showCollectionDao().updateCounts(
				collection.id,
				totalCount,
				unwatchedCount,
				DateTime.now()
			)
		}
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
