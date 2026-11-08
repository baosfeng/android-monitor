package com.bsfeng.monitor.ui.components

import android.view.View
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider

internal tailrec fun View.findDialogWindow(): Window? = when (this) {
    is DialogWindowProvider -> window
    else -> (parent as? View)?.findDialogWindow()
}
