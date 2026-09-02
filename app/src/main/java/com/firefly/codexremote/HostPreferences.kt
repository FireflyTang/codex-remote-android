package com.firefly.codexremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

const val DefaultHostAddress = "ws://codex-remote-linux/connect"

private val Context.hostDataStore by preferencesDataStore(name = "connection")

class HostPreferences(context: Context) {
    private val dataStore = context.applicationContext.hostDataStore

    val hostAddress: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences[HostAddressKey] ?: DefaultHostAddress }

    val lastProjectPath: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> normalizeStoredProjectPath(preferences[LastProjectPathKey].orEmpty()) }

    suspend fun setHostAddress(value: String) {
        dataStore.edit { preferences ->
            preferences[HostAddressKey] = value
        }
    }

    suspend fun setLastProjectPath(value: String) {
        val normalized = normalizeStoredProjectPath(value)
        dataStore.edit { preferences ->
            if (normalized.isEmpty()) preferences.remove(LastProjectPathKey)
            else preferences[LastProjectPathKey] = normalized
        }
    }

    private companion object {
        val HostAddressKey = stringPreferencesKey("host_address")
        val LastProjectPathKey = stringPreferencesKey("last_project_path")
    }
}

internal const val LegacyDefaultProjectPath = "/home/user"

internal fun normalizeStoredProjectPath(value: String): String =
    value.trim().takeUnless { it.isEmpty() || it == LegacyDefaultProjectPath }.orEmpty()
