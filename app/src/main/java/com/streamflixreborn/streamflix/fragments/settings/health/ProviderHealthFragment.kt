package com.streamflixreborn.streamflix.fragments.settings.health

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.ui.tvFocusSpring
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.databinding.FragmentProviderHealthBinding
import kotlinx.coroutines.launch

/** Settings > Sources > Source health, shared by the phone and TV navigation graphs. */
class ProviderHealthFragment : Fragment() {

    private var _binding: FragmentProviderHealthBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<ProviderHealthViewModel>()
    private val isTv get() = activity is MainTvActivity
    private lateinit var adapter: ProviderHealthAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val themed = inflater.cloneInContext(ContextThemeWrapper(requireContext(), R.style.Theme_StreamFlix_Health))
        _binding = FragmentProviderHealthBinding.inflate(themed, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.settings_health_title)

        if (isTv) {
            val h = resources.getDimensionPixelSize(R.dimen.health_padding_horizontal_tv)
            val v = resources.getDimensionPixelSize(R.dimen.health_padding_vertical_tv)
            binding.healthRoot.setPadding(h, v, h, v)
            binding.btnHealthTest.tvFocusSpring()
        }

        adapter = ProviderHealthAdapter(focusableRows = isTv)
        binding.rvHealth.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHealth.adapter = adapter
        binding.rvHealth.itemAnimator = null

        binding.btnHealthTest.setOnClickListener {
            if (viewModel.state.value.running) viewModel.stop()
            else viewModel.start(includeCandidates = binding.cbHealthCandidates.isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }

        binding.btnHealthTest.requestFocus()
    }

    private fun render(state: ProviderHealthViewModel.State) {
        val b = _binding ?: return
        b.btnHealthTest.setText(if (state.running) R.string.settings_health_stop else R.string.settings_health_test_now)
        b.cbHealthCandidates.isEnabled = !state.running
        b.piHealth.visibility = if (state.running) View.VISIBLE else View.GONE
        if (state.running && state.providerCount > 0) {
            b.piHealth.isIndeterminate = false
            b.piHealth.max = state.providerCount
            b.piHealth.setProgressCompat(state.providerIndex, true)
        }

        b.tvHealthStatus.text = when {
            state.running -> getString(
                R.string.settings_health_checking,
                state.currentProvider.orEmpty(),
                state.providerIndex + 1,
                state.providerCount,
            )
            state.results.isNotEmpty() -> getString(
                R.string.settings_health_last_checked,
                DateUtils.getRelativeTimeSpanString(
                    state.results.maxOf { it.checkedAt },
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ),
            )
            else -> getString(R.string.settings_health_never_checked)
        }

        b.tvHealthEmpty.visibility = if (state.results.isEmpty() && !state.running) View.VISIBLE else View.GONE
        adapter.submitList(ProviderHealthAdapter.rows(state.results, state.currentProvider.takeIf { state.running }))
    }

    override fun onDestroyView() {
        binding.rvHealth.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
