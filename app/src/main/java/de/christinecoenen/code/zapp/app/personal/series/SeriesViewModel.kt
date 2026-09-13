package de.christinecoenen.code.zapp.app.personal.series

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.repositories.ShowCollectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SeriesViewModel(
	private val showCollectionRepository: ShowCollectionRepository
) : ViewModel() {

	val collectionsFlow = showCollectionRepository.getAllWithCounts()

	private val _markResult = MutableStateFlow<CollectionMarkResult?>(null)
	val markResult: LiveData<CollectionMarkResult?> = _markResult.asLiveData()

	/**
	 * Recalculates the unwatched/total counters, e.g. when the overview becomes visible again.
	 */
	fun refreshCounts() {
		showCollectionRepository.requestCountRefresh()
	}

	fun save(id: Int, name: String, searchQuery: String, excludeTerms: String) {
		viewModelScope.launch {
			showCollectionRepository.save(id, name, searchQuery, excludeTerms)
		}
	}

	fun delete(collection: ShowCollection) {
		viewModelScope.launch {
			showCollectionRepository.delete(collection)
		}
	}

	/**
	 * Marks all shows found for the collection as watched.
	 */
	fun markAllAsWatched(collection: ShowCollection) {
		viewModelScope.launch {
			_markResult.value = CollectionMarkResult(
				CollectionMarkResult.Action.WATCHED,
				showCollectionRepository.markAllAsWatched(collection)
			)
		}
	}

	/**
	 * Marks all shows found for the collection as unwatched.
	 */
	fun markAllAsUnwatched(collection: ShowCollection) {
		viewModelScope.launch {
			_markResult.value = CollectionMarkResult(
				CollectionMarkResult.Action.UNWATCHED,
				showCollectionRepository.markAllAsUnwatched(collection)
			)
		}
	}
}
