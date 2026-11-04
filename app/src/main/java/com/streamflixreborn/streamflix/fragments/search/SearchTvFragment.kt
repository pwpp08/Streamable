package com.streamflixreborn.streamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.adapters.viewholders.CategoryViewHolder
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSearchTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.hideKeyboard
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.RemoteSearch
import com.streamflixreborn.streamflix.ui.tvFocusSpring

class SearchTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }
    /** True while a typed query's results are on screen; false shows the recommendation rows. */
    private var showingResults = false
    private var currentGridColumns: Int = 1

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->
                switchProviderIfNeeded(movie.providerName)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->
                switchProviderIfNeeded(tvShow.providerName)
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    )
                )
            }
        }
    }

    private lateinit var voiceHelper: VoiceRecognitionHelper

    private val panelAdapter = SearchPanelAdapter(
        onQuery = { query -> runQuery(query) },
        onClearHistory = {
            viewModel.clearHistory()
        },
        isTv = true,
    )

    /** Hidden after a search runs, until the text changes again. */
    private var panelSuppressed = false

    private fun switchProviderIfNeeded(providerName: String?) {
        val targetName = providerName?.takeIf { it.isNotBlank() } ?: return
        if (targetName == UserPreferences.currentProvider?.name) return

        val targetProvider = Provider.providers.keys.find { it.name == targetName } ?: return
        UserPreferences.currentProvider = targetProvider
        Toast.makeText(
            requireContext(),
            getString(R.string.switching_to_provider, targetName),
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.history.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { renderPanel() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.suggestions.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { renderPanel() }
        }

        // Text typed on the phone remote (DeviceCommand.Search).
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteSearch.requests
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { pending ->
                    if (pending == null) return@collect
                    val text = RemoteSearch.consume() ?: return@collect
                    binding.etSearch.setText(text)
                    binding.etSearch.setSelection(text.length)
                    submitSearch()
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recommendations.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { rows ->
                if (!showingResults) displayRecommendations(rows)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->

                when (state) {
                    is State.Searching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        showingResults = viewModel.query.isNotBlank()
                        if (showingResults) {
                            displaySearch(state.results, state.hasMore)
                            appAdapter.isLoading = false
                            binding.vgvSearch.visibility = View.VISIBLE
                            binding.isLoading.root.visibility = View.GONE
                        } else {
                            displayRecommendations(viewModel.recommendations.value)
                        }
                    }
                    is State.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.search(viewModel.query) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    viewModel.search(viewModel.query)
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                                binding.vgvSearch.visibility = View.INVISIBLE
                                binding.etSearch.nextFocusDownId = binding.isLoading.btnIsLoadingRetry.id
                                binding.isLoading.btnIsLoadingRetry.nextFocusUpId = binding.etSearch.id
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    private fun submitSearch(): Boolean {
        val query = binding.etSearch.text?.toString().orEmpty()
        hideKeyboard()
        if (query.isNotBlank()) {
            panelSuppressed = true
            renderPanel()
        }

        viewModel.search(query)
        return true
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.vgvSearch.nextFocusUpId = binding.etSearch.id
        listOf(binding.btnSearchClear, binding.btnSearchVoice).forEach { it.tvFocusSpring() }
        // The phone remote can type here (RemoteSearch) once this TV is signed in to sync.
        binding.tvSearchRemoteHint.visibility =
            if (CloudSyncManager.currentUserId() != null) View.VISIBLE else View.GONE

        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, event ->
                val isSubmitAction =
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_NULL
                val isSubmitKey =
                    event?.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

                if (isSubmitAction || isSubmitKey) {
                    return@setOnEditorActionListener submitSearch()
                }
                return@setOnEditorActionListener false
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    return@setOnKeyListener focusSearchContent()
                }

                if (
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_SEARCH
                ) {
                    return@setOnKeyListener submitSearch()
                }

                false
            }

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    panelSuppressed = false
                    viewModel.onTextChanged(s?.toString().orEmpty())
                    renderPanel()
                    if (s.isNullOrBlank()) {
                                val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                panelSuppressed = true
                renderPanel()
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
            },
            onListeningStateChanged = { isListening ->
                binding.btnSearchVoice.startAnimation(blink)
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )

        binding.btnSearchVoice.apply {
            requestFocus()
            visibility = if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener { if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck() }
        }

        listOf(binding.btnSearchClear, binding.btnSearchVoice).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    focusSearchContent()
                } else {
                    false
                }
            }
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
                    val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
            viewModel.search("")
        }

        binding.rvSearchPanel.adapter = panelAdapter

        binding.vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    val isTopRow = position in 0 until currentGridColumns
                    val upTargetId = if (isTopRow) binding.etSearch.id else View.NO_ID
                    child?.itemView?.nextFocusUpId = upTargetId
                    if (isTopRow && child is CategoryViewHolder) {
                        val hgv = child.childRecyclerView as? HorizontalGridView
                        hgv?.nextFocusUpId = upTargetId
                        for (i in 0 until (hgv?.childCount ?: 0)) {
                            hgv?.getChildAt(i)?.nextFocusUpId = upTargetId
                        }
                    }
                }
            })
        }

        binding.root.requestFocus()
    }

    /** A recent search or suggestion was picked. */
    private fun runQuery(query: String) {
        val b = _binding ?: return
        b.etSearch.setText(query)
        b.etSearch.setSelection(query.length)
        submitSearch()
        focusSearchContent()
    }

    /**
     * Recent searches (empty box) or matches and suggestions (while typing), between the search
     * box and the results so the D-pad reaches them from the box.
     */
    private fun renderPanel() {
        val b = _binding ?: return
        val text = b.etSearch.text?.toString().orEmpty()
        val (suggestionsFor, suggestions) = viewModel.suggestions.value
        val rows = SearchPanelRow.build(
            text = text,
            history = viewModel.history.value,
            suggestions = suggestions,
            suggestionsFor = suggestionsFor,
            recentTitle = getString(R.string.search_recent),
            suggestionsTitle = getString(R.string.search_suggestions),
        )
        val visible = !panelSuppressed && rows.isNotEmpty()
        val wasFocused = b.rvSearchPanel.hasFocus()
        panelAdapter.submitList(if (visible) rows else emptyList())
        b.rvSearchPanel.visibility = if (visible) View.VISIBLE else View.GONE

        val panelId = b.rvSearchPanel.id
        val searchViews = listOf(b.etSearch, b.btnSearchClear, b.btnSearchVoice)
        if (visible) {
            searchViews.forEach { it.nextFocusDownId = panelId }
        } else {
            searchViews.forEach { if (it.nextFocusDownId == panelId) it.nextFocusDownId = b.vgvSearch.id }
            // Not the box itself: focusing it can open the keyboard.
            if (wasFocused) b.vgvSearch.requestFocus()
        }
    }

    private fun focusSearchContent(): Boolean {
        val hasResults = appAdapter.itemCount > 0 && binding.vgvSearch.visibility == View.VISIBLE
        return when {
            hasResults -> {
                binding.vgvSearch.requestFocus()
            }
            else -> false
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        currentGridColumns = if (viewModel.query == "") 5 else 6
        binding.vgvSearch.setNumColumns(currentGridColumns)

        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun rowTitle(name: String): String =
        if (name.startsWith(SearchViewModel.BECAUSE_PREFIX)) {
            getString(R.string.search_because_you_watched, name.removePrefix(SearchViewModel.BECAUSE_PREFIX))
        } else name

    /** Empty search box: rows of recommended titles instead of genre tiles. */
    private fun displayRecommendations(rows: List<Category>?) {
        if (rows == null) {
            binding.isLoading.apply {
                root.visibility = View.VISIBLE
                pbIsLoading.visibility = View.VISIBLE
                gIsLoadingRetry.visibility = View.GONE
            }
            return
        }
        binding.isLoading.root.visibility = View.GONE
        binding.vgvSearch.visibility = View.VISIBLE
        currentGridColumns = 1
        binding.vgvSearch.setNumColumns(currentGridColumns)
        appAdapter.setOnLoadMoreListener(null)
        appAdapter.isLoading = false
        appAdapter.submitList(rows.map { row ->
            row.list.forEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            }
            Category(name = rowTitle(row.name), list = row.list).apply {
                itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        })
    }
}
