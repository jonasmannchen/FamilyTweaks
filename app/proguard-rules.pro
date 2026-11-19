# FamilyTweaks does not use code shrinking (minifyEnabled = false), so no
# custom rules are required. DeviceAdminReceiver subclasses must be kept if
# shrinking is ever enabled; the manifest registration alone is enough for
# AGP's consumer rules in that case.
# -keep class com.jonas.familytweaks.admin.FamilyTweaksAdminReceiver { *; }
