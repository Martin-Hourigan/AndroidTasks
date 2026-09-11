# Firestore maps documents onto data classes by reflection, so the model
# classes and their constructors have to survive shrinking or every read comes
# back with null fields in release builds only — the worst kind of bug to find.
-keepclassmembers class dev.mahourigan.tasks.data.** {
    <init>();
    <fields>;
}

# The install-result receiver is built by name from the manifest. Keeping its
# constructor explicitly rather than trusting a manifest rule to cover members
# — that assumption is exactly what silently broke the MoonWidget panel, where
# R8 kept the class and dropped the constructor Glance needed to reflect on.
-keep class dev.mahourigan.tasks.update.InstallResultReceiver { <init>(); }
