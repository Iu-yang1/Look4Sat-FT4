/* Icom LAN packet layout and credential transform adapted from wfview (GPL-3.0),
 * https://github.com/eliggett/wfview/tree/078e6eecb5c8b207ac3ceb7315b66348a967cf02
 * Copyright wfview contributors. No Qt code or runtime is required. */
package com.rtbishop.look4sat.core.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object IcomLanProtocol {
    fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    fun be(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
    fun u16(b: ByteArray, at: Int) = le(b).getShort(at).toInt() and 65535
    fun header(size: Int, id: Int, remote: Int, seq: Int, type: Int = 0): ByteArray =
        ByteArray(size).also { le(it).putInt(0, size).putShort(4, type.toShort())
            .putShort(6, seq.toShort()).putInt(8, id).putInt(12, remote) }
    fun valid(b: ByteArray) = b.size >= 16 && b.size <= 65507 && le(b).getInt(0) == b.size
    private val table = intArrayOf(
        0x47,0x5d,0x4c,0x42,0x66,0x20,0x23,0x46,0x4e,0x57,0x45,0x3d,0x67,0x76,0x60,0x41,0x62,0x39,0x59,0x2d,0x68,0x7e,
        0x7c,0x65,0x7d,0x49,0x29,0x72,0x73,0x78,0x21,0x6e,0x5a,0x5e,0x4a,0x3e,0x71,0x2c,0x2a,0x54,0x3c,0x3a,0x63,0x4f,
        0x43,0x75,0x27,0x79,0x5b,0x35,0x70,0x48,0x6b,0x56,0x6f,0x34,0x32,0x6c,0x30,0x61,0x6d,0x7b,0x2f,0x4b,0x64,0x38,
        0x2b,0x2e,0x50,0x40,0x3f,0x55,0x33,0x37,0x25,0x77,0x24,0x26,0x74,0x6a,0x28,0x53,0x4d,0x69,0x22,0x5c,0x44,0x31,
        0x36,0x58,0x3b,0x7a,0x51,0x5f,0x52)
    fun credential(value: String): ByteArray {
        require(value.length in 1..16 && value.all { it.code in 32..126 }) { "Icom credentials require 1–16 ASCII characters" }
        return ByteArray(16).also { out -> value.forEachIndexed { i, c ->
            val p = c.code + i
            out[i] = table[(if (p > 126) 32 + p % 127 else p) - 32].toByte()
        } }
    }
    fun auth(size: Int, id: Int, remote: Int, seq: Int, inner: Int, request: Int, tokenRequest: Int, token: Int) =
        header(size,id,remote,seq).also { b ->
            be(b).putInt(16,size-16).putShort(22,inner.toShort())
            b[20]=1; b[21]=request.toByte()
            le(b).putShort(26,tokenRequest.toShort()).putInt(28,token)
        }
    fun audio(id: Int, remote: Int, seq: Int, audioSeq: Int, pcm: ByteArray) =
        header(24+pcm.size,id,remote,seq).also { b ->
            le(b).putShort(16,0x80.toShort())
            be(b).putShort(18,audioSeq.toShort()).putShort(22,pcm.size.toShort())
            pcm.copyInto(b,24)
        }
    fun pcm(b: ByteArray): FloatArray {
        require(valid(b) && b.size >= 24 && be(b).getShort(22).toInt().and(65535)==b.size-24 && (b.size-24)%2==0)
        val data=le(b)
        return FloatArray((b.size-24)/2) { data.getShort(24+it*2)/32768f }
    }
}

/** Bounded sequence reorderer. A missing packet becomes an explicit timeline discontinuity. */
internal class IcomAudioReorder(private val capacity: Int = 8) {
    private var expected: Int? = null
    private val pending = mutableMapOf<Int,FloatArray>()
    fun offer(seq: Int, samples: FloatArray): List<Pair<FloatArray,Boolean>> {
        val s=seq and 65535
        if (expected==null) expected=s
        val distance=(s-checkNotNull(expected)) and 65535
        if (distance>=32768 || pending.containsKey(s)) return emptyList()
        pending[s]=samples
        val result=mutableListOf<Pair<FloatArray,Boolean>>()
        if (distance>=capacity || pending.size>=capacity) {
            expected=pending.keys.minBy { (it-checkNotNull(expected)) and 65535 }
            pending.remove(expected)?.let { result+=it to true }
            expected=(checkNotNull(expected)+1) and 65535
        }
        while (pending.containsKey(expected)) {
            result+=checkNotNull(pending.remove(expected)) to false
            expected=(checkNotNull(expected)+1) and 65535
        }
        return result
    }
}
