package com.rtbishop.look4sat.core.presentation

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Measure the first frame containing results, rather than just Activity creation. */
@Composable
fun ContentDrawTiming(page: String, ready: Boolean) {
    val view = LocalView.current
    val enteredAt = remember(page) { SystemClock.elapsedRealtime() }
    val reported = remember(page) { booleanArrayOf(false) }
    DisposableEffect(view, page, ready) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            if (!reported[0]) {
                reported[0] = true
                val now = SystemClock.elapsedRealtime()
                Log.i("Look4SatStartup",
                    "page=$page firstContentDraw processMs=${now - Process.getStartElapsedRealtime()} " +
                        "pageMs=${now - enteredAt} elapsedRealtimeMs=$now")
            }
        }
        if (ready && !reported[0]) observer.addOnDrawListener(listener)
        onDispose {
            // Android forbids removing draw listeners inside an onDraw dispatch.
            view.post { if (observer.isAlive) observer.removeOnDrawListener(listener) }
        }
    }
}
