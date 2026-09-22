package de.robv.android.xposed;

public class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static void hookMethod(
        java.lang.reflect.Member method,
        XC_MethodHook callback) {}
}
