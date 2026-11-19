package com.jonas.familytweaks.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jonas.familytweaks.data.AppInfo
import com.jonas.familytweaks.data.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for one of the two app tabs.
 *
 * [selectionCount] counts USER SELECTIONS only; [activeAdminCount] counts
 * packages Android reports as ACTIVE admins. The two are intentionally
 * separate numbers and must never be merged in the UI.
 */
data class TabUiState(
    val apps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val selectionCount: Int = 0,
    val activeAdminCount: Int = 0,
    val availableAdminCount: Int = 0
)

/**
 * ViewModel for the app list. Holds no Context reference beyond the
 * application context (AndroidViewModel) and talks to the process-wide
 * AppRepository singleton only – so it survives rotation without leaks.
 */
class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository.get(application)

    private val _userTab = MutableStateFlow(TabUiState())
    val userTab: StateFlow<TabUiState> = _userTab.asStateFlow()

    private val _systemTab = MutableStateFlow(TabUiState())
    val systemTab: StateFlow<TabUiState> = _systemTab.asStateFlow()

    /** Lowercased query; filtering happens on the already-loaded lists. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    init {
        // Collect the single repository stream once per ViewModel and split
        // it into the two tabs. New scan results replace old ones atomically.
        viewModelScope.launch {
            repository.apps.collect { all ->
                publish(all)
            }
        }
    }

    fun setQuery(query: String) {
        _query.value = query
        // Re-filter from cached data – NO new PackageManager query per keystroke.
        viewModelScope.launch {
            repository.apps.replayCache.firstOrNull()?.let { publish(it) }
        }
    }

    /** Triggered on startup and on every return from system settings. */
    fun refresh() {
        _userTab.update { it.copy(isLoading = true) }
        _systemTab.update { it.copy(isLoading = true) }
        repository.refresh()
    }

    fun toggleSelection(packageName: String) {
        repository.selectionStore.toggle(packageName)
        // Reflect immediately from cached data; a full rescan is unnecessary.
        viewModelScope.launch {
            repository.apps.replayCache.firstOrNull()?.let { all ->
                publish(all.map {
                    if (it.packageName == packageName) {
                        it.copy(isSelected = repository.selectionStore.isSelected(it.packageName))
                    } else it
                })
            }
        }
    }

    private fun publish(all: List<AppInfo>) {
        val q = _query.value
        val user = all.filter { !it.isSystemApp && matches(it, q) }
        val system = all.filter { it.isSystemApp && matches(it, q) }

        _userTab.value = TabUiState(
            apps = user,
            isLoading = false,
            selectionCount = user.count { it.isSelected },
            activeAdminCount = user.count { it.isAdminActive },
            availableAdminCount = user.count { !it.isAdminActive && it.adminComponents.isNotEmpty() }
        )
        _systemTab.value = TabUiState(
            apps = system,
            isLoading = false,
            selectionCount = system.count { it.isSelected },
            activeAdminCount = system.count { it.isAdminActive },
            availableAdminCount = system.count { !it.isAdminActive && it.adminComponents.isNotEmpty() }
        )
    }

    private fun matches(app: AppInfo, query: String): Boolean =
        query.isEmpty() ||
            app.label.lowercase().contains(query) ||
            app.packageName.lowercase().contains(query)

    private inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
        while (true) {
            val prev = value
            val next = function(prev)
            if (compareAndSet(prev, next)) return
        }
    }
}
