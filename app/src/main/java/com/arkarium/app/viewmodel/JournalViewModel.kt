package com.arkarium.app.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arkarium.app.data.JournalFeedClient
import com.arkarium.app.data.JournalLoadResult
import com.arkarium.app.data.JournalRepository
import java.io.File
import kotlinx.coroutines.launch

// Backs Rae's Journal (ui/RaesJournalScreen.kt). Thin on purpose - fetch/parse/cache
// logic lives in JournalRepository, which is what the unit tests exercise.
class JournalViewModel(private val repository: JournalRepository) : ViewModel() {

    // null until the first load finishes.
    val result = mutableStateOf<JournalLoadResult?>(null)
    val isLoading = mutableStateOf(false)

    private var lastFreshAt: Long? = null

    // Called whenever the page opens. A fresh copy younger than MIN_REFETCH_MS is reused
    // (the page is a drill-down people re-enter often; a journal doesn't change by the
    // minute); anything else - nothing yet, an offline copy, a failure - tries again.
    // `force` is the refresh button.
    fun load(force: Boolean = false) {
        if (isLoading.value) return
        val lastFresh = lastFreshAt
        if (!force && result.value is JournalLoadResult.Fresh && lastFresh != null &&
            System.currentTimeMillis() - lastFresh < MIN_REFETCH_MS
        ) return

        isLoading.value = true
        viewModelScope.launch {
            try {
                val loaded = repository.load()
                val current = result.value
                // Never replace content already on screen with a bare failure.
                val keepCurrent = loaded is JournalLoadResult.Failed &&
                    (current is JournalLoadResult.Fresh || current is JournalLoadResult.Offline)
                if (!keepCurrent) result.value = loaded
                if (loaded is JournalLoadResult.Fresh) lastFreshAt = System.currentTimeMillis()
            } finally {
                isLoading.value = false
            }
        }
    }

    companion object {
        private const val MIN_REFETCH_MS = 10 * 60 * 1000L

        fun factory(context: Context): ViewModelProvider.Factory {
            // applicationContext only - the ViewModel outlives any one Activity.
            val cacheFile = File(context.applicationContext.cacheDir, "journal-feed.xml")
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    JournalViewModel(JournalRepository(JournalFeedClient(), cacheFile)) as T
            }
        }
    }
}
