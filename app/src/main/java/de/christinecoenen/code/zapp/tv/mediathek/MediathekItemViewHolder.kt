package de.christinecoenen.code.zapp.tv.mediathek

import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import de.christinecoenen.code.zapp.app.settings.repository.SettingsRepository
import de.christinecoenen.code.zapp.databinding.TvFragmentMediathekListItemBinding
import de.christinecoenen.code.zapp.models.shows.MediathekShow
import de.christinecoenen.code.zapp.repositories.MediathekRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MediathekItemViewHolder(
	private val binding: TvFragmentMediathekListItemBinding,
	private val scope: LifecycleCoroutineScope,
) : RecyclerView.ViewHolder(binding.root), KoinComponent {

	private val mediathekRepository: MediathekRepository by inject()
	private val settingsRepository: SettingsRepository by inject()

	private var playbackPositionJob: Job? = null

	suspend fun setShow(show: MediathekShow) = withContext(Dispatchers.Main) {
		recycle()

		binding.topic.text = show.topic
		// fix layout_constraintWidth_max not be applied correctly
		binding.topic.requestLayout()

		binding.title.text = show.title
		binding.duration.text = show.formattedDuration
		binding.time.text = show.formattedTimestamp
		binding.channel.text = show.channel

		binding.root.isVisible = true
		setContentAlpha(1f)

		playbackPositionJob = scope.launch {
			mediathekRepository
				.getPlaybackPositionPercent(show.apiId)
				.collectLatest(::updatePlaybackPositionPercent)
		}
	}

	fun recycle() {
		playbackPositionJob?.cancel()
		playbackPositionJob = null
	}

	private fun updatePlaybackPositionPercent(percent: Float) {
		val isWatched = percent >= 1f
		val hideWatched = isWatched &&
			settingsRepository.watchedShowDisplay == SettingsRepository.WatchedShowDisplay.HIDE

		binding.root.isVisible = !hideWatched
		setContentAlpha(
			if (isWatched && !hideWatched) settingsRepository.watchedShowAlpha else 1f
		)
	}

	/**
	 * Applies the alpha to all content views of this item. The alpha of the root view is
	 * intentionally left untouched, because RecyclerView's item animator animates the root
	 * alpha when items are inserted and would override our value.
	 */
	private fun setContentAlpha(alpha: Float) {
		for (index in 0 until binding.root.childCount) {
			binding.root.getChildAt(index).alpha = alpha
		}
	}
}
