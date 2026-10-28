// SPDX-License-Identifier: GPL-3.0-only
// Search/tracking policy reference: Iu-yang1/JTTY-Go 9c740414c847e362ca98fc65fe5068ed63156324 (GPL-3.0).
package com.rtbishop.look4sat.core.domain.jtty

import com.rtbishop.look4sat.core.domain.ft4.RealFft
import kotlin.math.abs

class JttyCarrierSearch {
    private val fft=RealFft(4096)
    private val rolling=FloatArray(4096)
    private var buffered=0
    private var hop=0
    private var nextId=1L
    private var carriers=emptyList<JttyCarrier>()
    fun reset() { buffered=0;hop=0;carriers=emptyList() }
    fun confirm(hz: Float, snr: Float, now: Long) { merge(hz,snr,now,true) }
    fun snapshot(now: Long): List<JttyCarrier> {
        carriers=carriers.filter { now-it.lastSeenMillis <= 6_000 }
        return carriers.sortedWith(compareByDescending<JttyCarrier> { it.confirmed }.thenByDescending { it.strengthDb }).take(8)
    }
    fun push(samples: FloatArray, now: Long): FloatArray? {
        var latest: FloatArray?=null
        for(sample in samples) {
            if(buffered==rolling.size) { rolling.copyInto(rolling,0,1024);buffered-=1024 }
            rolling[buffered++]=sample;hop++
            if(buffered==rolling.size && hop>=1024) {
                hop=0;val bins=fft.magnitudeDb(rolling).copyOf(957);latest=bins
                val noise=bins.sliceArray(34 until bins.size).sorted()[bins.size/2]
                val peaks=(35 until bins.lastIndex).filter { bins[it]>noise+8 && bins[it]>bins[it-1] && bins[it]>=bins[it+1] }
                    .sortedByDescending { bins[it] }
                val selected=mutableListOf<Float>()
                for(index in peaks) {
                    val hz=index*12000f/4096
                    if(selected.any { abs(it-hz)<100 }) continue
                    selected+=hz;merge(hz,bins[index]-noise,now,false)
                    if(selected.size==8) break
                }
            }
        }
        snapshot(now);return latest
    }
    private fun merge(hz: Float, strength: Float, now: Long, confirmed: Boolean) {
        val old=carriers.minByOrNull { abs(it.frequencyHz-hz) }?.takeIf { abs(it.frequencyHz-hz)<=20 }
        val item=if(old==null) JttyCarrier(nextId++,hz,strength,now,confirmed)
            else old.copy(frequencyHz=hz,strengthDb=old.strengthDb*0.7f+strength*0.3f,lastSeenMillis=now,confirmed=old.confirmed||confirmed)
        carriers=(carriers.filter { it.id!=old?.id && now-it.lastSeenMillis<=6000 }+item)
            .sortedWith(compareByDescending<JttyCarrier> { it.confirmed }.thenByDescending { it.strengthDb }).take(8)
    }
}
