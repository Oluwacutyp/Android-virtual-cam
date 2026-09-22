package de.robv.android.xposed;

public class XposedHelpers {

    public static void findAndHookMethod(
        String className, ClassLoader cl,
        String methodName, Object... args) {}

    public static void findAndHookMethod(
        Class<?> clazz, String methodName,
        Object... args) {}

    public static Class<?> findClass(
        String className, ClassLoader cl) {
        try { return cl.loadClass(className); }
        catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static Object getObjectField(
        Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(
        Object obj, String fieldName, Object value) {}

    public static Object getStaticObjectField(
        Class<?> clazz, String fieldName) {
        return null;
    }

    public static void setStaticObjectField(
        Class<?> clazz, String fieldName, Object value) {}

    public static Object callMethod(
        Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(
        Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object newInstance(
        Class<?> clazz, Object... args) {
        return null;
    }
}
