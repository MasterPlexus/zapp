package de.christinecoenen.code.zapp.repositories

import de.christinecoenen.code.zapp.app.mediathek.api.IMediathekApiService
import de.christinecoenen.code.zapp.app.mediathek.api.request.QueryRequest
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.models.collections.isExcluded
import de.christinecoenen.code.zapp.persistence.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
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
) {

	companion object {
		/**
		 * Maximum number of shows that will be marked as watched at once.
		 */
		private const val MARK_AS_WATCHED_LIMIT = 100

		/**
		 * Time after which the total number of found shows is fetched again.
		 */
		private const val COUNT_MAX_AGE_HOURS = 6
	}

	private val refreshTrigger = MutableStateFlow(0)

	/**
	 * Observes all collections and keeps their unwatched/total counters up to date.
	 */
	fun getAllWithCounts(): Flow<List<ShowCollection>> {
		return combine(getAll(), refreshTrigger) { collections, _ -> collections }
			.onEach { collections -> collections.forEach { refreshCount(it) } }
	}

	/**
	 * Observes the most recent collections and keeps their counters up to date.
	 */
	fun getRecentWithCounts(limit: Int): Flow<List<ShowCollection>> {
		return combine(getRecent(limit), refreshTrigger) { collections, _ -> collections }
			.onEach { collections -> collections.forEach { refreshCount(it) } }
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
	 * @return The number of shows that have been marked as watched.
	 */
	suspend fun markAllAsWatched(collection: ShowCollection): Int = withContext(Dispatchers.IO) {
		val shows = searchMediathek(collection.searchQuery, MARK_AS_WATCHED_LIMIT)
			.filter { show -> !collection.isExcluded(show) }

		var markedCount = 0
		for (show in shows) {
			try {
				val persistedShow = mediathekRepository.persistOrUpdateShow(show).first()

				if (persistedShow.videoDuration <= 0 ||
					persistedShow.playbackPosition >= persistedShow.videoDuration
				) {
					continue
				}

				mediathekRepository.markAsPlayed(show.apiId)
				markedCount++
			} catch (e: Exception) {
				Timber.e(e, "Could not mark show ${show.apiId} as watched")
			}
		}

		// update the cached counters
		database.showCollectionDao().getFromIdSync(collection.id)?.let { refreshCount(it) }

		markedCount
	}

	/**
	 * Recalculates the unwatched/total counters of a collection. The total number of
	 * found shows is only fetched from the mediatheks when the cached value is stale,
	 * the number of watched shows is always recalculated.
	 */
	private suspend fun refreshCount(collection: ShowCollection) {
		val watchedCount = database
			.mediathekShowDao()
			.countWatchedForQuery("%${collection.searchQuery}%")

		val storedUpdatedAt = collection.countUpdatedAt
		val isStale = storedUpdatedAt == null ||
			storedUpdatedAt.plusHours(COUNT_MAX_AGE_HOURS).isBeforeNow()

		val totalCount = if (isStale) {
			searchMediathekTotalCount(collection.searchQuery) ?: return
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

	private suspend fun searchMediathek(searchQuery: String, limit: Int) =
		mediathekApi
			.listShows(
				QueryRequest().apply {
					size = limit
					offset = 0
					setQueryString(searchQuery)
				}
			)
			.result
			?.results
			?: emptyList()
}
