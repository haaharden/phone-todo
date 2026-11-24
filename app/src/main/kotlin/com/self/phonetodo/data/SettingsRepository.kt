package com.self.phonetodo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.self.phonetodo.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class GoogleOAuthTokens(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val codeVerifier: String = "",
)

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val lock = Any()
    private val dataDir = File(context.filesDir, "phone-todo-data").apply { mkdirs() }
    private val settingsFile = File(dataDir, "settings.json")
    private val tokenFile = File(dataDir, "oauth_tokens.enc")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return runCatching {
            json.decodeFromString<AppSettings>(settingsFile.readText(Charsets.UTF_8))
        }.getOrElse { AppSettings() }
    }

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val next = transform(_settings.value)
            val tmp = File(dataDir, "settings.json.tmp")
            tmp.writeText(json.encodeToString(AppSettings.serializer(), next), Charsets.UTF_8)
            if (!tmp.renameTo(settingsFile)) {
                settingsFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
            _settings.value = next
            return next
        }
    }

    // --- Encrypted OAuth Token Storage via Android Keystore AES-256-GCM ---

    private fun getOrCreateKey(): SecretKey {
        val alias = "phone_todo_oauth_key_v1"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun saveTokens(tokens: GoogleOAuthTokens) {
        synchronized(lock) {
            runCatching {
                val plain = json.encodeToString(GoogleOAuthTokens.serializer(), tokens).toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val iv = cipher.iv
                val enc = cipher.doFinal(plain)
                val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(enc, Base64.NO_WRAP)
                tokenFile.writeText(payload, Charsets.UTF_8)
            }
        }
    }

    fun loadTokens(): GoogleOAuthTokens {
        synchronized(lock) {
            if (!tokenFile.exists()) return GoogleOAuthTokens()
            return runCatching {
                val parts = tokenFile.readText(Charsets.UTF_8).split(":")
                if (parts.size != 2) return GoogleOAuthTokens()
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val enc = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                val plain = String(cipher.doFinal(enc), Charsets.UTF_8)
                json.decodeFromString<GoogleOAuthTokens>(plain)
            }.getOrElse { GoogleOAuthTokens() }
        }
    }

    fun clearTokens() {
        synchronized(lock) {
            tokenFile.delete()
        }
    }
}
