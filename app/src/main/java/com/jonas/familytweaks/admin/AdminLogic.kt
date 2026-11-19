package com.jonas.familytweaks.admin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.admin.DevicePolicyManager
import com.jonas.familytweaks.data.AdminStatus

/**
 * Pure, context-free classification helpers around device-admin detection.
 *
 * These functions contain the core business logic of FamilyTweaks and are
 * fully unit-testable on the JVM without Robolectric or a real device.
 */
object AdminLogic {

    /** Name of the meta-data entry every device-admin receiver must declare. */
    const val DEVICE_ADMIN_META_DATA_NAME = "android.app.device_admin"

    /** Signature-level permission the system requires on admin receivers. */
    const val BIND_DEVICE_ADMIN_PERMISSION = "android.permission.BIND_DEVICE_ADMIN"

    /**
     * Classifies one package from the two facts Android provides:
     * - does the package own at least one VALID device-admin receiver?
     * - is at least one of its admins ACTIVE according to DevicePolicyManager?
     *
     * Active always wins. A stored user selection is deliberately NOT an
     * input here: a selection must never be mistaken for real protection.
     */
    fun classify(hasValidReceiver: Boolean, isActiveAdmin: Boolean): AdminStatus = when {
        isActiveAdmin -> AdminStatus.ADMIN_ACTIVE
        hasValidReceiver -> AdminStatus.ADMIN_AVAILABLE
        else -> AdminStatus.NO_ADMIN
    }

    /**
     * Returns the subset of [admins] whose component belongs to [packageName].
     * Comparison uses the exact package name of each ComponentName – never
     * string prefixes or labels – so components of similarly named packages
     * (e.g. "com.foo" vs "com.foo.bar") can never be confused.
     */
    fun adminsOfPackage(admins: List<ComponentName>, packageName: String): List<ComponentName> =
        admins.filter { it.packageName == packageName }

    /** True when at least one active admin component belongs to [packageName]. */
    fun isActiveAdminOfPackage(
        activeAdmins: List<ComponentName>,
        packageName: String
    ): Boolean = adminsOfPackage(activeAdmins, packageName).isNotEmpty()

    /**
     * Picks the first admin component of [packageName], if any. Used as the
     * EXTRA_DEVICE_ADMIN argument when requesting activation via the official
     * ACTION_ADD_DEVICE_ADMIN screen.
     */
    fun firstAdminForPackage(
        admins: List<ComponentName>,
        packageName: String
    ): ComponentName? = adminsOfPackage(admins, packageName).firstOrNull()

    /**
     * Checks whether a raw receiver entry describes a real device-admin
     * receiver:
     *  1. it must be protected by android.permission.BIND_DEVICE_ADMIN
     *     (signature-level permission held by the Android system), and
     *  2. it must reference the policy XML through the meta-data resource
     *     "android.app.device_admin".
     *
     * Anything else is NOT a device administrator – no matter what any
     * third-party tool claims.
     */
    fun isValidAdminComponentInfo(
        permName: String?,
        hasDeviceAdminMetaData: Boolean
    ): Boolean =
        permName == BIND_DEVICE_ADMIN_PERMISSION && hasDeviceAdminMetaData

    /**
     * Same check performed against a PackageManager on API < 30, where the
     * dedicated capability query does not exist yet. Never throws: a missing
     * or unreadable component simply counts as "not an admin".
     */
    @Suppress("DEPRECATION")
    fun isValidAdminComponentLegacy(pm: PackageManager, component: ComponentName): Boolean {
        val info = try {
            pm.getReceiverInfo(component, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        } catch (e: RuntimeException) {
            // Defensive: unusual OEM implementations may throw other runtime
            // errors for broken components. Treat them as "no valid admin".
            return false
        }
        val hasMeta = info?.metaData?.containsKey(DEVICE_ADMIN_META_DATA_NAME) == true
        return isValidAdminComponentInfo(info?.permission, hasMeta)
    }

    /**
     * Whether the device supports device administration at all
     * (PackageManager.FEATURE_DEVICE_ADMIN).
     */
    fun deviceAdminSupportedByDevice(pm: PackageManager): Boolean =
        pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
}

/**
 * Builds the official "add device admin" intent for an arbitrary component.
 * The system UI will ALWAYS ask the user to confirm; nothing is activated
 * silently, and only components that really exist in their own package can
 * ever be passed here.
 */
fun buildAddAdminIntent(component: ComponentName, explanation: String): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)

/** Small convenience accessor so call sites read naturally. */
internal val Context.devicePolicyManager: DevicePolicyManager
    get() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

/** True on Android 13 (API 33)+ where richer component queries exist. */
internal val supportsApi33: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
