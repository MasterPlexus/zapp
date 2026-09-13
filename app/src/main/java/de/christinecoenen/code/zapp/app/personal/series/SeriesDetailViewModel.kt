package de.christinecoenen.code.zapp.app.personal.series

import android.text.format.DateUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import de.christinecoenen.code.zapp.app.mediathek.api.IMediathekApiService
import de.christinecoenen.code.zapp.app.mediathek.api.MediathekPagingSource
import de.christinecoenen.code.zapp.app.mediathek.api.request.QueryRequest
import de.christinecoenen.code.zapp.app.mediathek.api.result.QueryInfoResult
import de.christinecoenen.code.zapp.app.mediathek.ui.list.adapter.UiModel
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.models.shows.MediathekShow
import de.christinecoenen.code.zapp.repositories.ShowCollectionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.joda.time.DateTime

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModel(
	private val showCollectionRepository: ShowCollectionRepository,
	mediathekApi: IMediathekApiService,
	private val collectionId: Int,
) : ViewModel() {

	companion object {
		private const val ITEM_COUNT_PER_PAGE = 30
	}

	private val pagingConfig = PagingConfig(
		pageSize = ITEM_COUNT_PER_PAGE,
		enablePlaceholders = false
	)

	private val queryInfoResult = MutableStateFlow<QueryInfoResult?>(null)

	private val _collection = MutableStateFlow<ShowCollection?>(null)
	val collection: LiveData<ShowCollection?> = _collection.asLiveData()

	private val _markResult = MutableStateFlow<Int?>(null)
	val markResult: LiveData<Int?> = _markResult.asLiveData()

	val showList = showCollectionRepository
		.getFromId(collectionId)
		.filterNotNull()
		.distinctUntilChangedBy { it.searchQuery }
		.flatMapLatest { collection ->
			Pager(pagingConfig) {
				MediathekPagingSource(
					mediathekApi,
					QueryRequest().apply {
						size = ITEM_COUNT_PER_PAGE
						setQueryString(collection.searchQuery)
					},
					queryInfoResult
				)
			}.flow
		}
		.map<PagingData<MediathekShow>, PagingData<UiModel>> { pagingData ->
			pagingData.map { show ->
				UiModel.MediathekShowModel(
					show,
					DateTime(show.timestamp.toLong() * DateUtils.SECOND_IN_MILLIS)
				)
			}
		}
		.cachedIn(viewModelScope)
		.asLiveData()

	init {
		viewModelScope.launch {
			var autoMarkChecked = false

			showCollectionRepository
				.getFromId(collectionId)
				.filterNotNull()
				.collect { collection ->
					_collection.value = collection

					// automatically mark all finds as watched when the option is enabled
					if (!autoMarkChecked) {
						autoMarkChecked = true
						if (collection.markAllAsWatched) {
							_markResult.value =
								showCollectionRepository.markAllAsWatched(collection)
						}
					}
				}
		}
	}

	fun markAllAsWatched() {
		viewModelScope.launch {
			val collection = showCollectionRepository.getFromId(collectionId).firstOrNull()
				?: return@launch

			_markResult.value = showCollectionRepository.markAllAsWatched(collection)
		}
	}
}
