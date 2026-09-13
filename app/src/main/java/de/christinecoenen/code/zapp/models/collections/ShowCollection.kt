package de.christinecoenen.code.zapp.models.collections

import androidx.room.Entity
import androidx.room.PrimaryKey
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
	 * When enabled, all shows found for this collection are marked as watched.
	 */
	var markAllAsWatched: Boolean = false,

	var createdAt: DateTime = DateTime.now()
)
