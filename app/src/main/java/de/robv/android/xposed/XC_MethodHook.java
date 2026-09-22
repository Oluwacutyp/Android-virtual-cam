package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public void beforeHookedMethod(
        MethodHookParam param) throws Throwable {}

    public void afterHookedMethod(
        MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() { return result; }

        public void setResult(Object result) {
            this.result = result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }
    }
}
