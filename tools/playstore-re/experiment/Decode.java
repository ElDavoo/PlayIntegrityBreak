package pibexp;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;

/**
 * The official decode: Finsky's decodeintegritytoken request (fdfe API), sent through the Play Store's own
 * request machinery (Lqif.a with an Lbuiw body that carries the classic token). Same calls as the dev-options check.
 */
final class Decode {
    private static final String TAG = "PIB-EXP";

    private Decode() {
    }

    static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    /** Breadth-first search of the object graph (fields only, no provider calls) for an instance of target. */
    static Object find(Object root, Class<?> target, int maxDepth) throws IllegalAccessException {
        Deque<Object[]> queue = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        queue.add(new Object[]{root, 0});
        seen.put(root, true);
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Object o = item[0];
            int depth = (Integer) item[1];
            if (target.isInstance(o)) {
                return o;
            }
            if (depth >= maxDepth) {
                continue;
            }
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object v = field.get(o);
                        if (v != null && !seen.containsKey(v) && !(v instanceof String)) {
                            seen.put(v, true);
                            queue.add(new Object[]{v, depth + 1});
                        }
                    } catch (Throwable t) {
                        // unreadable fields are skipped
                    }
                }
            }
        }
        return null;
    }

    static void run(final Object root, final ClassLoader cl, final String token) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int c : new int[]{1, 3, 0}) {
                    Log.i(TAG, "DECODE variant c=" + c);
                    System.setProperty("pib.c", String.valueOf(c));
                    once(root, cl, token);
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "PIB-DECODE").start();
    }

    static void once(Object root, ClassLoader cl, String token) {
        try {
            Class<?> vwrC = cls(cl, "vwr");
            Object vwr = find(root, vwrC, 3);
            Log.i(TAG, "DECODE vwr=" + (vwr == null ? "not found" : vwr.getClass().getName()));
            if (vwr == null) {
                return;
            }
            Method l = vwrC.getDeclaredMethod("l", String.class);
            l.setAccessible(true);
            Object qif = l.invoke(vwr, "decodeintegritytoken");
            Log.i(TAG, "DECODE qif=" + (qif == null ? "null" : qif.getClass().getName()));
            if (qif == null) {
                return;
            }
            // The body the Play Store builds (Llzg.b): device info (buje) wrapped in buiv, the token, and c = 2.
            Class<?> bujeC = cls(cl, "buje");
            Object buje = bujeC.getDeclaredConstructor().newInstance();
            setField(buje, "d", android.os.Build.BRAND);
            setField(buje, "c", android.os.Build.FINGERPRINT);
            setField(buje, "e", android.os.Build.DEVICE);
            setField(buje, "f", android.os.Build.MODEL);
            setField(buje, "g", android.os.Build.PRODUCT);
            setField(buje, "b", 0x1f); // has-bits of the five device fields
            Class<?> buivC = cls(cl, "buiv");
            Object buiv = buivC.getDeclaredConstructor().newInstance();
            setField(buiv, "c", buje);
            setField(buiv, "b", 1);
            Class<?> buiwC = cls(cl, "buiw");
            Object buiw = buiwC.getDeclaredConstructor().newInstance();
            setField(buiw, "b", 1);
            final int variant = Integer.getInteger("pib.c", 2);
            setField(buiw, "c", variant);
            setField(buiw, "d", buiv);
            setField(buiw, "e", token);
            Class<?> oxgC = cls(cl, "oxg");
            Class<?> oxfC = cls(cl, "oxf");
            Log.i(TAG, "DECODE listeners oxg.interface=" + oxgC.isInterface() + " oxf.interface=" + oxfC.isInterface());
            InvocationHandler listener = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    StringBuilder sb = new StringBuilder("DECODE listener " + method.getName() + "(");
                    if (args != null) {
                        for (Object arg : args) {
                            sb.append(arg == null ? "null" : arg.getClass().getName()).append(',');
                        }
                    }
                    Log.i(TAG, sb.append(")").toString());
                    if (args != null) {
                        for (Object arg : args) {
                            dump(arg);
                        }
                    }
                    return null;
                }
            };
            Object listenerG = Proxy.newProxyInstance(cl, new Class[]{oxgC}, listener);
            Object listenerF = Proxy.newProxyInstance(cl, new Class[]{oxfC}, listener);
            Method a = cls(cl, "qif").getMethod("a", buiwC, oxgC, oxfC);
            a.invoke(qif, buiw, listenerG, listenerF);
            Log.i(TAG, "DECODE request sent");
        } catch (Throwable t) {
            Throwable root2 = t;
            while (root2.getCause() != null) {
                root2 = root2.getCause();
            }
            Log.i(TAG, "DECODE failed: " + root2);
            for (String line : android.util.Log.getStackTraceString(root2).split("\n")) {
                Log.i(TAG, "DECODE " + line);
            }
        }
    }

    static void setField(Object target, String name, Object value) throws Throwable {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (value instanceof Integer && f.getType() == int.class) {
            f.setInt(target, (Integer) value);
        } else {
            f.set(target, value);
        }
    }

    static void dump(Object o) {
        if (o == null) {
            return;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object v = field.get(o);
                    String s = v == null ? "null" : (v instanceof Number || v instanceof String || v instanceof Boolean ? String.valueOf(v) : v.getClass().getName());
                    if (s.length() > 300) {
                        s = s.substring(0, 300) + "...";
                    }
                    Log.i(TAG, "DECODE   " + c.getName() + "." + field.getName() + " = " + s);
                } catch (Throwable t) {
                    // skipped
                }
            }
        }
    }
}
