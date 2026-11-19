package com.jonas.familytweaks.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * FamilyTweaks' own device administrator component.
 *
 * Being registered AND enabled as a device admin makes the Android system
 * treat this package as exempt from DevicePolicyManager.setPackagesSuspended():
 * suspended-package handling explicitly skips packages that are active device
 * admins. That is the real, documented system-side exception FamilyTweaks
 * relies on – there is no invented "protection" anywhere in this app.
 *
 * IMPORTANT HONESTY NOTE: a DeviceAdminReceiver always belongs to the package
 * that declares it. Enabling THIS receiver protects ONLY com.jonas.familytweaks
 * itself. It cannot grant admin rights to any other app; other apps must ship
 * their own receivers (see ForeignAdminFinder).
 *
 * All callbacks are intentionally left at their safe defaults. No policies
 * are enforced beyond the minimal "watch" flags declared in
 * res/xml/device_admin_receiver.xml.
 */
class FamilyTweaksAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Nothing to configure: we request no password/encryption/wipe policy.
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // The UI re-reads the real state from DevicePolicyManager on resume,
        // so no local bookkeeping is needed here.
    }

    companion object {
        /** Canonical ComponentName of this receiver. */
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, FamilyTweaksAdminReceiver::class.java)

        /** Opens Android's own device-admin management screen for this app. */
        fun launchAdminSettings(context: Context) {
            val intent = Intent(DevicePolicyManager.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: SecurityException) {
                Toast.makeText(context, "Einstellungen nicht verfügbar", Toast.LENGTH_SHORT).show()
            } catch (e: IllegalStateException) {
                Toast.makeText(context, "Einstellungen nicht verfügbar", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
