package com.firefly.codexremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException

const val DefaultHostAddress = "ws://codex-remote-linux/connect"

data class PersistedCodexState(
    val selectedCodexId: String = "",
    val drafts: Map<String, String> = emptyMap(),
)

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

    val codexState: Flow<PersistedCodexState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            PersistedCodexState(
                selectedCodexId = preferences[SelectedCodexIdKey].orEmpty().trim(),
                drafts = decodeCodexDrafts(preferences[CodexDraftsKey].orEmpty()),
            )
        }

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

    suspend fun setSelectedCodexId(codexId: String) {
        val normalized = codexId.trim()
        dataStore.edit { preferences ->
            if (normalized.isEmpty()) preferences.remove(SelectedCodexIdKey)
            else preferences[SelectedCodexIdKey] = normalized
        }
    }

    suspend fun setCodexDraft(codexId: String, draft: String) {
        val normalizedId = codexId.trim()
        if (normalizedId.isEmpty()) return
        dataStore.edit { preferences ->
            val updated = PersistedCodexState(
                selectedCodexId = preferences[SelectedCodexIdKey].orEmpty(),
                drafts = decodeCodexDrafts(preferences[CodexDraftsKey].orEmpty()),
            ).withDraft(normalizedId, draft)
            if (updated.drafts.isEmpty()) preferences.remove(CodexDraftsKey)
            else preferences[CodexDraftsKey] = encodeCodexDrafts(updated.drafts)
        }
    }

    suspend fun forgetCodex(codexId: String) {
        val normalizedId = codexId.trim()
        if (normalizedId.isEmpty()) return
        dataStore.edit { preferences ->
            val updated = PersistedCodexState(
                selectedCodexId = preferences[SelectedCodexIdKey].orEmpty(),
                drafts = decodeCodexDrafts(preferences[CodexDraftsKey].orEmpty()),
            ).withoutCodex(normalizedId)
            if (updated.selectedCodexId.isEmpty()) preferences.remove(SelectedCodexIdKey)
            else preferences[SelectedCodexIdKey] = updated.selectedCodexId
            if (updated.drafts.isEmpty()) preferences.remove(CodexDraftsKey)
            else preferences[CodexDraftsKey] = encodeCodexDrafts(updated.drafts)
        }
    }

    private companion object {
        val HostAddressKey = stringPreferencesKey("host_address")
        val LastProjectPathKey = stringPreferencesKey("last_project_path")
        val SelectedCodexIdKey = stringPreferencesKey("selected_codex_id")
        val CodexDraftsKey = stringPreferencesKey("codex_drafts")
    }
}

internal const val LegacyDefaultProjectPath = "/home/user"

internal fun normalizeStoredProjectPath(value: String): String =
    value.trim().takeUnless { it.isEmpty() || it == LegacyDefaultProjectPath }.orEmpty()

internal fun encodeCodexDrafts(drafts: Map<String, String>): String = JSONObject().apply {
    drafts.toSortedMap().forEach { (codexId, draft) ->
        if (codexId.isNotBlank() && draft.isNotEmpty()) put(codexId, draft)
    }
}.toString()

internal fun decodeCodexDrafts(encoded: String): Map<String, String> = runCatching {
    val json = JSONObject(encoded.ifBlank { "{}" })
    buildMap {
        json.keys().forEach { codexId ->
            val draft = json.optString(codexId)
            if (codexId.isNotBlank() && draft.isNotEmpty()) put(codexId, draft)
        }
    }
}.getOrDefault(emptyMap())

internal fun PersistedCodexState.withDraft(codexId: String, draft: String): PersistedCodexState {
    val normalizedId = codexId.trim()
    if (normalizedId.isEmpty()) return this
    val updatedDrafts = drafts.toMutableMap().apply {
        if (draft.isEmpty()) remove(normalizedId) else put(normalizedId, draft)
    }
    return copy(drafts = updatedDrafts)
}

internal fun PersistedCodexState.withoutCodex(codexId: String): PersistedCodexState {
    val normalizedId = codexId.trim()
    if (normalizedId.isEmpty()) return this
    return copy(
        selectedCodexId = selectedCodexId.takeUnless { it == normalizedId }.orEmpty(),
        drafts = drafts - normalizedId,
    )
}
