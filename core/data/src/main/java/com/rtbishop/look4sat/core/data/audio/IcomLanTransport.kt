package com.rtbishop.look4sat.core.data.audio

import com.rtbishop.look4sat.core.data.framework.RadioTransport

/** A CAT reader belongs to one LAN generation; it must never attach itself to a replacement. */
internal class IcomLanTransport(
    private val prepareSession: suspend ()->IcomLanSession,
    private val currentSession: ()->IcomLanSession?,
    private val releaseSession: suspend (IcomLanSession)->Unit
) : RadioTransport {
    private var bound: IcomLanSession?=null
    private var pending=byteArrayOf()
    override val connectionDescription="icom/wlan"
    override val isConnected: Boolean get()=bound?.let { it===currentSession() && it.isConnected }==true
    override suspend fun connect(): Boolean {
        pending=byteArrayOf();bound=prepareSession();return isConnected
    }
    override suspend fun disconnect() {
        val old=bound;bound=null;pending=byteArrayOf()
        if(old!=null) releaseSession(old)
    }
    override suspend fun write(bytes: ByteArray): Boolean = if(isConnected) checkNotNull(bound).writeCiv(bytes) else false
    override suspend fun readAvailable(maxBytes: Int): ByteArray {
        require(maxBytes>=0)
        if(maxBytes==0 || !isConnected) return byteArrayOf()
        if(pending.isEmpty()) pending=checkNotNull(bound).readCiv()
        val count=minOf(maxBytes,pending.size)
        return pending.copyOfRange(0,count).also { pending=pending.copyOfRange(count,pending.size) }
    }
}
