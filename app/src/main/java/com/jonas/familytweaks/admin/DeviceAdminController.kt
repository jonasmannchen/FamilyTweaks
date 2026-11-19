package com.jonas.familytweaks.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import com.jonas.familytweaks.data.AdminStatus

/** Status plus the underlying admin components for one package. */
data class PackageAdminState(
    val status: AdminStatus,
    val components: List<ComponentName>
)

/**
 * Central, stateless facade over DevicePolicyManager + PackageManager for
 * everything FamilyTweaks needs to know about device administration.
 *
 * Design rules:
 *  - Every method is safe to call from a background thread (Dispatchers.IO).
 *  - Nothing here caches Android's admin state; the truth is always re-read
 *    from the framework so that deactivating an admin in system settings is
 *    reflected immediately on the next refresh.
 *  - All framework calls are wrapped so that SecurityException, removed
 *    packages and broken OEM components degrade gracefully instead of
 *    crashing the scan.
 */
class DeviceAdminController(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager
    private val dpm: DevicePolicyManager get() = context.devicePolicyManager

    /** ComponentName of FamilyTweaks' own receiver. */
    val ownAdminComponent: ComponentName
        get() = FamilyTweaksAdminReceiver.getComponentName(context)

    /** Whether this device supports device administration at all. */
    fun isDeviceAdminSupported(): Boolean =
        AdminLogic.deviceAdminSupportedByDevice(pm)

    /**
     * True only when ANDROID reports FamilyTweaks' own receiver as an ACTIVE
     * device administrator. isAdminActive() works without any special
     * permission for one's own component on every supported API level.
     */
    fun isSelfAdminActive(): Boolean = try {
        dpm.isAdminActive(ownAdminComponent)
    } catch (e: SecurityException) {
        false
    }

    /**
     * The list of ALL currently active device-admin components on this
     * device, as reported by DevicePolicyManager.getActiveAdmins().
     *
     * IMPORTANT FRAMEWORK LIMITATION: getActiveAdmins() only returns data
     * once this app itself is an ACTIVE device admin; otherwise the
     * framework returns null. Callers must therefore treat "no active admins
     * visible" as "unknown", never as proof that no other admin exists.
     * [buildForeignStatusMap] handles this honestly via [canSeeActiveAdmins].
     */
    fun getActiveAdmins(): List<ComponentName> = try {
        dpm.activeAdmins ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    /**
     * Device-admin receivers declared by [packageName], filtered to those
     * that really carry the BIND_DEVICE_ADMIN permission attribute.
     *
     *  - API 30+: uses the non-deprecated PackageInfoFlags overload.
     *  - API 28/29: same query with the legacy int-flags overload.
     *
     * Returns an empty list for unknown/removed packages and never throws.
     */
    fun findDeclaredAdminReceivers(packageName: String): List<ComponentName> {
        val receivers = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_RECEIVERS.toLong())
                ).receivers?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_RECEIVERS)
                    .receivers?.toList().orEmpty()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList() // uninstalled mid-scan – not an error
        } catch (e: RuntimeException) {
            return emptyList() // defensive against broken framework entries
        }

        return receivers
            .filter { it.permission == AdminLogic.BIND_DEVICE_ADMIN_PERMISSION && it.name != null }
            .map { ComponentName(packageName, it.name!!) }
    }

    /**
     * Builds the authoritative per-package admin status map for [packageNames].
     *
     * Truth sources, strictly separated:
     *  - ADMIN_ACTIVE:   package appears among getActiveAdmins() (framework truth).
     *  - ADMIN_AVAILABLE: package declares a BIND_DEVICE_ADMIN receiver but
     *    none of its admins is active.
     *  - NO_ADMIN:       neither of the above.
     *
     * If FamilyTweaks is not itself an active admin ([canSeeActiveAdmins] =
     * false), foreign ACTIVE status cannot be verified through
     * getActiveAdmins(); such packages are then honestly reported as
     * AVAILABLE at most – NEVER as ACTIVE. (FamilyTweaks' OWN status always
     * works through isAdminActive(), which has no such restriction.)
     */
    fun buildForeignStatusMap(
        packageNames: Collection<String>,
        canSeeActiveAdmins: Boolean
    ): Map<String, PackageAdminState> {
        val activeAdmins = if (canSeeActiveAdmins) getActiveAdmins() else emptyList()
        val activePackages = activeAdmins.mapNotNull { it.packageName }.toHashSet()

        return packageNames.associateWith { pkg ->
            val isActive = activePackages.contains(pkg)
            val receivers = if (isActive) {
                emptyList() // already known to be active; skip expensive query
            } else {
                findDeclaredAdminReceivers(pkg)
            }
            val status = AdminLogic.classify(
                hasValidReceiver = receivers.isNotEmpty(),
                isActiveAdmin = isActive
            )
            PackageAdminState(
                status = status,
                components = if (isActive) AdminLogic.adminsOfPackage(activeAdmins, pkg) else receivers
            )
        }
    }

    /**
     * Requests activation of [component] through Android's official system
     * screen (ACTION_ADD_DEVICE_ADMIN). This NEVER activates anything by
     * itself – the user must confirm in the system dialog. May throw
     * ActivityNotFoundException on devices without the settings screen;
     * callers must handle it.
     */
    fun requestActivation(component: ComponentName, explanation: String) {
        context.startActivity(buildAddAdminIntent(component, explanation))
    }

    /** Opens Android's device-admin management settings screen. */
    fun openAdminSettings() = FamilyTweaksAdminReceiver.launchAdminSettings(context)

    /**
     * Shows Android's admin screen for FamilyTweaks' own component. From
     * there the user can manage/deactivate the admin in the system UI.
     * Direct removeActiveAdmin() calls are intentionally NOT used: since
     * Android 9 self-removal silently redirects to Settings anyway, so the
     * official screen is the honest path.
     */
    fun requestSelfAdminManagement() {
        try {
            context.startActivity(
                buildAddAdminIntent(ownAdminComponent, "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            openAdminSettings()
        }
    }
}
