package com.rtbishop.look4sat.core.data.audio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class RadioAudioCredentials(context: Context) {
    private val preferences=context.getSharedPreferences("radio_audio_secrets",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("radio_audio",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("radio_audio",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(identity: String, username: String, password: String) {
        IcomLanProtocol.credential(username); IcomLanProtocol.credential(password)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        cipher.updateAAD(identity.toByteArray())
        val encrypted=cipher.doFinal("$username\n$password".toByteArray())
        check(preferences.edit().putString(identity,Base64.encodeToString(cipher.iv+encrypted,Base64.NO_WRAP)).commit())
    }
    fun read(identity: String): Pair<String,String>? {
        val value=preferences.getString(identity,null) ?: return null
        val bytes=Base64.decode(value,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
        cipher.updateAAD(identity.toByteArray())
        val fields=String(cipher.doFinal(bytes.copyOfRange(12,bytes.size))).split('\n',limit=2)
        return fields[0] to fields[1]
    }
}
