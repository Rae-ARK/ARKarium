package com.arkarium.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore by preferencesDataStore(name = "arkarium_prefs")

enum class Theme {
    LIGHT, DARK, WARM_PAPER
}

class PreferencesManager(private val context: Context) {
    companion object {
        private val LIBRARY_URI_KEY = stringPreferencesKey("library_uri")
        private val THEME_KEY = stringPreferencesKey("theme")
        private val DEFAULT_PAGE_SIZE_KEY = intPreferencesKey("default_page_size")
    }

    // libraryUri.collect() and theme.collect() run unattended in MainActivity.onCreate,
    // before the user has touched anything - same situation as startScan(). DataStore's
    // .data Flow throws IOException if the prefs file on disk can't be read (corrupted
    // file, first-run race, low storage, some OEM storage quirks); without this .catch,
    // that exception propagates out of collect() and crashes the whole app on launch
    // since nothing downstream of dataStore.data was previously guarding for it. Falling
    // back to emptyPreferences() just means defaults are used for that read, matching
    // DataStore's own recommended pattern.
    private val safePrefs = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val libraryUri: Flow<String?> = safePrefs.map { prefs ->
        prefs[LIBRARY_URI_KEY]
    }

    val theme: Flow<Theme> = safePrefs.map { prefs ->
        when (prefs[THEME_KEY]) {
            "DARK" -> Theme.DARK
            "WARM_PAPER" -> Theme.WARM_PAPER
            else -> Theme.LIGHT
        }
    }

    val defaultPageSize: Flow<Int> = safePrefs.map { prefs ->
        prefs[DEFAULT_PAGE_SIZE_KEY] ?: 10
    }

    suspend fun setLibraryUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[LIBRARY_URI_KEY] = uri
        }
    }

    suspend fun setTheme(theme: Theme) {
        context.dataStore.edit { prefs ->
            prefs[THEME_KEY] = theme.name
        }
    }

    suspend fun setDefaultPageSize(size: Int) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_PAGE_SIZE_KEY] = size
        }
    }
}
