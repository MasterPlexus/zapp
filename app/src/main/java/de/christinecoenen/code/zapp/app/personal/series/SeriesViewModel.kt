package de.christinecoenen.code.zapp.app.personal.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.repositories.ShowCollectionRepository
import kotlinx.coroutines.launch

class SeriesViewModel(
	private val showCollectionRepository: ShowCollectionRepository
) : ViewModel() {

	val collectionsFlow = showCollectionRepository.getAll()

	fun save(id: Int, name: String, searchQuery: String, markAllAsWatched: Boolean) {
		viewModelScope.launch {
			showCollectionRepository.save(id, name, searchQuery, markAllAsWatched)
		}
	}

	fun delete(collection: ShowCollection) {
		viewModelScope.launch {
			showCollectionRepository.delete(collection)
		}
	}
}
