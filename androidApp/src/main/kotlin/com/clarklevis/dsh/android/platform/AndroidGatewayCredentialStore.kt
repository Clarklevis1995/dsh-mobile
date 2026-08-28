package com.clarklevis.dsh.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * token/device id 先由 Android Keystore AES-GCM 加密，再写入独立的 no-backup DataStore。
 * 普通配置 DataStore 永远不会出现敏感明文。
 */
class AndroidGatewayCredentialStore(context: Context) : GatewayCredentialStore {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.noBackupFilesDir.resolve(CREDENTIAL_FILE_NAME) }
    )
    private val mutex = Mutex()

    override suspend fun loadOrCreateDeviceId(): String = mutex.withLock {
        readEncrypted(DEVICE_ID_KEY, DEVICE_ID_AAD)?.takeIf(String::isNotBlank)?.let { return it }
        val value = UUID.randomUUID().toString().lowercase()
        writeEncrypted(DEVICE_ID_KEY, DEVICE_ID_AAD, value)
        value
    }

    override suspend fun loadToken(endpoint: String): String? = mutex.withLock {
        val logicalKey = tokenLogicalKey(endpoint)
        readEncrypted(stringPreferencesKey(logicalKey), logicalKey)
    }

    override suspend fun saveToken(endpoint: String, token: String) {
        require(token.isNotBlank()) { "token must not be blank" }
        mutex.withLock {
            val logicalKey = tokenLogicalKey(endpoint)
            writeEncrypted(stringPreferencesKey(logicalKey), logicalKey, token)
        }
    }

    override suspend fun deleteToken(endpoint: String) {
        mutex.withLock {
            dataStore.edit { it.remove(stringPreferencesKey(tokenLogicalKey(endpoint))) }
        }
    }

    private suspend fun readEncrypted(key: Preferences.Key<String>, aad: String): String? {
        val encoded = dataStore.data.first()[key] ?: return null
        return runCatching { decrypt(encoded, aad) }.getOrElse {
            // Keystore 失效或密文损坏时 fail closed，并只删除对应密文。
            dataStore.edit { preferences -> preferences.remove(key) }
            null
        }
    }

    private suspend fun writeEncrypted(key: Preferences.Key<String>, aad: String, value: String) {
        val encrypted = encrypt(value, aad)
        dataStore.edit { preferences -> preferences[key] = encrypted }
    }

    private fun encrypt(value: String, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String, aad: String): String {
        val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        require(buffer.get() == VERSION) { "unsupported credential ciphertext version" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32 && buffer.remaining() > ivLength) { "invalid credential ciphertext" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun tokenLogicalKey(endpoint: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.trimEnd('/').toByteArray())
        return "gateway_token_${digest.joinToString("") { "%02x".format(it) }}"
    }

    companion object {
        private const val CREDENTIAL_FILE_NAME = "gateway_credentials.preferences_pb"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dsh_gateway_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val DEVICE_ID_AAD = "gateway_device_id"
        private val DEVICE_ID_KEY = stringPreferencesKey(DEVICE_ID_AAD)
        private const val VERSION: Byte = 1
    }
}
