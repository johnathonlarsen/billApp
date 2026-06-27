package com.family.bankapp.ui.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

tailrec fun Context.findComponentActivity(): ComponentActivity {
    when (this) {
        is ComponentActivity -> return this
        is ContextWrapper -> return baseContext.findComponentActivity()
        else -> error("Activity not found")
    }
}
