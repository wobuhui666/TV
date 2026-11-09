# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.fongmi.quickjs.method.** { *; }
-keep class com.fongmi.android.tv.bean.** { *; }

# sherpa-onnx Java methods are JNI entry points and their names/signatures must remain stable.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# SimpleXML
-keep interface org.simpleframework.xml.core.Label { public *; }
-keep class * implements org.simpleframework.xml.core.Label { public *; }
-keep interface org.simpleframework.xml.core.Parameter { public *; }
-keep class * implements org.simpleframework.xml.core.Parameter { public *; }
-keep interface org.simpleframework.xml.core.Extractor { public *; }
-keep class * implements org.simpleframework.xml.core.Extractor { public *; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Path <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Root <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Text <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Element <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Attribute <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.ElementList <fields>; }

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# SLF4J
-keeppackagenames org.slf4j.**
-keep class org.slf4j.** { *; }

# Kotlin
-keeppackagenames kotlin.**
-keep class kotlin.** { *; }

# CatVod
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# Jianpian
-keep class com.p2p.** { *; }

# JUPnP
-dontwarn org.jupnp.**
-keep class org.jupnp.** { *; }
-keep class javax.xml.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# TVBus
-keep class com.tvbus.engine.** { *; }

# XunLei
-keep class com.xunlei.downloadlib.** { *; }

# Zxing QRCode ABI
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.MultiFormatWriter { *; }
-keep class com.google.zxing.Writer { *; }
-keep class com.google.zxing.WriterException { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.qrcode.decoder.ErrorCorrectionLevel { *; }

# Public SDK types used by separately compiled source JARs and language bridges.
-keep class com.github.catvod.Init { *; }
-keep class com.github.catvod.Runtime { *; }
-keep class com.github.catvod.net.Net { *; }
-keep class com.github.catvod.net.Net$* { *; }
-keep class com.github.catvod.net.NetOptions { *; }
-keep class com.github.catvod.utils.Local { *; }
-keep class com.github.catvod.utils.Dialog { *; }
-keep class com.github.catvod.utils.Dialog$* { *; }
-keep class com.github.catvod.utils.Json { *; }
-keep class com.github.catvod.utils.Crypto { *; }
-keep class com.github.catvod.bean.Result { *; }
-keep class com.github.catvod.bean.Vod { *; }
-keep class com.github.catvod.bean.Vod$* { *; }
-keep class com.github.catvod.bean.Class { *; }
-keep class com.github.catvod.bean.Filter { *; }
-keep class com.github.catvod.bean.Filter$* { *; }
-keep class com.github.catvod.bean.Sub { *; }
-keep class com.github.catvod.bean.Danmaku { *; }
-keep class com.github.catvod.utils.Util { *; }
