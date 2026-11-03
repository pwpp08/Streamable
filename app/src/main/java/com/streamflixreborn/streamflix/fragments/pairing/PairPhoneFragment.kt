package com.streamflixreborn.streamflix.fragments.pairing

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.FragmentPairPhoneBinding
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.DeviceIdentity
import com.streamflixreborn.streamflix.sync.SupabaseProvider
import com.streamflixreborn.streamflix.sync.SyncUi
import com.streamflixreborn.streamflix.sync.pairing.PairingClient
import com.streamflixreborn.streamflix.sync.pairing.PairingProtocol
import com.streamflixreborn.streamflix.sync.pairing.QrScanners
import com.streamflixreborn.streamflix.utils.ProfileManager
import kotlinx.coroutines.launch

/**
 * Phone: sends this phone's sync account to a TV showing PairTvFragment.
 * Optional argument `pairUri` (a scanned `streamflix://pair?...` link).
 */
class PairPhoneFragment : Fragment() {

    private var _binding: FragmentPairPhoneBinding? = null
    private val binding get() = _binding!!

    private val scanner = QrScanners.default

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = scanner.parseResult(result.resultCode, result.data) ?: return@registerForActivityResult
        onScanned(text)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPairPhoneBinding.inflate(SyncUi.inflater(inflater), container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val email = CloudSyncManager.currentUserEmail()
        val signedIn = email != null && SupabaseProvider.configured(requireContext())
        binding.tvPairPhoneAccount.text = if (signedIn) {
            getString(R.string.pair_phone_account, email)
        } else {
            getString(R.string.pair_phone_not_signed_in)
        }
        binding.btnPairPhoneScan.isEnabled = signedIn
        binding.btnPairPhoneCode.isEnabled = signedIn

        binding.btnPairPhoneScan.setOnClickListener { startScan() }
        binding.btnPairPhoneCode.setOnClickListener { askCode() }

        val uriArg = arguments?.getString(ARG_PAIR_URI)
        if (signedIn && savedInstanceState == null && !uriArg.isNullOrBlank()) {
            onScanned(uriArg)
        }
    }

    private fun startScan() {
        try {
            scanLauncher.launch(scanner.createScanIntent(requireContext()) ?: throw ActivityNotFoundException())
        } catch (_: ActivityNotFoundException) {
            showResult(getString(R.string.pair_phone_no_scanner))
        }
    }

    private fun onScanned(text: String) {
        val uri = PairingProtocol.PairingUri.parse(text) ?: run {
            showResult(getString(R.string.pair_phone_invalid_qr))
            return
        }
        askPassword { credentials -> PairingClient.pair(uri, credentials) }
    }

    private fun askCode() {
        val context = SyncUi.themed(requireContext())
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        val layout = TextInputLayout(context).apply {
            hint = getString(R.string.pair_phone_code_hint)
            addView(input)
        }
        SyncUi.dialog(requireContext())
            .setTitle(R.string.pair_phone_code_title)
            .setView(padded(layout))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text?.toString().orEmpty().filter(Char::isDigit)
                if (code.length != 6) {
                    showResult(getString(R.string.pair_phone_invalid_code))
                } else {
                    askPassword { credentials ->
                        showProgress(getString(R.string.pair_phone_searching))
                        PairingClient.pairWithCode(requireContext(), code, credentials)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPassword(pair: suspend (PairingProtocol.Credentials) -> PairingClient.Result) {
        val email = CloudSyncManager.currentUserEmail() ?: return
        val context = SyncUi.themed(requireContext())
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = TextInputLayout(context).apply {
            hint = getString(R.string.pair_phone_password_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(input)
        }
        SyncUi.dialog(requireContext())
            .setTitle(R.string.pair_phone_password_title)
            .setMessage(getString(R.string.pair_phone_password_message, email))
            .setView(padded(layout))
            .setPositiveButton(R.string.pair_phone_pair) { _, _ ->
                val editable = input.text ?: return@setPositiveButton
                val password = CharArray(editable.length).also { editable.getChars(0, editable.length, it, 0) }
                editable.clear()
                if (password.isEmpty()) return@setPositiveButton
                val credentials = PairingProtocol.Credentials(
                    supabaseUrl = SupabaseProvider.getUrl(requireContext()),
                    publicKey = SupabaseProvider.getPublicKey(requireContext()),
                    email = email,
                    password = password,
                    profileName = ProfileManager.activeProfile?.name,
                    sourceDeviceName = DeviceIdentity.name(requireContext()),
                )
                runPairing(credentials, pair)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> input.text?.clear() }
            .show()
    }

    private fun runPairing(
        credentials: PairingProtocol.Credentials,
        pair: suspend (PairingProtocol.Credentials) -> PairingClient.Result,
    ) {
        showProgress(getString(R.string.pair_phone_in_progress))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                pair(credentials)
            } finally {
                credentials.wipe()
            }
            showResult(
                when (result) {
                    is PairingClient.Result.Success ->
                        if (result.tvName.isNotBlank()) getString(R.string.pair_phone_success, result.tvName)
                        else getString(R.string.pair_phone_success_unnamed)
                    is PairingClient.Result.Failure -> when (result.reason) {
                        PairingClient.Reason.NOT_FOUND -> getString(R.string.pair_phone_error_not_found)
                        PairingClient.Reason.UNREACHABLE -> getString(R.string.pair_phone_error_unreachable)
                        PairingClient.Reason.CODE_MISMATCH -> getString(R.string.pair_phone_error_code)
                        PairingClient.Reason.REJECTED ->
                            getString(R.string.pair_phone_error_rejected, result.message.orEmpty())
                    }
                },
            )
        }
    }

    private fun showProgress(text: String) {
        val b = _binding ?: return
        b.piPairPhone.visibility = View.VISIBLE
        b.tvPairPhoneResult.text = text
        b.btnPairPhoneScan.isEnabled = false
        b.btnPairPhoneCode.isEnabled = false
    }

    private fun showResult(text: String) {
        val b = _binding ?: return
        b.piPairPhone.visibility = View.GONE
        b.tvPairPhoneResult.text = text
        b.btnPairPhoneScan.isEnabled = true
        b.btnPairPhoneCode.isEnabled = true
    }

    private fun padded(view: View): View {
        val padding = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(view.context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(view)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_PAIR_URI = "pairUri"
    }
}
