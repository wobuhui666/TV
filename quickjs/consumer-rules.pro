# JNI reads the pinned wrapper's runtime handle; preserve its field name under R8.
-keepclassmembers class com.whl.quickjs.wrapper.QuickJSContext { long runtime; }
