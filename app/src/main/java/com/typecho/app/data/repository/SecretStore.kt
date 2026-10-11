package com.typecho.app.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 接口密钥等同读写全站健康数据的权限，不能躺在明文偏好文件里。
 * AES-256-GCM 的密钥材料由 Keystore 持有且不可导出，偏好文件只存 iv + 密文。
 */
class SecretStore(context: Context) {

    private val appContext = context.applicationContext

    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, obtainKey())
        }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.getOrElse { "" }

    /** 解不出来（换机、Keystore 被清）时返回空串，让界面回到「请重新输入密钥」。 */
    fun decrypt(payload: String): String = runCatching {
        if (payload.isBlank()) return ""
        val combined = Base64.decode(payload, Base64.NO_WRAP)
        if (combined.size <= IvLength) return ""
        val spec = GCMParameterSpec(128, combined, 0, IvLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, obtainKey(), spec)
        }
        String(cipher.doFinal(combined, IvLength, combined.size - IvLength), Charsets.UTF_8)
    }.getOrElse { "" }

    fun encryptOrThrow(plain: String): String {
        val out = encrypt(plain)
        check(out.isNotBlank()) { "系统密钥库不可用，无法安全保存密钥" }
        return out
    }

    private fun obtainKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(Alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                Alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val Alias = "noline_api_key"
        const val IvLength = 12
    }
}
