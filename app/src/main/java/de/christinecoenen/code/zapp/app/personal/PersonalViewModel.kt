package de.christinecoenen.code.zapp.app.personal

import androidx.lifecycle.ViewModel
import de.christinecoenen.code.zapp.repositories.MediathekRepository
import de.christinecoenen.code.zapp.repositories.ShowCollectionRepository


class PersonalViewModel(
	mediathekRepository: MediathekRepository,
	showCollectionRepository: ShowCollectionRepository
) : ViewModel() {

	val downloadsFlow = mediathekRepository.getDownloads(2)
	val continueWatchingFlow = mediathekRepository.getStarted(2)
	val bookmarkFlow = mediathekRepository.getBookmarked(2)

	val seriesFlow = showCollectionRepository.getRecentWithCounts(2)

}
