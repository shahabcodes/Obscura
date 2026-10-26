package com.shahaabapps.obscura.ui.components

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks the window this is placed in as holding sensitive data, so that only genuine assistive
 * tools can read it.
 *
 * The main window is marked when the app starts, but a sheet or a dialog is a window of its own
 * and does not inherit it — and those are where file names are shown: the clip a GIF is being
 * made from, the file a confirmation is about to shred. Put this at the top of any sheet or
 * dialog that shows something out of the vault.
 */
@Composable
fun SensitiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.rootView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        onDispose { }
    }
}
