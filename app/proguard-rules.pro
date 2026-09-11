# Firestore maps documents onto data classes by reflection, so the model
# classes and their constructors have to survive shrinking or every read comes
# back with null fields in release builds only — the worst kind of bug to find.
-keepclassmembers class dev.mahourigan.tasks.data.** {
    <init>();
    <fields>;
}
