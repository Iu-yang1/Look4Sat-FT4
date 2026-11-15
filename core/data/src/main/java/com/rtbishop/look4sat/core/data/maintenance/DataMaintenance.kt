package com.rtbishop.look4sat.core.data.maintenance

import android.content.SharedPreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Serializes snapshots with database writers, including a writer's nested repository calls. */
internal object DataMaintenance {
    @Volatile var restartRequired = false
    fun checkWritable() {
        if (restartRequired) throw kotlinx.coroutines.CancellationException("Restart after restoring the backup")
    }
    private val mutex = Mutex()
    val preferencesLock = Any()
    private class Owner : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Owner>
    }
    suspend fun <T> write(block: suspend () -> T): T =
        if (currentCoroutineContext()[Owner] != null) block()
        else mutex.withLock { checkWritable(); withContext(Owner()) { block() } }
}

/** Makes an in-memory preference snapshot atomic with respect to all preference editors. */
class SnapshotPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())
    private class Editor(private val delegate: SharedPreferences.Editor) : SharedPreferences.Editor by delegate {
        override fun putString(key: String?, value: String?) = apply { delegate.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { delegate.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { delegate.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { delegate.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { delegate.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { delegate.putBoolean(key, value) }
        override fun remove(key: String?) = apply { delegate.remove(key) }
        override fun clear() = apply { delegate.clear() }
        override fun commit(): Boolean = synchronized(DataMaintenance.preferencesLock) { DataMaintenance.checkWritable(); delegate.commit() }
        override fun apply() = synchronized(DataMaintenance.preferencesLock) { DataMaintenance.checkWritable(); delegate.apply() }
    }
}
