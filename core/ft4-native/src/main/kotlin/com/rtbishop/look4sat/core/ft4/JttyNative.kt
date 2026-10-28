// SPDX-License-Identifier: GPL-3.0-only
package com.rtbishop.look4sat.core.ft4

data class JttyNativeMessage(
    val id: Long, val startSeconds: Double, val frequencyHz: Float, val snrDb: Float,
    val kind: Int, val text: String, val calls: String
)

class JttyNativeDecoder : AutoCloseable {
    @Volatile private var handle: Long = JttyNativeBindings.create().also { check(it != 0L) }
    fun push(samples: ShortArray, centerHz: Float, widthHz: Float): List<JttyNativeMessage> {
        check(handle != 0L) { "JTTY decoder is closed" }
        return checkNotNull(JttyNativeBindings.push(handle, samples, centerHz, widthHz)) { "JTTY decode cancelled or invalid" }.toList()
    }
    fun cancel() { JttyNativeBindings.cancel(handle) }
    override fun close() { val id=handle;handle=0;JttyNativeBindings.close(id) }
}

object JttyNative {
    fun frameCount(text: String): Int = JttyNativeBindings.frames(text)
    fun generate(text: String, frequencyHz: Float, rate: Int): FloatArray =
        checkNotNull(JttyNativeBindings.generate(text,frequencyHz,rate)) { "JTTY message or frequency is invalid" }
}

internal object JttyNativeBindings {
    init { System.loadLibrary("look4sat_ft4") }
    external fun create(): Long
    external fun cancel(id: Long)
    external fun close(id: Long)
    external fun frames(text: String): Int
    external fun generate(text: String, frequencyHz: Float, rate: Int): FloatArray?
    external fun push(id: Long, samples: ShortArray, centerHz: Float, widthHz: Float): Array<JttyNativeMessage>?
}
