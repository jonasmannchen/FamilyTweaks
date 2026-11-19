package com.jonas.familytweaks.data

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Local persistence for USER SELECTIONS only.
 *
 * This class deliberately stores nothing about admin status: what Android
 * reports via DevicePolicyManager is always re-read live. Selections here
 * are pure preferences ("the user ticked this app") and are joined with the
 * framework truth in AppRepository – never conflated with it.
 *
 * Thread-safety: reads go through an in-memory set kept in sync with
 * SharedPreferences, so background scans never hit disk per item. Writes are
 * committed asynchronously (apply()) but the memory copy updates instantly.
 */
class SelectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Live in-memory mirror; CopyOnWriteArraySet tolerates concurrent scan + UI access. */
    @Volatile
    private var cache: Set<String> = CopyOnWriteArraySet(
        prefs.getStringSet(KEY_SELECTED, emptySet()) ?: emptySet<String>()
    )

    val selectedPackages: Set<String> get() = cache

    fun isSelected(packageName: String): Boolean = cache.contains(packageName)

    fun toggle(packageName: String): Boolean = synchronized(this) {
        val next = HashSet(cache)
        val nowSelected = if (next.contains(packageName)) {
            next.remove(packageName); false
        } else {
            next.add(packageName); true
        }
        persist(next)
        nowSelected
    }

    fun setSelected(packageName: String, selected: Boolean) = synchronized(this) {
        val next = HashSet(cache)
        if (selected) next.add(packageName) else next.remove(packageName)
        persist(next)
    }

    /** Drops selections whose packages no longer exist (post-uninstall cleanup). */
    fun retainOnly(existingPackages: Set<String>) = synchronized(this) {
        val next = HashSet(cache).apply { retainAll(existingPackages) }
        if (next.size != cache.size) persist(next)
    }

    private fun persist(set: Set<String>) {
        cache = CopyOnWriteArraySet(set)
        prefs.edit { putStringSet(KEY_SELECTED, set) }
    }

    companion object {
        private const val PREFS_NAME = "familytweaks_selections"
        private const val KEY_SELECTED = "selected_packages"
    }
}
