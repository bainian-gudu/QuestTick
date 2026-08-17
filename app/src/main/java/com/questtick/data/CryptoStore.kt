package com.questtick.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM 加解密工具，用于 Room 中的敏感 payload。 */
internal object CryptoStore {
    fun encryptString(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(cipherText, 0, payload, iv.size, cipherText.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decryptToString(encoded: String): String? {
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_SIZE_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_SIZE_BYTES)
            val cipherText = payload.copyOfRange(GCM_IV_SIZE_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt payload: ${e.message}")
            null
        }
    }

    private val cachedSecretKey by lazy {
        getOrCreateSecretKeyInternal()
    }

    private fun getOrCreateSecretKey(): SecretKey = cachedSecretKey

    private fun getOrCreateSecretKeyInternal(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val specBuilder =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            specBuilder.setInvalidatedByBiometricEnrollment(false)
        }
        // 允许设备重启后、用户首次解锁前仍可解密，保证后台定时签到可用
        // setUnlockedDeviceRequired 默认为 false，此处显式设为 false 避免误开
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            specBuilder.setUnlockedDeviceRequired(false)
        }
        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    private const val TAG = "CryptoStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "signin_room_payload"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_SIZE_BYTES = 12
    private const val GCM_TAG_SIZE_BITS = 128
}
