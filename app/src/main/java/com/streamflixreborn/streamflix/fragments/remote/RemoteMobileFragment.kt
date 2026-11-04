package com.streamflixreborn.streamflix.fragments.remote

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentRemoteMobileBinding
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.DeviceCommand
import com.streamflixreborn.streamflix.sync.DeviceCommandBus
import com.streamflixreborn.streamflix.sync.PlayOnTv
import com.streamflixreborn.streamflix.sync.SyncUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone remote for a TV signed in to the same account: search text and
 * player controls, sent through DeviceCommandBus. Reached from the remote
 * button next to search in MainMobileActivity (`R.id.remote`).
 */
class RemoteMobileFragment : Fragment() {

    private var _binding: FragmentRemoteMobileBinding? = null
    private val binding get() = _binding!!

    private var tv: DeviceCommandBus.SyncDevice? = null
    private var searchJob: Job? = null
    private var lastSentSearch: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRemoteMobileBinding.inflate(SyncUi.inflater(inflater), container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The remote is one page: shrink the round pad when the screen is short so nothing scrolls.
        binding.root.doOnLayout { fitDial() }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener { _binding?.let { fitDial() } }

        binding.btnRemoteChange.setOnClickListener { chooseTv(force = true) }
        binding.btnRemotePair.setOnClickListener { findNavController().navigate(R.id.pair_phone) }

        binding.etRemoteSearch.doAfterTextChanged { editable ->
            searchJob?.cancel()
            val text = editable?.toString().orEmpty().trim()
            if (text.isEmpty()) return@doAfterTextChanged
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                sendSearch(text)
            }
        }
        binding.etRemoteSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isEnter) {
                searchJob?.cancel()
                val text = binding.etRemoteSearch.text?.toString().orEmpty().trim()
                if (text.isNotEmpty()) {
                    lastSentSearch = null
                    viewLifecycleOwner.lifecycleScope.launch { sendSearch(text) }
                }
            }
            isEnter
        }

        binding.btnRemotePlayPause.setOnClickListener { control(DeviceCommand.Control.Action.PLAY_PAUSE) }
        binding.btnRemoteBack.setOnClickListener {
            control(DeviceCommand.Control.Action.SEEK_BACK, SEEK_MS)
        }
        binding.btnRemoteForward.setOnClickListener {
            control(DeviceCommand.Control.Action.SEEK_FORWARD, SEEK_MS)
        }
        binding.btnRemoteNext.setOnClickListener { control(DeviceCommand.Control.Action.NEXT_EPISODE) }
        binding.btnRemoteSubtitles.setOnClickListener { control(DeviceCommand.Control.Action.SUBTITLES_TOGGLE) }
        binding.btnRemoteStop.setOnClickListener { control(DeviceCommand.Control.Action.STOP) }

        restoreTv()
        renderTv()
        if (CloudSyncManager.currentUserId() == null) {
            binding.tvRemoteDevice.text = getString(R.string.sync_sign_in_first)
            setControlsEnabled(false)
            if (savedInstanceState == null) {
                com.streamflixreborn.streamflix.sync.AuthGate.require(requireActivity(), R.string.auth_reason_remote) {
                    if (_binding != null) {
                        setControlsEnabled(true)
                        chooseTv(force = false)
                    }
                }
            }
        } else if (tv == null) {
            chooseTv(force = false)
        }
    }

    private var lastDialSize = -1

    /**
     * Sizes the round pad (and its buttons) so the whole page just fits: full size when there is
     * room, smaller on short screens, never below [MIN_DIAL_DP].
     */
    private fun fitDial() {
        val b = _binding ?: return
        val scroll = b.root as? ScrollView ?: return
        val content = scroll.getChildAt(0) as? ViewGroup ?: return
        val dial = b.flRemoteDial
        val density = resources.displayMetrics.density
        // Everything except the pad: child heights plus margins plus the column's own padding.
        var others = content.paddingTop + content.paddingBottom
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child === dial || child.visibility == View.GONE) continue
            val lp = child.layoutParams as ViewGroup.MarginLayoutParams
            others += child.height + lp.topMargin + lp.bottomMargin
        }
        val dialMargins = (dial.layoutParams as ViewGroup.MarginLayoutParams).let { it.topMargin + it.bottomMargin }
        val full = (DIAL_DP * density).toInt()
        val size = (scroll.height - others - dialMargins).coerceIn((MIN_DIAL_DP * density).toInt(), full)
        if (kotlin.math.abs(size - lastDialSize) < 2) return
        lastDialSize = size
        val k = size.toFloat() / full
        dial.layoutParams = dial.layoutParams.also { it.width = size; it.height = size }
        fun View.sizeTo(widthDp: Int, marginDp: Int) {
            layoutParams = (layoutParams as FrameLayout.LayoutParams).also { lp ->
                lp.width = (widthDp * density * k).toInt(); lp.height = lp.width
                lp.marginStart = (marginDp * density * k).toInt(); lp.marginEnd = lp.marginStart
            }
        }
        b.btnRemoteBack.sizeTo(72, 12)
        b.btnRemoteForward.sizeTo(72, 12)
        b.btnRemotePlayPause.sizeTo(128, 0)
    }

    private fun chooseTv(force: Boolean) {
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val chosen = if (force) {
                val tvs = runCatching { PlayOnTv.listTvs(activity, DeviceCommandBus.LIST_WINDOW_MS) }.getOrNull()
                when {
                    tvs == null -> PlayOnTv.chooseTv(activity, DeviceCommandBus.LIST_WINDOW_MS)
                    tvs.isEmpty() -> PlayOnTv.chooseTv(activity, DeviceCommandBus.LIST_WINDOW_MS)
                    else -> PlayOnTv.pick(activity, tvs)
                }
            } else {
                PlayOnTv.chooseTv(activity, DeviceCommandBus.LIST_WINDOW_MS)
            }
            if (chosen != null) {
                tv = chosen
                saveTv(chosen)
                renderTv()
            }
        }
    }

    private suspend fun sendSearch(text: String) {
        if (text == lastSentSearch) return
        val target = tv ?: return chooseTv(force = false)
        val activity = activity ?: return
        if (PlayOnTv.sendTo(activity, target, DeviceCommand.Search(text))) lastSentSearch = text
    }

    private fun control(action: DeviceCommand.Control.Action, amountMs: Long? = null) {
        val target = tv ?: return chooseTv(force = false)
        val activity = activity ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            PlayOnTv.sendTo(activity, target, DeviceCommand.Control(action, amountMs))
        }
    }

    private fun renderTv() {
        val b = _binding ?: return
        b.tvRemoteDevice.text = tv?.let { getString(R.string.remote_tv_label, it.name) }
            ?: getString(R.string.remote_no_tv)
        setControlsEnabled(tv != null)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val b = _binding ?: return
        listOf(
            b.btnRemotePlayPause, b.btnRemoteBack, b.btnRemoteForward,
            b.btnRemoteNext, b.btnRemoteSubtitles, b.btnRemoteStop,
        ).forEach { it.isEnabled = enabled }
        b.tilRemoteSearch.isEnabled = enabled
    }

    private fun saveTv(device: DeviceCommandBus.SyncDevice) {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("id", device.id)
            .putString("name", device.name)
            .apply()
    }

    private fun restoreTv() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString("id", null) ?: return
        tv = DeviceCommandBus.SyncDevice(
            id = id,
            name = prefs.getString("name", null) ?: id.take(6),
            kind = com.streamflixreborn.streamflix.sync.DeviceIdentity.Kind.TV,
            lastSeenAtMillis = 0L,
            isThisDevice = false,
        )
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val DIAL_DP = 288
        private const val MIN_DIAL_DP = 180
        private const val PREFS = "remote_mobile"
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val SEEK_MS = 10_000L
    }
}
