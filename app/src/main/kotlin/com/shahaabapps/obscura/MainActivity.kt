package com.shahaabapps.obscura

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.shahaabapps.obscura.ui.navigation.ObscuraNavHost
import com.shahaabapps.obscura.ui.theme.ObscuraTheme
import com.shahaabapps.obscura.vault.VaultSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FragmentActivity because BiometricPrompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var session: VaultSession

    override fun onCreate(savedInstanceState: Bundle?) {
        // Block screenshots, screen recording and the preview thumbnail in recent apps.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // Other apps can't draw over the vault (no tapjacking of the PIN pad or fake prompts).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        // Older versions: ignore touches that arrive while another window covers the vault.
        window.decorView.filterTouchesWhenObscured = true
        // Only genuine assistive tools (TalkBack, Switch Access) may read the screen; other
        // accessibility services can't scrape file names or the recovery key.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ObscuraTheme {
                ObscuraNavHost(session)
            }
        }
    }
}
