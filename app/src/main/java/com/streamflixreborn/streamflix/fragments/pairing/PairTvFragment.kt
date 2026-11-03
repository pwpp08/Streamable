package com.streamflixreborn.streamflix.fragments.pairing

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentPairTvBinding
import com.streamflixreborn.streamflix.sync.DeviceIdentity
import com.streamflixreborn.streamflix.sync.SyncUi
import com.streamflixreborn.streamflix.sync.pairing.BuiltInQrRenderer
import com.streamflixreborn.streamflix.sync.pairing.PairingApplier
import com.streamflixreborn.streamflix.sync.pairing.PairingServer
import com.streamflixreborn.streamflix.sync.pairing.QrRenderer
import com.streamflixreborn.streamflix.utils.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV: shows a pairing QR code and 6-digit code; the phone sends the account
 * credentials (see PairingServer). Reached from TV settings
 * ("Sync & devices" > "Pair with phone") via `R.id.pair_tv`.
 */
class PairTvFragment : Fragment() {

    private var _binding: FragmentPairTvBinding? = null
    private val binding get() = _binding!!

    private val qrRenderer: QrRenderer = BuiltInQrRenderer
    private var server: PairingServer? = null
    private var renderedUri: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPairTvBinding.inflate(SyncUi.inflater(inflater), container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        PairingApplier.reset()
        binding.tvPairProfile.text = getString(
            R.string.pair_tv_profile_note,
            ProfileManager.activeProfile?.name ?: "default",
        )

        val appContext = requireContext().applicationContext
        val pairingServer = PairingServer(
            context = appContext,
            deviceName = DeviceIdentity.name(appContext),
            authenticate = { credentials -> PairingApplier.authenticate(appContext, credentials) },
            onPaired = { email -> PairingApplier.finishInBackground(appContext, email) },
        )
        server = pairingServer

        binding.btnPairNewCode.setOnClickListener { restart() }
        binding.btnPairDone.setOnClickListener { findNavController().navigateUp() }
        binding.btnPairDone.requestFocus()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(pairingServer.state, PairingApplier.status) { s, a -> s to a }
                    .collect { (state, applier) -> render(state, applier) }
            }
        }
        restart()
    }

    private fun restart() {
        PairingApplier.reset()
        renderedUri = null
        server?.start(viewLifecycleOwner.lifecycleScope)
    }

    private fun render(state: PairingServer.State, applier: PairingApplier.Status) {
        val info = when (state) {
            is PairingServer.State.Waiting -> state.info
            is PairingServer.State.Pairing -> state.info
            else -> null
        }
        if (info != null && info.uri != renderedUri) {
            renderedUri = info.uri
            showQr(info.uri)
            binding.tvPairCode.text = info.code.chunked(3).joinToString(" ")
            binding.tvPairAddress.text = getString(R.string.pair_tv_address, info.host, info.port)
        }
        val showCode = info != null
        binding.tvPairCode.visibility = if (showCode) View.VISIBLE else View.INVISIBLE
        binding.ivPairQr.visibility = if (showCode) View.VISIBLE else View.INVISIBLE
        binding.pbPairQr.visibility = if (state is PairingServer.State.Starting) View.VISIBLE else View.GONE

        binding.tvPairStatus.text = when (state) {
            PairingServer.State.Starting -> getString(R.string.pair_tv_status_starting)
            is PairingServer.State.Waiting -> getString(R.string.pair_tv_status_waiting)
            is PairingServer.State.Pairing -> getString(R.string.pair_tv_status_pairing)
            is PairingServer.State.Paired -> when (applier) {
                is PairingApplier.Status.Done -> getString(R.string.pair_tv_status_done, applier.email)
                is PairingApplier.Status.Failed -> getString(R.string.pair_tv_status_failed, applier.message)
                else -> getString(R.string.pair_tv_status_syncing, state.email)
            }
            is PairingServer.State.Failed -> getString(
                R.string.pair_tv_status_failed,
                when (state.reason) {
                    PairingServer.Reason.NO_NETWORK -> getString(R.string.pair_tv_error_no_network)
                    PairingServer.Reason.SOCKET -> getString(R.string.pair_tv_error_socket)
                    PairingServer.Reason.TOO_MANY_ATTEMPTS -> getString(R.string.pair_tv_error_attempts)
                },
            )
            PairingServer.State.Expired -> getString(R.string.pair_tv_status_expired)
        }
        if (state is PairingServer.State.Paired && !binding.btnPairDone.hasFocus()) {
            binding.btnPairDone.requestFocus()
        }
    }

    private fun showQr(uri: String) {
        val sizePx = resources.getDimensionPixelSize(R.dimen.pair_tv_qr_size)
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap: Bitmap = withContext(Dispatchers.Default) { qrRenderer.render(uri, sizePx) }
            val view = _binding?.ivPairQr ?: return@launch
            // Nearest-neighbour scaling keeps module edges sharp.
            view.setImageDrawable(BitmapDrawable(resources, bitmap).apply { setFilterBitmap(false) })
        }
    }

    override fun onDestroyView() {
        server?.stop()
        server = null
        _binding = null
        super.onDestroyView()
    }
}
