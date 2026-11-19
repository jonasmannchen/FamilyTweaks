package com.jonas.familytweaks.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.jonas.familytweaks.admin.AdminLogic
import com.jonas.familytweaks.admin.DeviceAdminController
import com.jonas.familytweaks.util.Compat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the app list.
 *
 * Responsibilities:
 *  - Enumerate ALL installed packages of the current user (including apps
 *    without launcher activity and disabled apps) via PackageManager on
 *    Dispatchers.IO.
 *  - Join that list with (a) the framework's device-admin truth from
 *    DeviceAdminController and (b) the user's locally stored selections –
 *    strictly keeping those two apart in AppInfo.
 *  - React to package add/remove/replace broadcasts and to our own admin
 *    enable/disable events by scheduling a debounced rescan.
 *
 * Race-condition handling: [isScanning] guards against overlapping scans;
 * if an event arrives during a scan, a follow-up scan is scheduled so the
 * result can never be stale-by-race.
 */
class AppRepository private constructor(context: Context) {

    private val supervisorJob = kotlinx.coroutines.SupervisorJob()
    private val appContext = context.applicationContext
    private val pm: PackageManager get() = appContext.packageManager
    private val controller = DeviceAdminController(appContext)
    val selectionStore = SelectionStore(appContext)

    /** Application-lifetime scope for scans; no Activity/ViewModel leak possible. */
    private val scope = CoroutineScope(
        supervisorJob + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
            android.util.Log.e("AppRepository", "scan failed", t)
        }
    )

    private val _apps = MutableSharedFlow<List<AppInfo>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val apps: SharedFlow<List<AppInfo>> = _apps.asSharedFlow()

    /** Last known active admins (framework truth), for activation requests. */
    @Volatile
    var lastActiveAdmins: List<ComponentName> = emptyList()
        private set

    // ---- broadcasting ------------------------------------------------------

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_CHANGED -> scheduleRescan(debounceMs = 500L)
            }
        }
    }

    private val adminReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Our own admin was enabled/disabled by the user in system UI.
            scheduleRescan(debounceMs = 250L)
        }
    }

    fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        appContext.registerReceiver(packageReceiver, filter)

        val adminFilter = IntentFilter().apply {
            addAction(Intent.ACTION_DEVICE_ADMIN_ENABLED)
            addAction(Intent.ACTION_DEVICE_ADMIN_DISABLED)
        }
        appContext.registerReceiver(adminReceiver, adminFilter)
    }

    fun unregisterReceivers() {
        mainHandler.removeCallbacks(rescanRunnable)
        runCatching { appContext.unregisterReceiver(packageReceiver) }
        runCatching { appContext.unregisterReceiver(adminReceiver) }
    }

    // ---- scanning ----------------------------------------------------------

    private val scanMutex = Mutex()
    private var pendingRescan = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Performs a full scan on Dispatchers.IO and emits the result. Concurrent
     * calls are collapsed: while a scan runs, additional requests only set a
     * "rescan pending" flag, so results can never be stale-by-race and the
     * PackageManager is never queried twice in parallel.
     */
    fun refresh() {
        scope.launch {
            scanMutex.withLock {
                do {
                    pendingRescan = false
                    val result = withContext(Dispatchers.IO) { buildAppList() }
                    _apps.tryEmit(result)
                } while (pendingRescan)
            }
        }
    }

    /** Debounced rescan triggered by package/admin broadcast events. */
    private fun scheduleRescan(debounceMs: Long) {
        mainHandler.removeCallbacks(rescanRunnable)
        mainHandler.postDelayed(rescanRunnable, debounceMs)
    }

    private val rescanRunnable = Runnable { refresh() }

    /**
     * Builds the complete, sorted app list. Pure IO work – safe off the main
     * thread. Every per-package failure is contained: one broken package can
     * never abort the whole scan.
     */
    private fun buildAppList(): List<AppInfo> {
        val raw: List<ApplicationInfo> = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(Compat.INSTALLED_APPS_FLAGS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(Compat.INSTALLED_APPS_FLAGS)
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Framework truth #1: our own admin state (always readable).
        val selfActive = controller.isSelfAdminActive()
        // Framework truth #2: all active admins (only visible once we ARE admin).
        val activeAdmins = if (selfActive) controller.getActiveAdmins() else emptyList()
        lastActiveAdmins = activeAdmins
        val activePackages = activeAdmins.mapNotNull { it.packageName }.toHashSet()

        val selected = selectionStore.selectedPackages

        val infos = raw.mapNotNull { ai ->
            try {
                val pkg = ai.packageName
                val isActive = activePackages.contains(pkg)
                // Active status comes from the framework; the receiver query
                // still runs so the UI can show WHICH component is active.
                val receivers = controller.findDeclaredAdminReceivers(pkg)
                val status = AdminLogic.classify(
                    hasValidReceiver = receivers.isNotEmpty(),
                    isActiveAdmin = isActive
                )
                AppInfo(
                    packageName = pkg,
                    label = Compat.safeLabel(ai, pm),
                    isSystemApp = Compat.isSystemApp(ai),
                    isAdminActive = status == AdminStatus.ADMIN_ACTIVE,
                    adminComponents = receivers.map { it.className },
                    isSelected = selected.contains(pkg),
                    isEnabled = Compat.isEnabled(ai, pm)
                )
            } catch (e: Exception) {
                null // unreadable / vanished package: skip, keep scanning
            }
        }

        // Clean up selections pointing at uninstalled packages.
        selectionStore.retainOnly(infos.mapTo(HashSet()) { it.packageName })

        return infos.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
    }

    // ---- actions -----------------------------------------------------------

    /** Result of asking FamilyTweaks to protect one app via its own admin. */
    sealed class ProtectResult {
        data object AlreadyActive : ProtectResult()
        data class CanActivate(val component: ComponentName) : ProtectResult()
        data object NoAdminAvailable : ProtectResult()
    }

    /**
     * Checks whether [packageName] really owns a usable device-admin receiver
     * and, if so, returns the exact ComponentName for the official Android
     * activation screen. NEVER fabricates components: only receivers found in
     * the target package's own manifest are considered.
     */
    fun requestProtectionCheck(packageName: String): ProtectResult {
        val candidates = controller.findDeclaredAdminReceivers(packageName)
        if (candidates.isEmpty()) return ProtectResult.NoAdminAvailable
        val component = AdminLogic.firstAdminForPackage(candidates, packageName)
            ?: return ProtectResult.NoAdminAvailable
        return ProtectResult.CanActivate(component)
    }

    /** Opens the official ACTION_ADD_DEVICE_ADMIN screen for [component]. */
    fun launchActivation(component: ComponentName, explanation: String) {
        controller.requestActivation(component, explanation)
    }

    fun openAdminSettings() = controller.openAdminSettings()

    fun isSelfAdminActiveNow(): Boolean = controller.isSelfAdminActive()

    companion object {
        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context).also { instance = it }
            }
    }
}
