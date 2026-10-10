package pibexp;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import icu.nullptr.playintegritybreak.core.Backend;
import icu.nullptr.playintegritybreak.core.HookParam;
import icu.nullptr.playintegritybreak.core.MethodHook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Headless verdict probe. The integrity xpe is built from the Dagger graph (activity attached and created, never
 * shown). The official request-and-decode method xpe.G(zhy, continuation) is then called directly, with the zhy event
 * that f(null) creates. Its result is the decoded response; the labels are read from it.
 * Obfuscated names are for Play Store 52.9.21-34 (versionCode 85292140).
 */
public final class Exp {
    private static final String TAG = "PIB-EXP";
    private static final int XPE_KEY = 922;
    private static final String ACTIVITY = "com.google.android.finsky.systemservicesactivity.SystemServicesActivity";

    private Exp() {
    }

    static void log(String msg) {
        Log.i(TAG, msg);
    }

    static void logError(String msg, Throwable t) {
        log("FAILED " + msg + ": " + t);
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        log("root cause: " + root);
        for (String line : Log.getStackTraceString(root).split("\n")) {
            log(line);
        }
    }

    public static void run(final Context app, final ClassLoader cl) {
        try {
            Method rootMethod = cls(cl, "rii").getDeclaredMethod("y");
            rootMethod.setAccessible(true);
            Token.run(app, rootMethod.invoke(app), cl);
        } catch (Throwable t) {
            logError("root", t);
        }
        if (Boolean.getBoolean("pib.activity")) new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    step(app, cl);
                } catch (Throwable t) {
                    logError("experiment", t);
                }
            }
        });
    }

    static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** Logs the fields of o and its superclasses, summarised (arrays and collections by size). */
    static void dumpFields(Object o, String tag) {
        if (o == null) {
            log(tag + ": null");
            return;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object v = field.get(o);
                    String summary;
                    if (v == null) {
                        summary = "null";
                    } else if (v.getClass().isArray()) {
                        summary = "len=" + java.lang.reflect.Array.getLength(v);
                    } else if (v instanceof java.util.Collection) {
                        summary = "size=" + ((java.util.Collection<?>) v).size();
                    } else if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                        summary = String.valueOf(v);
                    } else {
                        summary = v.getClass().getName();
                    }
                    log(tag + " " + c.getName() + "." + field.getName() + ":" + field.getType().getName() + " = " + summary);
                } catch (Throwable t) {
                    log(tag + " " + field.getName() + " unreadable: " + t);
                }
            }
        }
    }

    /** Activity.attach with its parameters filled in by type; returns the manifest theme. */
    static int attachActivity(Activity activity, Context app, String className) throws Throwable {
        Method attach = null;
        for (Method m : Activity.class.getDeclaredMethods()) {
            if (m.getName().equals("attach") && (attach == null || m.getParameterTypes().length > attach.getParameterTypes().length)) {
                attach = m;
            }
        }
        if (attach == null) {
            throw new IllegalStateException("Activity.attach not found");
        }
        attach.setAccessible(true);
        Class<?>[] types = attach.getParameterTypes();
        Object[] args = new Object[types.length];
        ComponentName component = new ComponentName("com.android.vending", className);
        ActivityInfo info;
        try {
            info = app.getPackageManager().getActivityInfo(component, 0);
        } catch (Throwable t) {
            info = new ActivityInfo();
            info.packageName = "com.android.vending";
            info.name = className;
            info.theme = app.getApplicationInfo().theme;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(component);
        Object thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == Context.class || t == android.app.Application.class) {
                args[i] = app;
            } else if (t == Intent.class) {
                args[i] = intent;
            } else if (t == ActivityInfo.class) {
                args[i] = info;
            } else if (t.getName().equals("android.app.ActivityThread")) {
                args[i] = thread;
            } else if (t == IBinder.class) {
                args[i] = new Binder();
            } else if (t == CharSequence.class) {
                args[i] = "SystemServices";
            } else if (t == android.content.res.Configuration.class) {
                args[i] = app.getResources().getConfiguration();
            } else if (t == int.class) {
                args[i] = 0;
            } else if (t == boolean.class) {
                args[i] = false;
            } else if (t == long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        attach.invoke(activity, args);
        return info.theme;
    }

    /** A CoroutineContext that holds nothing: fold returns the initial value, get returns null. */
    static Object emptyContext(final ClassLoader cl) throws ClassNotFoundException {
        return Proxy.newProxyInstance(cl, new Class[]{cls(cl, "cfzn")}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if (name.equals("fold")) {
                    return args[0];
                }
                if (name.equals("plus") || name.equals("minusKey")) {
                    return proxy;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    static void parseResponse(Object result) throws Throwable {
        if (result == null) {
            log("RESULT null");
            return;
        }
        log("RESULT class=" + result.getClass().getName());
        dumpFields(result, "RESULT");
        if (result.getClass().getName().equals("bujd")) {
            Field status = result.getClass().getDeclaredField("b");
            status.setAccessible(true);
            Field body = result.getClass().getDeclaredField("c");
            body.setAccessible(true);
            Object bodyValue = body.get(result);
            if (((Integer) status.get(result)) == 1 && bodyValue != null) {
                Field labels = bodyValue.getClass().getDeclaredField("b");
                labels.setAccessible(true);
                log("LABELS=" + labels.get(bodyValue));
            }
        }
    }

    static void step(Context app, ClassLoader cl) throws Throwable {
        log("start, app=" + app.getClass().getName());

        // Verdict hook on the builder, for comparison with the decoded labels.
        Method cO = null;
        for (Method m : cls(cl, "zdc").getDeclaredMethods()) {
            if (m.getName().equals("cO") && m.getParameterTypes().length == 3) {
                cO = m;
            }
        }
        cO.setAccessible(true);
        Backend.INSTANCE.getCurrent().hook(cO, new MethodHook() {
            @Override
            public void before(HookParam param) {
                log("VERDICT labels=" + param.getArgs()[0] + " testId=" + param.getArgs()[1]);
            }
        });

        // Capture the zhy event that f(null) hands to its event sink (cgnz.e).
        final Object[] captured = new Object[1];
        Method sink = cls(cl, "cgnz").getDeclaredMethod("e", Object.class);
        sink.setAccessible(true);
        Backend.INSTANCE.getCurrent().hook(sink, new MethodHook() {
            @Override
            public void before(HookParam param) {
                captured[0] = param.getArgs()[0];
            }
        });

        // Root component: Lrii.y() on the Application gives Lrnk.
        Method y = cls(cl, "rii").getDeclaredMethod("y");
        y.setAccessible(true);
        Object rnk = y.invoke(app);
        Class<?> rnkC = rnk.getClass();

        // Headless activity: attached, themed and created; never shown.
        Class<?> ssaC = cls(cl, ACTIVITY);
        Activity activity = (Activity) ssaC.getDeclaredConstructor().newInstance();
        int theme = attachActivity(activity, app, ACTIVITY);
        activity.setTheme(theme);
        Method onCreate = cls(cl, "bape").getDeclaredMethod("onCreate", Bundle.class);
        onCreate.setAccessible(true);
        onCreate.invoke(activity, (Object) null);
        Method aN = cls(cl, "bape").getDeclaredMethod("aN");
        aN.setAccessible(true);
        Object cekn = aN.invoke(activity);
        Field eField = cekn.getClass().getDeclaredField("e");
        eField.setAccessible(true);
        Object cela = eField.get(cekn);

        // The activity-scoped components and the key 922 provider (as before).
        Class<?> celaC = cls(cl, "cela");
        Class<?> rmcC = cls(cl, "rmc");
        Constructor<?> rmcCtor = rmcC.getDeclaredConstructor(rnkC, celaC);
        rmcCtor.setAccessible(true);
        Object rmc = rmcCtor.newInstance(rnk, cela);
        Class<?> rmqC = cls(cl, "rmq");
        Constructor<?> rmqCtor = rmqC.getDeclaredConstructor(rnkC, rmcC, Activity.class);
        rmqCtor.setAccessible(true);
        Object rmq = rmqCtor.newInstance(rnk, rmc, activity);
        Class<?> asouC = cls(cl, "asou");
        Constructor<?> asouCtor = asouC.getDeclaredConstructor(Object.class, Object.class);
        asouCtor.setAccessible(true);
        Object asou = asouCtor.newInstance(activity, new ReentrantReadWriteLock());
        Class<?> rmeC = cls(cl, "rme");
        Constructor<?> rmeCtor = rmeC.getDeclaredConstructor(rnkC, rmcC, rmqC, asouC);
        rmeCtor.setAccessible(true);
        Object rme = rmeCtor.newInstance(rnk, rmc, rmq, asou);
        Class<?> rmwC = cls(cl, "rmw");
        Constructor<?> rmwCtor = rmwC.getDeclaredConstructor(rnkC, rmcC, rmqC, rmeC, int.class);
        rmwCtor.setAccessible(true);
        Object rmw = rmwCtor.newInstance(rnk, rmc, rmq, rme, XPE_KEY);
        final Object xpe = cls(cl, "cema").getMethod("a").invoke(rmw);
        log("xpe: " + xpe.getClass().getName());

        // The zhy event: f(null) builds it (nonce and request) and hands it to the sink, which we capture.
        Method f = xpe.getClass().getDeclaredMethod("f", cls(cl, "aobx"));
        f.setAccessible(true);
        f.invoke(xpe, (Object) null);
        final Object zhy = captured[0];
        log("zhy captured: " + (zhy == null ? "null" : zhy.getClass().getName()));
        if (zhy == null) {
            return;
        }

        // The official request-and-decode: xpe.G(zhy, continuation). The continuation receives the decoded response.
        final Class<?> cfzj = cls(cl, "cfzj");
        final ClassLoader loader = cl;
        final Method G = xpe.getClass().getDeclaredMethod("G", cls(cl, "zhy"), cfzj);
        G.setAccessible(true);
        Object continuation = Proxy.newProxyInstance(cl, new Class[]{cfzj}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getParameterTypes().length == 1 && method.getReturnType() == void.class) {
                    log("G resumed");
                    parseResponse(args[0]);
                    return null;
                }
                if (method.getParameterTypes().length == 0 && method.getReturnType() == cls(loader, "cfzn")) {
                    return emptyContext(loader);
                }
                return defaultValue(method.getReturnType());
            }
        });
        Object returned = G.invoke(xpe, zhy, continuation);
        log("G returned: " + (returned == null ? "null" : returned.getClass().getName()));
        if (returned != null && !returned.getClass().getName().contains("Suspend") && !returned.getClass().getName().contains("suspend")) {
            log("G returned a value directly (no suspension)");
        }
    }
}
