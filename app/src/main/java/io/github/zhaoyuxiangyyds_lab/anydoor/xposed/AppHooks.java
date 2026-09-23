package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.hardware.Sensor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseIntArray;

import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * In-process hooks for apps that are explicitly added to the module scope ("strong mode").
 * The system_server hooks already cover every app; these add a second layer for apps that
 * cache/verify Location objects, check isFromMockProvider, or read WiFi/cell data directly.
 */
final class AppHooks {
    private AppHooks() {}

    private static final WeakHashMap<Object, Runnable> PUMPS = new WeakHashMap<>();

    static void install(final XC_LoadPackage.LoadPackageParam lp, final SpoofState st) {
        final String pkg = lp.packageName;
        if (Keys.PKG.equals(pkg)) return;

        // ---- android.location.Location getters ----
        Class<?> loc = Location.class;
        hookGetter(loc, "getLatitude", st, pkg, 0);
        hookGetter(loc, "getLongitude", st, pkg, 1);
        hookGetter(loc, "getAltitude", st, pkg, 2);
        hookGetter(loc, "getAccuracy", st, pkg, 3);
        hookGetter(loc, "getSpeed", st, pkg, 4);
        hookGetter(loc, "getBearing", st, pkg, 5);
        XC_MethodHook notMock = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (st.started()) p.setResult(false);
            }
        };
        HookUtil.hookAll(loc, "isFromMockProvider", notMock);
        if (android.os.Build.VERSION.SDK_INT >= 31) HookUtil.hookAll(loc, "isMock", notMock);
        // Do NOT hook hasAltitude()/hasSpeed()/hasBearing()/hasAccuracy(): on Android 12+ both
        // Location.writeToParcel and Location.CREATOR consult them to decide which fields are in
        // the parcel. Forcing a different answer in only one of the two sides (the other may run
        // in another process, or have the tiny getter inlined by AOT) shifts every byte that
        // follows – Amap's AMapLocation then reads garbage lat/lng and fails with
        // "LatLng is error#0802".

        // ---- LocationManager ----
        Class<?> lm = LocationManager.class;
        HookUtil.hookAll(lm, "getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && (Keys.PROBE_PROVIDER.equals(p.args[0]) || Keys.STATE_PROVIDER.equals(p.args[0])
                        || (p.args[0] instanceof String && ((String) p.args[0]).startsWith(Keys.PUMP_PROVIDER)))) return;
                if (!st.started() || st.isExempt(pkg) || !st.bool(Keys.APP_HOOK, true)) return;
                if (p.hasThrowable()) return;
                String provider = p.args.length > 0 && p.args[0] instanceof String ? (String) p.args[0] : "gps";
                Object res = p.getResult();
                p.setResult(st.build(provider, res instanceof Location ? (Location) res : null));
            }
        });
        XC_MethodHook pump = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || st.isExempt(pkg) || !st.bool(Keys.APP_HOOK, true)) return;
                LocationListener listener = null;
                Looper looper = null;
                String provider = null;
                for (Object a : p.args) {
                    if (a instanceof LocationListener) listener = (LocationListener) a;
                    else if (a instanceof Looper) looper = (Looper) a;
                    else if (a instanceof String && provider == null) provider = (String) a;
                }
                if (listener == null) return;
                if (!p.hasThrowable()) startPump(st, listener, looper, provider == null ? "gps" : provider, pkg, "requestSingleUpdate".equals(p.method.getName()));
            }
        };
        HookUtil.hookAll(lm, "requestLocationUpdates", pump);
        // Single-update requests use the platform delivery; a second timer would deliver twice.
        HookUtil.hookAll(lm, "removeUpdates", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length > 0 && p.args[0] instanceof LocationListener) stopPump(p.args[0]);
            }
        });
        HookUtil.hookAll(lm, "isProviderEnabled", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || st.isExempt(pkg) || !st.bool(Keys.APP_HOOK, true)) return;
                if (p.args.length > 0 && ("gps".equals(p.args[0]) || "network".equals(p.args[0]))) p.setResult(true);
            }
        });
        HookUtil.hookAll(lm, "addNmeaListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (st.started() && st.bool(Keys.GNSS_BLOCK, true)) p.setResult(false);
            }
        });

        // ---- WiFi / cell (in-process) ----
        XC_MethodHook emptyList = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (st.started() && st.bool(Keys.WIFI_BLOCK, true)) p.setResult(new ArrayList<>());
            }
        };
        HookUtil.hookAll(android.net.wifi.WifiManager.class, "getScanResults", emptyList);
        HookUtil.hookAll(android.net.wifi.WifiInfo.class, "getBSSID", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (st.started() && st.bool(Keys.WIFI_BLOCK, true) && p.getResult() != null) p.setResult("02:00:00:00:00:00");
            }
        });
        XC_MethodHook cell = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.CELL_BLOCK, true)) return;
                Class<?> rt = ((Method) p.method).getReturnType();
                if (List.class.isAssignableFrom(rt)) p.setResult(new ArrayList<>());
                else p.setResult(null);
            }
        };
        Class<?> tm = android.telephony.TelephonyManager.class;
        HookUtil.hookAll(tm, "getAllCellInfo", cell);
        HookUtil.hookAll(tm, "getCellLocation", cell);
        HookUtil.hookAll(tm, "getNeighboringCellInfo", cell);
        HookUtil.hookAll(tm, "listen", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.CELL_BLOCK, true)) return;
                for (int i = 0; i < p.args.length; i++) {
                    if (p.args[i] instanceof Integer) p.args[i] = ((Integer) p.args[i]) & ~(0x10 | 0x400);
                }
            }
        });
        // Android 10+: requestCellInfoUpdate([WorkSource,] Executor, CellInfoCallback) is the modern
        // getAllCellInfo; answer it with an empty list on the caller's executor.
        HookUtil.hookAll(tm, "requestCellInfoUpdate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.CELL_BLOCK, true)) return;
                java.util.concurrent.Executor ex = null;
                Object cb = null;
                for (Object a : p.args) {
                    if (a instanceof java.util.concurrent.Executor) ex = (java.util.concurrent.Executor) a;
                    else if (a != null && !(a instanceof android.os.WorkSource)) cb = a;
                }
                HookUtil.deliverEmptyCells(cb, ex);
                p.setResult(null);
            }
        });
        // ServiceState carries the serving cell identity since Android 10
        HookUtil.hookAll(tm, "getServiceState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.CELL_BLOCK, true) || p.hasThrowable() || p.getResult() == null) return;
                p.setResult(HookUtil.sanitizeServiceState(p.getResult()));
            }
        });
        // connected WiFi inside NetworkCapabilities (Android 12+ replacement for getConnectionInfo).
        // Only the direct getter is rewritten in-process; the callback path (onCapabilitiesChanged)
        // is masked system-side in ConnectivityService, which avoids touching a live callback Message
        // in the app process (a source of hard-to-trace crashes in scoped apps).
        Class<?> cm = android.net.ConnectivityManager.class;
        HookUtil.hookAll(cm, "getNetworkCapabilities", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || !st.bool(Keys.WIFI_BLOCK, true) || p.hasThrowable()) return;
                Object masked = HookUtil.maskWifiTransport(p.getResult(), st.privacy());
                if (masked != null) p.setResult(masked);
            }
        });
        installGmsHooks(st, pkg, lp.classLoader);
        installIdentityHooks(st);
        installSensorHooks(st);
        HookEntry.log("app hooks installed in " + pkg);
    }

    // ------------------------------------------------------------------ Google Play services

    /**
     * Google Play services keeps its own fused cache and hands fixes to clients through
     * com.google.android.gms.location.LocationResult rather than the platform's LocationResult, so
     * the system-side onReportLocation rewrite never sees those copies. That is what made Maps and
     * Earth show the virtual position for a moment and then snap back: GMS re-stamped the fix
     * (mock flag plus the real extras) after the system had already rewritten it.
     *
     * <p>The contained Location objects are still plain android.location.Location, so the getter
     * hooks above already apply to them; rewriting the container as well removes the mock flag and
     * the real-provider extras at the point GMS builds them, before any client reads the result.
     *
     * <p>Only classes GMS actually bundles are touched, so this is a no-op in every other app:
     * findClassIfExists returns null when the process has no Play services on its class path.
     */
    private static void installGmsHooks(final SpoofState st, final String pkg, ClassLoader cl) {
        Class<?> result = XposedHelpers.findClassIfExists(
                "com.google.android.gms.location.LocationResult", cl);
        if (result == null) return;

        HookUtil.hookAll(result, "getLastLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || st.isExempt(pkg) || !st.bool(Keys.APP_HOOK, true)) return;
                if (p.hasThrowable() || !(p.getResult() instanceof Location)) return;
                Location orig = (Location) p.getResult();
                if (!isSystemFix(orig)) return;
                p.setResult(st.build(orig.getProvider(), orig));
            }
        });
        HookUtil.hookAll(result, "getLocations", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!st.started() || st.isExempt(pkg) || !st.bool(Keys.APP_HOOK, true)) return;
                if (p.hasThrowable() || !(p.getResult() instanceof List)) return;
                List<?> in = (List<?>) p.getResult();
                if (in.isEmpty() || !isSystemFix(in.get(0))) return;
                List<Location> out = new ArrayList<>(in.size());
                for (Object o : in) {
                    Location l = (Location) o;
                    out.add(st.build(l.getProvider(), l));
                }
                p.setResult(out);
            }
        });
    }

    // ------------------------------------------------------------------ privacy: identifiers

    /** Client-side identity spoof for scoped apps (second layer over the system_server / phone hooks). */
    private static void installIdentityHooks(final SpoofState st) {
        XC_MethodHook fakeId = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null || p.getResult() == null || !st.idSpoof()) return;
                String fake = st.fakeIdFor(((Method) p.method).getName());
                if (fake != null) p.setResult(fake);
            }
        };
        HookUtil.hookByPrefix(android.telephony.TelephonyManager.class,
                new String[]{"getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber", "getLine1Number"}, fakeId);
        if (Build.VERSION.SDK_INT >= 26) HookUtil.hookByPrefix(Build.class, new String[]{"getSerial"}, fakeId);
        XC_MethodHook androidId = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!st.idSpoof()) return;
                for (Object a : p.args) {
                    if ("android_id".equals(a)) {
                        String fake = st.str(Keys.FAKE_ANDROID_ID, null);
                        if (fake != null) p.setResult(fake);
                        return;
                    }
                }
            }
        };
        Class<?> secure = android.provider.Settings.Secure.class;
        HookUtil.hookAll(secure, "getString", androidId);
        HookUtil.hookAll(secure, "getStringForUser", androidId);
        if (st.idSpoof()) {
            try {
                String fake = st.str(Keys.FAKE_SERIAL, null);
                if (fake != null) XposedHelpers.setStaticObjectField(Build.class, "SERIAL", fake);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ sensors: pedometer & privacy

    private static final int TYPE_PRESSURE = 6, TYPE_STEP_DETECTOR = 18, TYPE_STEP_COUNTER = 19;
    private static final SparseIntArray HANDLE_TYPE = new SparseIntArray();
    /** event queue → sensor handles registered on it (only step sensors are tracked) */
    private static final WeakHashMap<Object, Set<Integer>> STEP_QUEUES = new WeakHashMap<>();
    private static final WeakHashMap<Object, double[]> LAST_DELIVERED = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> INJECTING = new ThreadLocal<>();
    private static Handler stepHandler;

    /**
     * Every sensor event reaches Java through SystemSensorManager$SensorEventQueue.dispatchSensorEvent
     * (handle, values, accuracy, timestamp). We rewrite step counters to our fake total, drop the real
     * step detector and barometer, and feed synthetic step events from a 1 s timer so a phone lying on
     * the desk still "walks" for pedometer apps.
     */
    private static void installSensorHooks(final SpoofState st) {
        Class<?> base = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager$BaseEventQueue", null);
        Class<?> queue = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager$SensorEventQueue", null);
        if (base == null || queue == null) {
            HookEntry.log("sensor queues not found");
            return;
        }
        HookUtil.hookAll(base, "addSensor", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof Sensor)) return;
                Sensor sn = (Sensor) p.args[0];
                int handle = handleOf(sn);
                synchronized (AppHooks.class) {
                    HANDLE_TYPE.put(handle, sn.getType());
                    if (sn.getType() == TYPE_STEP_COUNTER || sn.getType() == TYPE_STEP_DETECTOR) {
                        Set<Integer> hs = STEP_QUEUES.get(p.thisObject);
                        if (hs == null) STEP_QUEUES.put(p.thisObject, hs = new HashSet<>());
                        hs.add(handle);
                        ensureStepTimer(st);
                    }
                }
            }
        });
        HookUtil.hookAll(base, "removeSensor", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof Sensor)) return;
                synchronized (AppHooks.class) {
                    Set<Integer> hs = STEP_QUEUES.get(p.thisObject);
                    if (hs != null) hs.remove(handleOf((Sensor) p.args[0]));
                }
            }
        });
        XC_MethodHook forget = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                synchronized (AppHooks.class) {
                    STEP_QUEUES.remove(p.thisObject);
                    LAST_DELIVERED.remove(p.thisObject);
                }
            }
        };
        HookUtil.hookAll(base, "removeAllSensors", forget);
        HookUtil.hookAll(base, "dispose", forget);
        HookUtil.hookAll(queue, "dispatchSensorEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !(p.args[0] instanceof Integer) || !(p.args[1] instanceof float[])) return;
                int type;
                synchronized (AppHooks.class) {
                    type = HANDLE_TYPE.get((Integer) p.args[0], -1);
                }
                boolean injected = Boolean.TRUE.equals(INJECTING.get());
                if (type == TYPE_STEP_COUNTER && st.stepFake()) {
                    ((float[]) p.args[1])[0] = (float) st.num(Keys.STEPS, 0);
                } else if (type == TYPE_STEP_DETECTOR && st.stepFake() && !injected) {
                    p.setResult(null);
                } else if (type == TYPE_PRESSURE && st.privacy() && st.bool(Keys.SENSOR_BLOCK, true)) {
                    p.setResult(null);
                }
            }
        });
    }

    private static int handleOf(Sensor s) {
        try {
            return (Integer) XposedHelpers.callMethod(s, "getHandle");
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Main-looper timer that pushes synthetic step events to every registered step listener. */
    private static synchronized void ensureStepTimer(final SpoofState st) {
        if (stepHandler != null) return;
        stepHandler = new Handler(Looper.getMainLooper());
        stepHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (st.stepFake()) pumpSteps(st);
                } catch (Throwable t) {
                    HookEntry.log("step pump: " + t);
                }
                stepHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private static void pumpSteps(SpoofState st) {
        double steps = Math.floor(st.num(Keys.STEPS, 0));
        List<Object[]> work = new ArrayList<>();
        synchronized (AppHooks.class) {
            for (Map.Entry<Object, Set<Integer>> e : STEP_QUEUES.entrySet()) {
                for (int h : e.getValue()) work.add(new Object[]{e.getKey(), h, HANDLE_TYPE.get(h, -1)});
            }
        }
        long ts = SystemClock.elapsedRealtimeNanos();
        for (Object[] w : work) {
            Object q = w[0];
            int handle = (Integer) w[1], type = (Integer) w[2];
            double[] last;
            synchronized (AppHooks.class) {
                last = LAST_DELIVERED.get(q);
                if (last == null) LAST_DELIVERED.put(q, last = new double[]{-1, steps});
            }
            if (type == TYPE_STEP_COUNTER) {
                if (last[0] == steps) continue;
                last[0] = steps;
                float[] v = new float[16];
                v[0] = (float) steps;
                inject(q, handle, v, ts);
            } else if (type == TYPE_STEP_DETECTOR) {
                int n = (int) Math.min(6, steps - last[1]);
                last[1] = steps;
                float[] v = new float[16];
                v[0] = 1f;
                for (int i = 0; i < n; i++) inject(q, handle, v, ts - (n - 1 - i) * 150_000_000L);
            }
        }
    }

    private static void inject(Object queue, int handle, float[] values, long ts) {
        INJECTING.set(true);
        try {
            XposedHelpers.callMethod(queue, "dispatchSensorEvent", handle, values, 3, ts);
        } catch (Throwable t) {
            HookEntry.log("inject step: " + t);
        } finally {
            INJECTING.set(false);
        }
    }

    /** Only fixes that came from the platform are rewritten; apps build their own Location objects for maths. */
    private static boolean isSystemFix(Object o) {
        if (!(o instanceof Location)) return false;
        String p = ((Location) o).getProvider();
        return "gps".equals(p) || "network".equals(p) || "fused".equals(p) || "passive".equals(p);
    }

    private static void hookGetter(Class<?> cls, String name, final SpoofState st, final String pkg, final int what) {
        HookUtil.hookAll(cls, name, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!st.started() || st.isExempt(pkg) || !st.bool(Keys.APP_HOOK, true) || !isSystemFix(p.thisObject)) return;
                SpoofState.Fix f = st.current();
                switch (what) {
                    case 0: p.setResult(f.lat); break;
                    case 1: p.setResult(f.lng); break;
                    case 2: p.setResult(f.alt); break;
                    case 3: p.setResult(f.acc); break;
                    case 4: p.setResult(f.speed); break;
                    default: p.setResult(f.bearing); break;
                }
            }
        });
    }

    /** Periodically feed a listener with fresh fixes so apps get updates even without a real GPS fix. */
    private static synchronized void startPump(final SpoofState st, final LocationListener l, Looper looper, final String provider, final String pkg, final boolean single) {
        stopPump(l);
        final Handler h = new Handler(looper != null ? looper : Looper.getMainLooper());
        Runnable r = new Runnable() {
            @Override
            public void run() {
                synchronized (AppHooks.class) {
                    if (PUMPS.get(l) != this) return;
                }
                if (st.started() && !st.isExempt(pkg) && st.bool(Keys.APP_HOOK, true)) {
                    try {
                        l.onLocationChanged(st.build(provider, null));
                        if (single) { stopPump(l); return; }
                    } catch (Throwable t) {
                        HookEntry.log("pump: " + t);
                    }
                }
                h.postDelayed(this, st.interval());
            }
        };
        PUMPS.put(l, r);
        h.postDelayed(r, 300);
    }

    private static synchronized void stopPump(Object l) {
        PUMPS.remove(l);
    }
}
