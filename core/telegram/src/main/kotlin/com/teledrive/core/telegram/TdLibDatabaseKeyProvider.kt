package com.teledrive.core.telegram

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Keeps the TDLib database key stable across restarts while wrapping it with an
 * Android Keystore-backed AES-256-GCM key. The key itself is never logged or put
 * into Room/DataStore.
 */
class TdLibDatabaseKeyProvider(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "teledrive_tdlib_key",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getOrCreate(): ByteArray {
        val existing = preferences.getString(KEY, null)
        if (!existing.isNullOrBlank()) return Base64.decode(existing, Base64.NO_WRAP)
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        preferences.edit().putString(KEY, Base64.encodeToString(generated, Base64.NO_WRAP)).apply()
        return generated
    }

    private companion object {
        const val KEY = "tdlib_database_encryption_key"
    }
}
