package de.robv.android.xposed;

public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam)
        throws Throwable;

    class StartupParam {
        public String modulePath;
    }
}
