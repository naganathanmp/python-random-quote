package com.tareimbursement.app.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (sign-in tokens, Hugging Face token). */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a key that never leaves the Android Keystore. */
class KeystoreCipher(private val alias: String = "ta_secrets_v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

class SecretStore(context: Context, private val cipher: SecretCipher = KeystoreCipher()) {
    private val sp = context.getSharedPreferences("ta_secrets", Context.MODE_PRIVATE)

    fun get(key: String): String? = try {
        sp.getString(key, null)?.let { String(cipher.decrypt(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }
    } catch (e: Exception) { null }

    /** Returns false if the secret could not be stored securely (nothing is written in plain text). */
    fun put(key: String, value: String?): Boolean = try {
        if (value.isNullOrEmpty()) { sp.edit().remove(key).apply(); true }
        else { sp.edit().putString(key, Base64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply(); true }
    } catch (e: Exception) { false }
}
