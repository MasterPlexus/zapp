package de.christinecoenen.code.zapp.repositories

import de.christinecoenen.code.zapp.app.mediathek.api.IMediathekApiService
import de.christinecoenen.code.zapp.app.mediathek.api.request.QueryRequest
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.persistence.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

	suspend fun save(collection: ShowCollection) = withContext(Dispatchers.IO) {
		save(collection.id, collection.name, collection.searchQuery, collection.markAllAsWatched)
	}

	suspend fun save(
		id: Int,
		name: String,
		searchQuery: String,
		markAllAsWatched: Boolean,
	) = withContext(Dispatchers.IO) {
		val dao = database.showCollectionDao()
		val trimmedName = name.trim()
		val trimmedQuery = searchQuery.trim()

		if (id == 0) {
			dao.insert(
				ShowCollection(
					name = trimmedName,
					searchQuery = trimmedQuery,
					markAllAsWatched = markAllAsWatched
				)
			)
		} else {
			val existing = dao.getFromIdSync(id) ?: return@withContext
			dao.update(
				existing.copy(
					name = trimmedName,
					searchQuery = trimmedQuery,
					markAllAsWatched = markAllAsWatched
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

		markedCount
	}

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
