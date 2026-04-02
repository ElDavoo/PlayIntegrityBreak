package icu.nullptr.playintegritybreak.ui.util

import android.widget.Toast
import androidx.annotation.StringRes
import icu.nullptr.playintegritybreak.pibApp

fun showToast(@StringRes resId: Int) {
    Toast.makeText(pibApp, resId, Toast.LENGTH_SHORT).show()
}

fun showToast(text: CharSequence) {
    Toast.makeText(pibApp, text, Toast.LENGTH_SHORT).show()
}
