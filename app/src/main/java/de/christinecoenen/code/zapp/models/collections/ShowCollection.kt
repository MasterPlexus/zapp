package de.christinecoenen.code.zapp.models.collections

import androidx.room.Entity
import androidx.room.PrimaryKey
import de.christinecoenen.code.zapp.models.shows.MediathekShow
import org.joda.time.DateTime

/**
 * A named collection of shows that is defined by a mediathek search query.
 *
 * Whenever the collection is opened, the stored [searchQuery] is used to
 * search the mediatheks and the results are shown to the user.
 */
@Entity
data class ShowCollection(

	@PrimaryKey(autoGenerate = true)
	var id: Int = 0,

	var name: String,

	var searchQuery: String,

	/**
	 * Comma separated terms. Shows matching one of these terms are excluded from
	 * the collection and will not be shown.
	 */
	var excludeTerms: String = "",

	/**
	 * Total number of shows found for [searchQuery] in the mediatheks.
	 */
	var totalCount: Int = 0,

	/**
	 * Number of found shows that have not been watched yet.
	 */
	var unwatchedCount: Int = 0,

	/**
	 * Time the cached counts have been calculated for the last time - null if never.
	 */
	var countUpdatedAt: DateTime? = null,

	/**
	 * Timestamp (seconds) of the newest show that was seen for this collection. Used to
	 * detect new shows. 0 means "not checked yet".
	 */
	var lastKnownShowTimestamp: Int = 0,

	var createdAt: DateTime = DateTime.now()
)

/**
 * The single exclusion terms contained in [ShowCollection.excludeTerms].
 */
val ShowCollection.excludedSearchTerms: List<String>
	get() = excludeTerms
		.split(',', ';', '\n')
		.map { it.trim() }
		.filter { it.isNotEmpty() }

/**
 * Whether the given show matches one of the collection's exclusion terms and
 * should therefore not be shown.
 */
fun ShowCollection.isExcluded(show: MediathekShow): Boolean {
	return excludedSearchTerms.any { term ->
		show.title.contains(term, ignoreCase = true) ||
			show.topic.contains(term, ignoreCase = true)
	}
}

/**
 * Whether the given show belongs to this collection: it must match all words of
 * [ShowCollection.searchQuery] in title or topic and must not be excluded.
 *
 * This mirrors the way the mediathek search works (all words must match) and is
 * unicode aware, unlike a SQL "LIKE" query.
 */
fun ShowCollection.matches(show: MediathekShow): Boolean {
	if (isExcluded(show)) {
		return false
	}

	val searchWords = searchQuery
		.split(' ', '\t', '\n')
		.map { it.trim() }
		.filter { it.isNotEmpty() }

	if (searchWords.isEmpty()) {
		return false
	}

	return searchWords.all { word ->
		show.title.contains(word, ignoreCase = true) ||
			show.topic.contains(word, ignoreCase = true)
	}
}
