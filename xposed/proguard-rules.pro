-keep class com.github.kyuubiran.ezxhelper.utils.** { *; }
-keep class icu.nullptr.playintegritybreak.xposed.XposedEntry { *; }

# Keep xposed runtime/hook implementation classes. These entrypoints are not
# discovered by static references and can be aggressively optimized away in release.
-keep class icu.nullptr.playintegritybreak.xposed.** { *; }

# Keep common xposed callback signatures used by framework dispatch/reflection.
-keepclassmembers class icu.nullptr.playintegritybreak.xposed.** {
	public void beforeHookedMethod(...);
	public void afterHookedMethod(...);
	public void initZygote(...);
	public void handleLoadPackage(...);
}

# Keep annotation metadata used by some xposed/helper runtimes.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn android.content.res.XModuleResources
-dontwarn android.content.res.XResources
-dontwarn de.robv.android.xposed.IXposedHookLoadPackage
-dontwarn de.robv.android.xposed.IXposedHookZygoteInit$StartupParam
-dontwarn de.robv.android.xposed.IXposedHookZygoteInit
-dontwarn de.robv.android.xposed.XC_MethodHook$MethodHookParam
-dontwarn de.robv.android.xposed.XC_MethodHook$Unhook
-dontwarn de.robv.android.xposed.XC_MethodHook
-dontwarn de.robv.android.xposed.XC_MethodReplacement
-dontwarn de.robv.android.xposed.XposedBridge
-dontwarn de.robv.android.xposed.XposedHelpers
-dontwarn de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
