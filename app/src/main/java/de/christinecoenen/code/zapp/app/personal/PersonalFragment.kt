package de.christinecoenen.code.zapp.app.personal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import de.christinecoenen.code.zapp.R
import de.christinecoenen.code.zapp.app.main.MainActivity
import de.christinecoenen.code.zapp.app.mediathek.ui.helper.ShowMenuHelper
import de.christinecoenen.code.zapp.app.mediathek.ui.list.adapter.MediathekShowListItemListener
import de.christinecoenen.code.zapp.app.personal.adapter.HeaderAdapater
import de.christinecoenen.code.zapp.app.personal.adapter.LoadStatusAdapter
import de.christinecoenen.code.zapp.app.personal.adapter.MediathekShowListAdapter
import de.christinecoenen.code.zapp.app.personal.adapter.ShowCollectionListAdapter
import de.christinecoenen.code.zapp.databinding.PersonalFragmentBinding
import de.christinecoenen.code.zapp.models.collections.ShowCollection
import de.christinecoenen.code.zapp.models.shows.MediathekShow
import de.christinecoenen.code.zapp.utils.system.LifecycleOwnerHelper.launchOnCreated
import org.koin.androidx.viewmodel.ext.android.viewModel

class PersonalFragment : Fragment(), MenuProvider {

	private var _binding: PersonalFragmentBinding? = null
	private val binding: PersonalFragmentBinding get() = _binding!!

	private val viewModel: PersonalViewModel by viewModel()

	private lateinit var outerAdapter: ConcatAdapter
	private lateinit var downloadsAdapter: MediathekShowListAdapter
	private lateinit var continueWatchingAdapter: MediathekShowListAdapter
	private lateinit var bookmarkAdapter: MediathekShowListAdapter
	private lateinit var seriesAdapter: ShowCollectionListAdapter

	private val seriesHeaderAdapter = HeaderAdapater(
		R.string.activity_main_tab_series,
		R.drawable.ic_sharp_format_list_bulleted_24
	) { navigateToSeries() }
	private val downloadsHeaderAdapter = HeaderAdapater(
		R.string.activity_main_tab_downloads,
		R.drawable.ic_baseline_save_alt_24,
	) { navigateToDownloads() }
	private val continueWatchingHeaderAdapter = HeaderAdapater(
		R.string.activity_main_tab_continue_watching,
		R.drawable.ic_outline_play_circle_24
	) { navigateToContinueWatching() }
	private val bookmarksHeaderAdapter = HeaderAdapater(
		R.string.activity_main_tab_bookmarks,
		R.drawable.ic_outline_bookmarks_24
	) { navigateToBookmarks() }

	private val downloadsLoadStatusAdapter =
		LoadStatusAdapter(R.string.fragment_personal_no_results_downloads)
	private val continueWatchingLoadStatusAdapter =
		LoadStatusAdapter(R.string.fragment_personal_no_results_continue_watching)
	private val bookmarkLoadStatusAdapter =
		LoadStatusAdapter(R.string.fragment_personal_no_results_bookmarks)
	private val seriesLoadStatusAdapter =
		LoadStatusAdapter(R.string.fragment_personal_no_results_series)

	private val seriesClickListener = object : ShowCollectionListAdapter.Listener {
		override fun onCollectionClicked(collection: ShowCollection) {
			navigateToSeriesDetail(collection)
		}

		override fun onCollectionMenuClicked(collection: ShowCollection, view: View) {
			// the overview list does not offer a context menu
		}
	}

	private val showClickListener = object : MediathekShowListItemListener {
		override fun onShowClicked(show: MediathekShow) {
			navigateToShow(show)
		}

		override fun onShowLongClicked(show: MediathekShow, view: View) {
			ShowMenuHelper(this@PersonalFragment, show).apply {
				showContextMenu(view)
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		downloadsAdapter = MediathekShowListAdapter(lifecycleScope, showClickListener)
		continueWatchingAdapter = MediathekShowListAdapter(lifecycleScope, showClickListener)
		bookmarkAdapter = MediathekShowListAdapter(lifecycleScope, showClickListener)
		seriesAdapter = ShowCollectionListAdapter(false, seriesClickListener)

		// always show the "view all" button, so collections can be created from here
		seriesHeaderAdapter.setShowMoreButton(true)

		outerAdapter = ConcatAdapter(
			seriesHeaderAdapter,
			seriesAdapter,
			seriesLoadStatusAdapter,
			downloadsHeaderAdapter,
			downloadsAdapter,
			downloadsLoadStatusAdapter,
			continueWatchingHeaderAdapter,
			continueWatchingAdapter,
			continueWatchingLoadStatusAdapter,
			bookmarksHeaderAdapter,
			bookmarkAdapter,
			bookmarkLoadStatusAdapter,
		)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		_binding = PersonalFragmentBinding.inflate(inflater, container, false)

		binding.list.adapter = outerAdapter

		launchOnCreated {
			viewModel.seriesFlow.collect {
				seriesAdapter.submitList(it)
				seriesLoadStatusAdapter.onShowsLoaded(it.size)
			}
		}

		launchOnCreated {
			viewModel.downloadsFlow.collect {
				downloadsAdapter.setShows(it)
				downloadsHeaderAdapter.setShowMoreButton(it.isNotEmpty())
				downloadsLoadStatusAdapter.onShowsLoaded(it.size)
			}
		}

		launchOnCreated {
			viewModel.continueWatchingFlow.collect {
				continueWatchingAdapter.setShows(it)
				continueWatchingHeaderAdapter.setShowMoreButton(it.isNotEmpty())
				continueWatchingLoadStatusAdapter.onShowsLoaded(it.size)
			}
		}

		launchOnCreated {
			viewModel.bookmarkFlow.collect {
				bookmarkAdapter.setShows(it)
				bookmarksHeaderAdapter.setShowMoreButton(it.isNotEmpty())
				bookmarkLoadStatusAdapter.onShowsLoaded(it.size)
			}
		}

		(requireActivity() as? MainActivity)?.addMenuProviderToSearchBar(
			this,
			viewLifecycleOwner,
			Lifecycle.State.RESUMED
		)

		return binding.root
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.activity_main_toolbar, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

	private fun navigateToDownloads() {
		val directions = PersonalFragmentDirections.toDownloadsFragment()
		findNavController().navigate(directions)
	}

	private fun navigateToContinueWatching() {
		val directions = PersonalFragmentDirections.toContinueWatchingFragment()
		findNavController().navigate(directions)
	}

	private fun navigateToBookmarks() {
		val directions = PersonalFragmentDirections.toBookmarksFragment()
		findNavController().navigate(directions)
	}

	private fun navigateToSeries() {
		val directions = PersonalFragmentDirections.toSeriesFragment()
		findNavController().navigate(directions)
	}

	private fun navigateToSeriesDetail(collection: ShowCollection) {
		val directions = PersonalFragmentDirections.toSeriesDetailFragment(collectionId = collection.id)
		findNavController().navigate(directions)
	}

	private fun navigateToShow(show: MediathekShow) {
		val directions =
			PersonalFragmentDirections.toMediathekDetailFragment(mediathekShow = show)
		findNavController().navigate(directions)
	}
}
