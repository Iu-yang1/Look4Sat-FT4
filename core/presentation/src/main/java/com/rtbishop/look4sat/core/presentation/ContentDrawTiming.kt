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
fun ContentDrawTiming(page: String, ready: Boolean, requestId: Any? = Unit, complete: Boolean = false) {
    val view = LocalView.current
    val enteredAt = remember(page, requestId) { SystemClock.elapsedRealtime() }
    val reported = remember(page, requestId) { booleanArrayOf(false, false) }
    DisposableEffect(view, page, requestId, ready, complete) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener {
            if (ready && !reported[0]) {
                reported[0] = true
                val now = SystemClock.elapsedRealtime()
                Log.i("Look4SatStartup",
                    "page=$page firstContentDraw processMs=${now - Process.getStartElapsedRealtime()} " +
                        "pageMs=${now - enteredAt} elapsedRealtimeMs=$now request=$requestId")
            }
            if (complete && !reported[1]) {
                reported[1] = true
                val now = SystemClock.elapsedRealtime()
                Log.i("Look4SatStartup", "page=$page finalContentDraw request=$requestId " +
                    "processMs=${now - Process.getStartElapsedRealtime()} pageMs=${now - enteredAt}")
            }
        }
        if ((ready && !reported[0]) || (complete && !reported[1])) observer.addOnDrawListener(listener)
        onDispose {
            // Android forbids removing draw listeners inside an onDraw dispatch.
            view.post { if (observer.isAlive) observer.removeOnDrawListener(listener) }
        }
    }
}
