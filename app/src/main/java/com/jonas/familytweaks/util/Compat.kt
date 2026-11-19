package com.jonas.familytweaks.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Small compatibility helpers. Everything API-level dependent lives here so
 * the rest of the codebase stays free of Build.VERSION checks.
 */
object Compat {

    /**
     * Flags for PackageManager.getInstalledApplications().
     *
     * MATCH_DISABLED_COMPONENTS makes disabled apps visible; without it, an
     * app whose components were disabled (e.g. by a suspender/locker) would
     * silently vanish from our list. The int overload works on every API
     * level we support (28+).
     */
    const val INSTALLED_APPS_FLAGS =
        PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES

    /** Whether [app] is a system app – UPDATED_SYSTEM_APP counts as system too. */
    fun isSystemApp(app: ApplicationInfo): Boolean =
        AppClassifier.isSystemFlagSet(app.flags)

    /** Whether the package's components are currently enabled. */
    fun isEnabled(app: ApplicationInfo, pm: PackageManager): Boolean = try {
        app.enabled && pm.getApplicationEnabledSetting(app.packageName) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (e: Exception) {
        // SecurityException / IllegalArgumentException on removed packages:
        // fall back to the static manifest value instead of crashing.
        app.enabled
    }

    /** Safely resolve a launcher label; falls back to the package name. */
    fun safeLabel(app: ApplicationInfo, pm: PackageManager): String = try {
        pm.getApplicationLabel(app)?.toString().takeUnless { it.isNullOrBlank() }
            ?: app.packageName
    } catch (e: Exception) {
        app.packageName
    }

    /** Safely load an app icon; null when the framework cannot provide one. */
    fun safeIcon(app: ApplicationInfo, pm: PackageManager): android.graphics.drawable.Drawable? = try {
        pm.getApplicationIcon(app)
    } catch (e: Exception) {
        null // missing/broken icon – caller shows a placeholder
    }

    /** True when some activity can handle [intent] on this device. */
    fun canResolve(context: Context, intent: Intent): Boolean = try {
        intent.resolveActivity(context.packageManager) != null
    } catch (e: Exception) {
        false
    }
}

/**
 * Pure classification logic, separated from Android framework types so it is
 * trivially unit-testable on the JVM.
 */
object AppClassifier {

    /**
     * System-app rule: FLAG_SYSTEM marks preinstalled apps and
     * FLAG_UPDATED_SYSTEM_APP marks preinstalled apps that received updates
     * through the Play Store etc. Updated system apps must NOT be sorted
     * into the user-app tab, hence both flags count as "system".
     */
    fun isSystemFlagSet(flags: Int): Boolean =
        (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** Simple, allocation-light search filter over label and package name. */
    fun matchesQuery(label: String, packageName: String, queryLowercased: String): Boolean =
        queryLowercased.isEmpty() ||
            label.lowercase().contains(queryLowercased) ||
            packageName.lowercase().contains(queryLowercased)
}
