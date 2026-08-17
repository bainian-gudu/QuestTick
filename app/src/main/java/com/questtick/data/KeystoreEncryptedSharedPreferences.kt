package com.questtick.data

import android.content.Context
import android.content.SharedPreferences
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

/**
 * 兼容 SharedPreferences 接口的本地加密存储。
 *
 * 字符串值会先使用 Android Keystore 保护的 AES-256-GCM 密钥加密，再写入普通
 * SharedPreferences。应用只保存随机 IV 与密文，不保存可导出的明文密钥。
 *
 * SharedPreferences 的基础类型均使用带类型前缀的载荷加密，读取时严格校验对应类型；
 * String 使用原始字符串载荷，StringSet 中的每个元素会先独立编码再整体加密。
 */
internal class KeystoreEncryptedSharedPreferences(
    context: Context,
    fileName: String,
) : SharedPreferences {
    private val appContext = context.applicationContext
    private val delegate = appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? {
        key ?: return defValue
        val plain = decryptStoredValue(key) ?: return defValue
        return if (isTypedPayload(plain)) defValue else plain
    }

    override fun getAll(): MutableMap<String, *> {
        val result = linkedMapOf<String, Any>()
        delegate.all.forEach { (key, value) ->
            val encrypted = value as? String ?: return@forEach
            decryptToString(encrypted)?.let { plain -> result[key] = decodeStoredValue(plain) }
        }
        return result
    }

    override fun contains(key: String?): Boolean = key != null && delegate.contains(key)

    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        key ?: return defValues
        val plain = decryptStoredValue(key) ?: return defValues
        return decodeStringSet(plain) ?: defValues
    }

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int {
        key ?: return defValue
        val plain = decryptStoredValue(key) ?: return defValue
        return decodeTypedPayload(plain, TYPE_INT)?.toIntOrNull() ?: defValue
    }

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long {
        key ?: return defValue
        val plain = decryptStoredValue(key) ?: return defValue
        return decodeTypedPayload(plain, TYPE_LONG)?.toLongOrNull() ?: defValue
    }

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float {
        key ?: return defValue
        val plain = decryptStoredValue(key) ?: return defValue
        return decodeTypedPayload(plain, TYPE_FLOAT)?.toFloatOrNull() ?: defValue
    }

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean {
        key ?: return defValue
        val plain = decryptStoredValue(key) ?: return defValue
        return decodeTypedPayload(plain, TYPE_BOOLEAN)?.toBooleanStrictOrNull() ?: defValue
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        delegate.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        delegate.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private inner class Editor(
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor =
            apply {
                key ?: return@apply
                if (value == null) delegateEditor.remove(key) else delegateEditor.putString(key, encryptString(value))
            }

        override fun remove(key: String?): SharedPreferences.Editor =
            apply {
                key?.let { delegateEditor.remove(it) }
            }

        override fun clear(): SharedPreferences.Editor = apply { delegateEditor.clear() }

        override fun commit(): Boolean = delegateEditor.commit()

        override fun apply() = delegateEditor.apply()

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor =
            apply {
                key ?: return@apply
                if (values == null) {
                    delegateEditor.remove(key)
                } else {
                    delegateEditor.putString(key, encryptString(encodeStringSet(values)))
                }
            }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = putTypedValue(key, TYPE_INT, value.toString())

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = putTypedValue(key, TYPE_LONG, value.toString())

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = putTypedValue(key, TYPE_FLOAT, value.toString())

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = putTypedValue(key, TYPE_BOOLEAN, value.toString())

        private fun putTypedValue(
            key: String?,
            type: String,
            value: String,
        ): SharedPreferences.Editor =
            apply {
                key ?: return@apply
                delegateEditor.putString(key, encryptString(encodeTypedPayload(type, value)))
            }
    }

    private fun decryptStoredValue(key: String): String? {
        val encrypted = delegate.getString(key, null) ?: return null
        return decryptToString(encrypted)
    }

    private fun encodeTypedPayload(
        type: String,
        value: String,
    ): String = "$TYPE_PREFIX$type:$value"

    private fun decodeTypedPayload(
        plain: String,
        expectedType: String,
    ): String? {
        val prefix = "$TYPE_PREFIX$expectedType:"
        return plain.takeIf { it.startsWith(prefix) }?.substring(prefix.length)
    }

    private fun isTypedPayload(plain: String): Boolean = plain.startsWith(TYPE_PREFIX)

    private fun decodeStoredValue(plain: String): Any {
        decodeTypedPayload(plain, TYPE_BOOLEAN)?.toBooleanStrictOrNull()?.let { return it }
        decodeTypedPayload(plain, TYPE_INT)?.toIntOrNull()?.let { return it }
        decodeTypedPayload(plain, TYPE_LONG)?.toLongOrNull()?.let { return it }
        decodeTypedPayload(plain, TYPE_FLOAT)?.toFloatOrNull()?.let { return it }
        decodeStringSet(plain)?.let { return it }
        return plain
    }

    private fun encodeStringSet(values: Set<String>): String {
        val encodedValues =
            values.map { value ->
                Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            }
        return encodeTypedPayload(TYPE_STRING_SET, encodedValues.joinToString(STRING_SET_SEPARATOR))
    }

    private fun decodeStringSet(plain: String): MutableSet<String>? {
        val payload = decodeTypedPayload(plain, TYPE_STRING_SET) ?: return null
        if (payload.isEmpty()) return linkedSetOf()
        return try {
            payload.split(STRING_SET_SEPARATOR)
                .mapTo(linkedSetOf()) { encoded ->
                    String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8)
                }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to decode secure string set: ${e.message}")
            null
        }
    }

    private fun encryptString(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(cipherText, 0, payload, iv.size, cipherText.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptToString(encoded: String): String? {
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_SIZE_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_SIZE_BYTES)
            val cipherText = payload.copyOfRange(GCM_IV_SIZE_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
            String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt secure preference value: ${e.message}")
            null
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
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
                // 明确不需要用户认证，避免锁屏时无法解密后台签到任务
                .setUserAuthenticationRequired(false)
        // Android P+ 防止生物识别注册导致密钥失效误伤（此处未开启用户认证，仍显式设置以便未来扩展）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            specBuilder.setInvalidatedByBiometricEnrollment(false)
        }
        // 允许设备重启后、用户首次解锁前仍可解密，保证后台定时签到可用
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            specBuilder.setUnlockedDeviceRequired(false)
        }
        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "KeystorePrefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "signin_secure_preferences"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_SIZE_BYTES = 12
        private const val GCM_TAG_SIZE_BITS = 128
        private const val TYPE_PREFIX = "__signin_secure_pref__:"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_INT = "int"
        private const val TYPE_LONG = "long"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_STRING_SET = "string_set"
        private const val STRING_SET_SEPARATOR = ","
    }
}
