package com.panlink.app

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

object AppLock {
    private const val PREFS = "panlink_settings"
    private const val KEY = "require_auth"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /** True jika HP punya PIN, pola, password, atau sidik jari. */
    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isDeviceSecure
    }

    @Suppress("DEPRECATION")
    fun authenticate(
        context: Context,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val builder = BiometricPrompt.Builder(context)
            .setTitle("Verifikasi untuk login WiFi")
            .setSubtitle("Gunakan sidik jari, PIN, atau pola layar kunci")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setDeviceCredentialAllowed(true)
        }

        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult?
                ) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onFailure("Verifikasi dibatalkan atau gagal.")
                }
            }
        )
    }
}