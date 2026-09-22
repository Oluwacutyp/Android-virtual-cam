package de.robv.android.xposed;

public abstract class XC_MethodReplacement
    extends XC_MethodHook {

    protected abstract Object replaceHookedMethod(
        MethodHookParam param) throws Throwable;

    @Override
    public final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            Object result = replaceHookedMethod(param);
            param.setResult(result);
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    public final void afterHookedMethod(MethodHookParam param) throws Throwable {
        // No-op for replacement
    }

    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return result;
            }
        };
    }
}
