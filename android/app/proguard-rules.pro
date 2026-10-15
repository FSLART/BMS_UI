# ProGuard rules for BMS UI Android App
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class pt.fslart.bms.model.** { *; }
-keep class pt.fslart.bms.ble.** { *; }
