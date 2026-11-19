package com.jonas.familytweaks.admin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Finds the device-admin receivers that OTHER apps genuinely declare in
 * their own manifests. Kept as a thin, well-documented wrapper around
 * DeviceAdminController so the "foreign admin detection" responsibility is
 * visibly separated from general admin control.
 *
 * Hard technical facts this class respects:
 *  - A DeviceAdminReceiver always belongs to the package that declares it.
 *    No app can register one "on behalf of" another package, and no app can
 *    grant admin rights to a foreign package. Rights are only ever granted
 *    by the user through Android's official ACTION_ADD_DEVICE_ADMIN screen.
 *  - FamilyTweaks can therefore only (a) detect which packages ship valid
 *    admin receivers and (b) hand those real ComponentNames to the system UI.
 */
class ForeignAdminFinder(
    private val context: Context,
    private val controller: DeviceAdminController = DeviceAdminController(context)
) {

    /** Admin receiver components declared by [packageName]. Never throws. */
    fun findAdminCandidates(packageName: String): List<ComponentName> =
        controller.findDeclaredAdminReceivers(packageName)

    /**
     * True when [component] exists AND is guarded by BIND_DEVICE_ADMIN –
     * i.e. the minimum requirement for Android to accept it as an admin.
     * This is manifest-level validation; the framework performs the final,
     * authoritative check inside its own activation screen.
     */
    fun verifyComponent(component: ComponentName): Boolean {
        val pm = context.packageManager
        val info = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getReceiverInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                pm.getReceiverInfo(component, PackageManager.GET_META_DATA)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        } ?: return false

        // Authoritative minimum bar: the receiver must be guarded by the
        // signature-level BIND_DEVICE_ADMIN permission. The meta-data check
        // is only possible for components we can fully read; when it is
        // present it must reference "android.app.device_admin".
        if (info.permission != AdminLogic.BIND_DEVICE_ADMIN_PERMISSION) return false
        val meta = info.metaData
        return meta == null || !meta.keySet().isNotEmpty() ||
            meta.containsKey(AdminLogic.DEVICE_ADMIN_META_DATA_NAME) ||
            meta.size() == 0
    }

    /** Candidates that pass verification. Empty list for removed packages. */
    fun findVerifiedAdminComponents(packageName: String): List<ComponentName> =
        findAdminCandidates(packageName).filter { verifyComponent(it) }

    /**
     * The official intent asking ANDROID to show its activation screen for
     * [component]. The user must confirm there; FamilyTweaks never claims
     * success until getActiveAdmins() reports the component as active.
     */
    fun buildActivationIntent(component: ComponentName, explanation: String): Intent =
        buildAddAdminIntent(component, explanation)
}
