package com.movo.customer.parcel.push

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

/** Device-only encrypted storage. No plaintext fallback, logs, backup, or speculative API calls. */
class PushTokenStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "parcel_fcm_token.enc"))

    fun save(token: String): Boolean = synchronized(lock) {
        if (token.isBlank() || token.length > 8192) return@synchronized false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val output = file.startWrite()
            try {
                output.write(byteArrayOf(1, cipher.iv.size.toByte()))
                output.write(cipher.iv)
                output.write(encrypted)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
            true
        } catch (_: Exception) { false }
    }

    fun latest(): String? = synchronized(lock) {
        try {
            val bytes = file.readFully()
            require(bytes.size in 30..16384 && bytes[0] == 1.toByte())
            val ivSize = bytes[1].toInt() and 255
            require(ivSize == 12 && bytes.size > ivSize + 18)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivSize)))
            String(cipher.doFinal(bytes.copyOfRange(2 + ivSize, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private companion object {
        const val alias = "movo.parcel.fcm.token.v1"
        val lock = Any()
    }
}
