package com.streamflixreborn.streamflix.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSearchMobileBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences // <-- IMPORT AÑADIDO
import com.streamflixreborn.streamflix.utils.VoiceRecognitionHelper
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.hideKeyboard
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.providers.IptvProvider

class SearchMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentSearchMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }

    private var appAdapter = AppAdapter()

    private lateinit var voiceHelper: VoiceRecognitionHelper

    private val panelAdapter = SearchPanelAdapter(
        onQuery = { query -> runQuery(query) },
        onClearHistory = { viewModel.clearHistory() },
    )

    /** Hidden after a search runs, until the text changes again. */
    private var panelSuppressed = false

    /** True while a typed query's results are on screen; false shows the recommendation rows. */
    private var showingResults = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.history.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { renderPanel() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.suggestions.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { renderPanel() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recommendations.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { rows ->
                if (!showingResults) displayRecommendations(rows)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                // ========= BLOQUE WHEN MODIFICADO =========
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
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.search(viewModel.query) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                            }
                        }
                    }
                }
                // ===========================================
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.etSearch.apply {
            // ========= LÓGICA DE BÚSQUEDA MODIFICADA =========
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submitSearch(binding.etSearch.text.toString())
                    return@setOnEditorActionListener true
                }
                return@setOnEditorActionListener false
            }

            setOnFocusChangeListener { _, _ -> renderPanel() }
            // =================================================

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    panelSuppressed = false
                    viewModel.onTextChanged(s?.toString().orEmpty())
                    renderPanel()
                    if(s.isNullOrBlank()){
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
            visibility =
                if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE

            setOnClickListener {
                if (!voiceHelper.isListening) {
                    voiceHelper.startWithPermissionCheck()
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

        binding.rvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun submitSearch(query: String) {
        hideKeyboard()

        if (query.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
            return
        }

        panelSuppressed = true
        renderPanel()
        binding.etSearch.clearFocus()

        viewModel.search(query)
    }

    /** A recent search or suggestion was tapped. */
    private fun runQuery(query: String) {
        binding.etSearch.setText(query)
        binding.etSearch.setSelection(query.length)
        submitSearch(query)
    }

    /** Recent searches (box focused and empty) or matches and suggestions (while typing). */
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
        val visible = !panelSuppressed && rows.isNotEmpty() && (b.etSearch.hasFocus() || text.isNotBlank())
        panelAdapter.submitList(if (visible) rows else emptyList())
        b.rvSearchPanel.visibility = if (visible) View.VISIBLE else View.GONE
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
        binding.rvSearch.layoutManager = LinearLayoutManager(requireContext())
        appAdapter.setOnLoadMoreListener(null)
        appAdapter.submitList(rows.map { row ->
            row.list.forEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                }
            }
            Category(name = rowTitle(row.name), list = row.list).apply {
                itemSpacing = resources.getDimensionPixelSize(R.dimen.browse_row_item_gap)
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
        })
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        if (binding.rvSearch.layoutManager !is GridLayoutManager) {
            binding.rvSearch.layoutManager = GridLayoutManager(requireContext(), 3)
        }
        appAdapter.submitList(list.onEach {
            when (it) {
                is Genre -> it.itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })

        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
