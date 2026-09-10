package com.rtbishop.look4sat.core.data.lotw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface LoTWStorage {
    fun read(name: String): ByteArray?
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
}

/** P12 backups and their saved passwords are encrypted at rest and excluded from Android/cloud backup. */
internal class AndroidLoTWStorage(context: Context) : LoTWStorage {
    private val directory = File(context.noBackupFilesDir, "lotw")
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-z]+")))
        check(directory.isDirectory || directory.mkdirs())
        return AtomicFile(File(directory, name))
    }
    private fun key(): SecretKey = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).run {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        generateKey()
    }
    @Synchronized override fun read(name: String): ByteArray? {
        val file = file(name)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..(16 * 1024 * 1024) && bytes[0] == 1.toByte())
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(name.toByteArray(Charsets.UTF_8))
            doFinal(bytes, 13, bytes.size - 13)
        }
    }
    @Synchronized override fun write(name: String, data: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(name.toByteArray(Charsets.UTF_8))
        }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(data)
        val file = file(name)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized override fun delete(name: String) = file(name).delete()
    private companion object { const val ALIAS = "look4sat.lotw.storage.v1" }
}
