package android.template.butki.utilities

import android.util.Log

object Logger {
    private const val MAIN_TAG = "Butki-Ai"

    fun d(tag: String, message: String) {
        Log.d("$MAIN_TAG-$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("$MAIN_TAG-$tag", message, throwable)
        } else {
            Log.e("$MAIN_TAG-$tag", message)
        }
    }

    fun w(tag: String, message: String) {
        Log.w("$MAIN_TAG-$tag", message)
    }
}
