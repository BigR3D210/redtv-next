package com.redtv.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials and catalogs are encrypted with a key that never leaves Android Keystore. */
interface TextStore {
    fun write(name: String, value: String)
    fun read(name: String): String?
    fun remove(name: String)
}

class PrivateStore(context: Context) : TextStore {
    private val directory = context.noBackupFilesDir.resolve("private").apply { mkdirs() }
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized override fun write(name: String, value: String) {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val temp = directory.resolve("$name.tmp")
        temp.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP))
        val dest = directory.resolve(name)
        if (!temp.renameTo(dest)) { temp.copyTo(dest, overwrite = true); temp.delete() }
    }
    @Synchronized override fun read(name: String): String? {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        val file = directory.resolve(name)
        if (!file.exists()) return null
        return runCatching {
            val parts = file.readText().split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
    @Synchronized override fun remove(name: String) { require(name.matches(Regex("[a-zA-Z0-9_-]+"))); directory.resolve(name).delete() }
    companion object { private const val ALIAS = "redtv-private-v2"; private val keyLock=Any() }
}
