package com.streamflixreborn.streamflix.sync

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Wraps actions that need a signed-in account. When the user is already signed in
 * [onReady] runs straight away; otherwise a popup offers Sign in / Create account
 * right where they are, and [onReady] runs once that succeeds.
 */
object AuthGate {

    fun isSignedIn(): Boolean = CloudSyncManager.currentUserId() != null

    fun require(activity: FragmentActivity, reasonRes: Int, onReady: () -> Unit) {
        if (isSignedIn()) {
            onReady()
            return
        }
        if (!SupabaseProvider.isConfigured) {
            Toast.makeText(activity, R.string.auth_gate_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        showDialog(activity, reasonRes, onReady)
    }

    private fun showDialog(activity: FragmentActivity, reasonRes: Int, onReady: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val message = TextView(activity).apply {
            text = activity.getString(R.string.auth_gate_message, activity.getString(reasonRes))
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val email = EditText(activity).apply {
            hint = activity.getString(R.string.cloud_sync_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val password = EditText(activity).apply {
            hint = activity.getString(R.string.cloud_sync_password_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(message, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(email, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.auth_gate_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.cloud_sync_sign_up, null)
            .setPositiveButton(R.string.cloud_sync_sign_in, null)
            .create()
        dialog.setOnShowListener {
            fun submit(create: Boolean) {
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                if (!emailValue.contains('@') || passwordValue.length < 6) {
                    Toast.makeText(activity, R.string.cloud_sync_invalid_credentials, Toast.LENGTH_LONG).show()
                    return
                }
                dialog.dismiss()
                authenticate(activity, emailValue, passwordValue, create, onReady)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit(create = false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit(create = true) }
        }
        dialog.show()
    }

    private fun authenticate(
        activity: FragmentActivity,
        email: String,
        password: String,
        create: Boolean,
        onReady: () -> Unit,
    ) {
        val busy = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.cloud_sync_progress_title)
            .setMessage(R.string.cloud_sync_progress_connecting)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            try {
                val signedIn = if (create) {
                    CloudSyncManager.signUp(activity, email, password)
                } else {
                    CloudSyncManager.signIn(activity, email, password)
                    true
                }
                busy.dismiss()
                when {
                    signedIn && isSignedIn() -> {
                        Toast.makeText(activity, R.string.auth_gate_done, Toast.LENGTH_SHORT).show()
                        onReady()
                    }
                    else -> Toast.makeText(activity, R.string.cloud_sync_confirm_email, Toast.LENGTH_LONG).show()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                busy.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.cloud_sync_error, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
