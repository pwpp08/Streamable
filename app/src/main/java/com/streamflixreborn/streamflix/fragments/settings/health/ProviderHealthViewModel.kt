package com.streamflixreborn.streamflix.fragments.settings.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.utils.health.HealthStore
import com.streamflixreborn.streamflix.utils.health.ProviderHealthChecker
import com.streamflixreborn.streamflix.utils.health.ServerHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProviderHealthViewModel : ViewModel() {

    data class State(
        val results: List<ServerHealth> = emptyList(),
        val running: Boolean = false,
        val currentProvider: String? = null,
        val providerIndex: Int = 0,
        val providerCount: Int = 0,
        val checkedCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { HealthStore.latest() }
            _state.update { if (it.running) it else it.copy(results = stored) }
        }
    }

    fun start(includeCandidates: Boolean) {
        if (job?.isActive == true) return
        val checker = ProviderHealthChecker(ProviderHealthChecker.defaultProviders(includeCandidates))
        job = viewModelScope.launch {
            val fresh = mutableListOf<ServerHealth>()
            _state.update { it.copy(running = true, checkedCount = 0, providerIndex = 0) }
            try {
                checker.run().collect { progress ->
                    when (progress) {
                        is ProviderHealthChecker.Progress.Started ->
                            _state.update { it.copy(providerCount = progress.providers.size) }
                        is ProviderHealthChecker.Progress.ProviderStarted -> _state.update { state ->
                            state.copy(
                                currentProvider = progress.provider,
                                providerIndex = progress.index,
                                providerCount = progress.total,
                                // Old results of this provider are replaced as new ones arrive.
                                results = state.results.filterNot { it.provider == progress.provider },
                            )
                        }
                        is ProviderHealthChecker.Progress.Checked -> {
                            fresh += progress.result
                            _state.update { it.copy(results = it.results + progress.result, checkedCount = it.checkedCount + 1) }
                        }
                        is ProviderHealthChecker.Progress.Finished -> Unit
                    }
                }
            } finally {
                // Keep partial results of a stopped run too.
                if (fresh.isNotEmpty()) withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { HealthStore.save(fresh.toList()) }
                _state.update { it.copy(running = false, currentProvider = null) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
