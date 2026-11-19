package com.jonas.familytweaks.data

/**
 * The three honest admin states FamilyTweaks distinguishes for every app.
 *
 * The crucial rule: [ADMIN_ACTIVE] may ONLY be reported when the Android
 * system itself (DevicePolicyManager.getActiveAdmins()) reports an active
 * admin component belonging to that package. A stored user selection is
 * never, ever treated as proof of protection.
 */
enum class AdminStatus {
    /** Android reports at least one ACTIVE device-admin receiver of this package. */
    ADMIN_ACTIVE,

    /**
     * The package declares a valid, system-accepted device-admin receiver
     * (BIND_DEVICE_ADMIN + DEVICE_ADMIN_META_DATA), but none of its admins
     * is currently active. Activation can be requested via the official
     * ACTION_ADD_DEVICE_ADMIN screen.
     */
    ADMIN_AVAILABLE,

    /**
     * The package has no usable device-admin receiver. This app CANNOT be
     * protected against setPackagesSuspended() through device administration.
     */
    NO_ADMIN
}

/**
 * Immutable snapshot of one installed package as shown in the UI.
 *
 * Note the strict separation between [isSelected] (a pure user preference
 * stored locally) and [adminStatus] (the actual state Android reports).
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val isAdminActive: Boolean,
    val adminComponents: List<String>,
    val isSelected: Boolean,
    val isEnabled: Boolean
) {
    /** Derived, single source of truth for the status badge. */
    val adminStatus: AdminStatus
        get() = when {
            isAdminActive -> AdminStatus.ADMIN_ACTIVE
            adminComponents.isNotEmpty() -> AdminStatus.ADMIN_AVAILABLE
            else -> AdminStatus.NO_ADMIN
        }
}
