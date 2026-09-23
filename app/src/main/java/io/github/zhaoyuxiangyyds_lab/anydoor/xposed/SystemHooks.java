package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import io.github.zhaoyuxiangyyds_lab.anydoor.Keys;
import io.github.zhaoyuxiangyyds_lab.anydoor.ConfigSnapshot;
import io.github.zhaoyuxiangyyds_lab.anydoor.BuildInfo;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks inside system_server: every app on the device receives the spoofed location, and
 * (optionally) WiFi scan results / cell registrations are hidden from non-system apps so that
 * network-positioning SDKs (Amap/Tencent/Baidu) cannot derive the real position.
 */
final class SystemHooks {
    private SystemHooks() {}

    private static final int LISTEN_CELL_LOCATION = 0x10;
    private static final int LISTEN_CELL_INFO = 0x400;
    private static final int EVENT_CELL_LOCATION_CHANGED = 5;
    private static final int EVENT_CELL_INFO_CHANGED = 11;

    /** Hook counts, reported back to the app through the probe provider (see LastLocationHook). */
    private static volatile int lastHooks, deliverHooks, reportHooks, acceptHooks, mockGrantHooks, cellGateHooks;
    private static final java.util.concurrent.atomic.AtomicLong deliveries = new java.util.concurrent.atomic.AtomicLong();
    private static volatile long lastDelivery;
    private static volatile String wifiState = "none", connState = "none";
    private static volatile int mockOp = -2;

    /**
     * Real LocationResult each rewritten report replaced, keyed by the spoofed result handed to the
     * registrations. onReportLocation is not per-package, so an exempt app cannot be skipped there;
     * instead the real result is kept here and handed back to exempt registrations in AcceptHook.
     * Weak keys so the entries die with the (short-lived) LocationResult objects.
     */
    private static final java.util.Map<Object, Object> REAL_RESULTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Object>());

    static void install(XC_LoadPackage.LoadPackageParam lp, final SpoofState st) {
        ClassLoader cl = lp.classLoader;

        // ---------- mock-location app-op grant ----------
        // Some ROMs (notably ColorOS/OxygenOS/realme on Android 12+) keep OP_MOCK_LOCATION at
        // MODE_ERRORED even after `appops set … android:mock_location allow` reports "allow", so
        // addTestProvider/setTestProviderLocation throw SecurityException and our test providers
        // never start – then apps that stream updates (WeChat mini-programs, Amap) get no fix and
        // fall back to network positioning, which our WiFi/cell block empties. Grant the op for our
        // own package inside the framework so the test providers register on every ROM.
        installMockLocationGrant(cl);

        // ---------- LocationManagerService ----------
        Class<?> lms = XposedHelpers.findClassIfExists("com.android.server.LocationManagerService", cl);
        if (lms == null) lms = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", cl);
        if (lms == null) {
            HookEntry.log("LocationManagerService not found!");
        } else {
            List<Class<?>> targets = new ArrayList<>();
            targets.add(lms);
            for (String n : new String[]{
                    "com.android.server.HwLocationManagerService",
                    "com.android.server.location.HwLocationManagerService"}) {
                Class<?> c = XposedHelpers.findClassIfExists(n, cl);
                if (c != null && !targets.contains(c)) targets.add(c);
            }
            int n = 0;
            for (Class<?> c : targets) n += HookUtil.hookAll(c, "getLastLocation", new LastLocationHook(st));
            lastHooks = n;
            HookEntry.log("getLastLocation hooks: " + n + " on " + targets.size() + " classes");

            // Android 8.1 - 11: per-receiver delivery
            Class<?> recv = XposedHelpers.findClassIfExists(lms.getName() + "$Receiver", cl);
            if (recv != null) {
                deliverHooks = HookUtil.hookAll(recv, "callLocationChangedLocked", new DeliverHook(st));
                HookEntry.log("Receiver.callLocationChangedLocked hooks: " + deliverHooks);
            }
            // Android 12+: rewrite per registration, after the system cache is updated.
            Class<?> lpm = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", cl);
            if (lpm != null) {
                // onReportLocation is the single, stable choke point every provider report passes
                // through before it fans out to registrations and the last-location cache. We always
                // replace the incoming LocationResult here: it is far more reliable than the
                // per-registration classes (whose names differ across OEM builds), it also strips the
                // mock-provider flag that the test provider stamps on its fixes (so a streaming client
                // that drops flagged fixes no longer snaps back to the real position), and it covers
                // the real provider overriding a spoofed fix a second later. This hook is not
                // per-package, so an exempt app cannot be skipped here; instead the real result is
                // remembered in REAL_RESULTS and restored for exempt registrations in AcceptHook.
                reportHooks = HookUtil.hookAll(lpm, "onReportLocation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (Pump.injecting() || !st.started() || p.args.length == 0 || p.args[0] == null) return;
                        Object replaced = spoofResult(st, p.args[0], p.thisObject);
                        if (replaced == null) return;
                        REAL_RESULTS.put(replaced, p.args[0]);
                        p.args[0] = replaced;
                        deliveries.incrementAndGet();
                        lastDelivery = System.currentTimeMillis();
                        if (st.debug()) HookEntry.log("report → spoof (" + managerName(p.thisObject) + ")");
                    }
                });
                HookEntry.log("LocationProviderManager.onReportLocation hooks: " + reportHooks);
                // The per-registration delivery point also covers the
                // "deliver cached last location on register" fast path, the exempt-app case, and OEM
                // builds where the report path is bypassed, and it honours the exempt list per package.
                acceptHooks = installAcceptHooks(lpm, st);
                HookEntry.log("Registration.acceptLocationChange hooks: " + acceptHooks);
            }
            // raw GNSS data (measurements, navigation messages, NMEA sentences) would reveal the
            // real position
            XC_MethodHook deny = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!st.started() || !st.bool(Keys.GNSS_BLOCK, true)) return;
                    int uid = Binder.getCallingUid();
                    if (HookUtil.isSystemUid(uid)) return;
                    if (st.isExempt(HookUtil.callerPackage(p.thisObject, p.args, uid))) return;
                    Class<?> rt = ((java.lang.reflect.Method) p.method).getReturnType();
                    if (rt == boolean.class || rt == Boolean.class) p.setResult(false);
                    else if (rt == void.class) p.setResult(null);
                }
            };
            for (String m : new String[]{"addGnssMeasurementsListener", "addGnssNavigationMessageListener",
                    "addGnssBatchingCallback", "startGnssBatch", "addGnssAntennaInfoListener",
                    "addNmeaListener", "registerGnssNmeaCallback"}) {
                HookUtil.hookAll(lms, m, deny);
            }
            // fallback driver for the cases where no test provider may run (exempt apps, mock op refused)
            Pump.install(lpm, lms, st);
        }

        // ---------- WiFi / connectivity ----------
        // Up to Android 10 WifiServiceImpl lives in services.jar; since Android 11 it is in the
        // com.android.wifi APEX, loaded by SystemServiceManager.startServiceFromJar() through a
        // separate PathClassLoader that lp.classLoader cannot see. The same happened to
        // ConnectivityService with Android 12 (com.android.tethering APEX). Catch both classes
        // when their service is started.
        Class<?> wifi = XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", cl);
        if (wifi != null) installWifiHooks(wifi, st);
        else wifiState = "waiting";
        // ConnectivityService lives in the com.android.tethering APEX and, since Android 14, is
        // jarjar-repackaged to android.net.connectivity.com.android.server.ConnectivityService, so a
        // name lookup misses it. Grab the real instance from the initializer that SystemServiceManager
        // starts (name-agnostic) instead of guessing the class name.
        Class<?> conn = XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", cl);
        if (conn == null) conn = XposedHelpers.findClassIfExists("android.net.connectivity.com.android.server.ConnectivityService", cl);
        if (conn != null) installConnectivityHooks(conn, st);
        else connState = "waiting";
        if (wifi == null || conn == null) {
            Class<?> ssm = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", cl);
            int k = ssm == null ? 0 : HookUtil.hookAll(ssm, "startService", new XC_MethodHook() {
                private boolean wifiDone, connDone;

                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (wifiDone || p.args.length == 0 || p.args[0] == null) return;
                    Class<?> c = p.args[0] instanceof Class ? (Class<?>) p.args[0] : p.args[0].getClass();
                    if (!"com.android.server.wifi.WifiService".equals(c.getName())) return;
                    wifiDone = true;
                    Class<?> impl = XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", c.getClassLoader());
                    if (impl == null) {
                        wifiState = "impl-missing";
                        HookEntry.log("WifiService loaded but WifiServiceImpl not found in " + c.getClassLoader());
                        return;
                    }
                    installWifiHooks(impl, st);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    // startService(Class) / startService(SystemService) both yield the started service
                    if (connDone) return;
                    Object svc = p.getResult();
                    if (svc == null || !svc.getClass().getName().contains("ConnectivityServiceInitializer")) {
                        svc = null;
                        for (Object a : p.args) {
                            if (a != null && a.getClass().getName().contains("ConnectivityServiceInitializer")) { svc = a; break; }
                        }
                        if (svc == null) return;
                    }
                    connDone = true;
                    Object cs = fieldOfType(svc, "ConnectivityService");
                    if (cs == null) {
                        connState = "no-instance";
                        HookEntry.log("ConnectivityServiceInitializer started but no ConnectivityService field found");
                        return;
                    }
                    installConnectivityHooks(cs.getClass(), st);
                }
            });
            if (k == 0) {
                if (wifi == null) wifiState = "no-loader";
                if (conn == null) connState = "no-loader";
                HookEntry.log("WifiServiceImpl/ConnectivityService not in system_server and SystemServiceManager.startService not hookable");
            }
        }

        // ---------- TelephonyRegistry: strip cell events from PhoneStateListener registrations ----------
        Class<?> tr = XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", cl);
        if (tr != null) {
            XC_MethodHook listen = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!shouldBlock(st, p.thisObject, p.args, Keys.CELL_BLOCK)) return;
                    int idx = p.args.length - 2;
                    if (idx < 0) return;
                    Object ev = p.args[idx];
                    if (ev instanceof Integer) {
                        p.args[idx] = ((Integer) ev) & ~(LISTEN_CELL_LOCATION | LISTEN_CELL_INFO);
                    } else if (ev instanceof int[]) {
                        int[] in = (int[]) ev;
                        List<Integer> out = new ArrayList<>();
                        for (int e : in) if (e != EVENT_CELL_LOCATION_CHANGED && e != EVENT_CELL_INFO_CHANGED) out.add(e);
                        int[] arr = new int[out.size()];
                        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
                        p.args[idx] = arr;
                    }
                }
            };
            for (String m : new String[]{"listen", "listenForSubscriber", "listenWithEventList"}) HookUtil.hookAll(tr, m, listen);
            // Android 10+: every cell-location / cell-info / service-state delivery first asks
            // checkFineLocationAccess(Record, minSdk) / checkCoarseLocationAccess(Record, minSdk).
            // Answering "no" for a blocked registration makes the registry skip the cell events
            // it still has and hand out location-sanitized ServiceState copies (no cell identity),
            // exactly as it does for apps without location permission.
            XC_MethodHook noAccess = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 0 || p.args[0] == null) return;
                    if (registrationBlocked(st, p.args[0])) p.setResult(false);
                }
            };
            cellGateHooks = HookUtil.hookAll(tr, "checkFineLocationAccess", noAccess)
                    + HookUtil.hookAll(tr, "checkCoarseLocationAccess", noAccess);
            HookEntry.log("TelephonyRegistry location gate hooks: " + cellGateHooks);
        }
        installIdentityHooks(cl, st);
        HookEntry.log("system hooks installed (sdk " + android.os.Build.VERSION.SDK_INT + ")");
    }

    /** TelephonyRegistry.Record → should its cell data be withheld (cell block on, normal non-exempt app). */
    private static boolean registrationBlocked(SpoofState st, Object record) {
        String pkg = null;
        int uid = -1;
        try {
            Object o = XposedHelpers.getObjectField(record, "callingPackage");
            if (o instanceof String) pkg = (String) o;
        } catch (Throwable ignored) {
        }
        try {
            uid = XposedHelpers.getIntField(record, "callerUid");
        } catch (Throwable ignored) {
        }
        return shouldBlockPackage(st, uid, pkg, Keys.CELL_BLOCK);
    }

    /**
     * ConnectivityService: the connected WiFi's BSSID travels to apps inside
     * NetworkCapabilities.getTransportInfo() (getNetworkCapabilities and every NetworkCallback);
     * both paths pass through the location-sanitizing copy method, so mask it there.
     */
    private static void installConnectivityHooks(Class<?> conn, final SpoofState st) {
        XC_MethodHook mask = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null || p.getResult() == null) return;
                String pkg = null;
                List<Integer> ints = new ArrayList<>();
                for (Object a : p.args) {
                    if (a instanceof String && pkg == null) pkg = (String) a;
                    else if (a instanceof Integer) ints.add((Integer) a);
                }
                // (nc, includeLocationSensitiveInfo, callerPid, callerUid, callerPkgName, tag) on 12+,
                // (nc, callerUid, callerPkgName) on 11
                int uid = ints.size() >= 2 ? ints.get(1) : ints.size() == 1 ? ints.get(0) : -1;
                if (!shouldBlockPackage(st, uid, pkg, Keys.WIFI_BLOCK)) return;
                Object masked = HookUtil.maskWifiTransport(p.getResult(), st.privacy());
                if (masked != null) p.setResult(masked);
            }
        };
        int n = 0;
        for (String m : new String[]{"createWithLocationInfoSanitizedIfNecessaryWhenParceled", "maybeSanitizeLocationInfoForCaller"}) {
            n += HookUtil.hookAll(conn, m, mask);
        }
        if (n == 0) {
            // OEM / newer builds may rename the sanitizer; match by shape: a method that both takes and
            // returns a NetworkCapabilities and has "sanitiz" in its name.
            try {
                for (java.lang.reflect.Method m : conn.getDeclaredMethods()) {
                    if (!m.getName().toLowerCase(java.util.Locale.US).contains("sanitiz")) continue;
                    if (!android.net.NetworkCapabilities.class.isAssignableFrom(m.getReturnType())) continue;
                    boolean takesNc = false;
                    for (Class<?> pt : m.getParameterTypes()) if (android.net.NetworkCapabilities.class.isAssignableFrom(pt)) takesNc = true;
                    if (!takesNc) continue;
                    try {
                        de.robv.android.xposed.XposedBridge.hookMethod(m, mask);
                        n++;
                    } catch (Throwable t) {
                        HookEntry.log("hook " + m.getName() + " failed: " + t);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        connState = n > 0 ? "ok" : "no-methods";
        HookEntry.log("connectivity hooks installed: " + n + " via " + conn.getClassLoader());
    }

    /** First declared-field value of {@code owner} whose class simple name equals {@code simpleName}. */
    private static Object fieldOfType(Object owner, String simpleName) {
        try {
            for (java.lang.reflect.Field f : owner.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try {
                    v = f.get(owner);
                } catch (Throwable t) {
                    continue;
                }
                if (v != null && simpleName.equals(v.getClass().getSimpleName())) return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Android 12+: LocationManagerService.addTestProvider / setTestProviderEnabled /
     * setTestProviderLocation / removeTestProvider gate on
     * {@code getAppOpsHelper().noteOp(OP_MOCK_LOCATION, caller)}, and noteOp throws a
     * SecurityException when the op resolves to MODE_ERRORED. Force the location injector's app-op
     * helper to report "allowed" for OP_MOCK_LOCATION when the caller is our own package, so the
     * calls succeed even on ROMs that keep the op errored despite the stored appops mode.
     */
    private static void installMockLocationGrant(ClassLoader cl) {
        Class<?> helper = XposedHelpers.findClassIfExists(
                "com.android.server.location.injector.SystemAppOpsHelper", cl);
        if (helper == null) {
            HookEntry.log("SystemAppOpsHelper not found; mock-location grant skipped (sdk "
                    + Build.VERSION.SDK_INT + ")");
            return;
        }
        final int op = resolveMockOp(cl);
        mockOp = op;
        XC_MethodHook grant = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (op < 0 || p.args.length < 2 || !(p.args[0] instanceof Integer)
                        || (Integer) p.args[0] != op || p.args[1] == null) return;
                String pkg;
                try {
                    pkg = (String) XposedHelpers.callMethod(p.args[1], "getPackageName");
                } catch (Throwable t) {
                    return;
                }
                // Only our own package, only the mock-location op: nothing else is affected.
                if (Keys.PKG.equals(pkg)) p.setResult(Boolean.TRUE);
            }
        };
        int n = 0;
        for (String m : new String[]{"noteOp", "noteOpNoThrow", "checkOpNoThrow", "startOpNoThrow"}) {
            n += HookUtil.hookAll(helper, m, grant);
        }
        mockGrantHooks = n;
        HookEntry.log("mock-location grant hooks: " + n + " (op=" + op + ")");
    }

    /** OP_MOCK_LOCATION code; resolved reflectively because it is a hidden constant, 58 as fallback. */
    private static int resolveMockOp(ClassLoader cl) {
        Class<?> aom = XposedHelpers.findClassIfExists("android.app.AppOpsManager", cl);
        if (aom != null) {
            try {
                return XposedHelpers.getStaticIntField(aom, "OP_MOCK_LOCATION");
            } catch (Throwable ignored) {
            }
            try {
                Object v = XposedHelpers.callStaticMethod(aom, "strOpToOp", "android:mock_location");
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }
        return 58; // stable AOSP value for OP_MOCK_LOCATION
    }

    /** getScanResults → empty, getConnectionInfo → BSSID masked, for non-exempt normal apps. */
    private static void installWifiHooks(Class<?> wifi, final SpoofState st) {
        int n = 0;
        n += HookUtil.hookAll(wifi, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!shouldBlock(st, p.thisObject, p.args, Keys.WIFI_BLOCK)) return;
                Object res = p.getResult();
                if (res == null) return;
                try {
                    p.setResult(emptyLike(res));
                } catch (Throwable t) {
                    // Leaving the real scan list in place lets Amap/Baidu/Tencent (and the risk
                    // control in apps like 云闪付/UnionPay) reverse the real WiFi position and
                    // override the spoofed GPS, so surface the failure in diagnostics.
                    wifiState = "scan-replace-failed";
                    HookEntry.log("getScanResults replace failed: " + t);
                }
            }
        });
        n += HookUtil.hookAll(wifi, "getConnectionInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!shouldBlock(st, p.thisObject, p.args, Keys.WIFI_BLOCK)) return;
                Object w = p.getResult();
                if (w == null) return;
                try {
                    Object copy = XposedHelpers.newInstance(w.getClass(), w);
                    XposedHelpers.callMethod(copy, "setBSSID", "02:00:00:00:00:00");
                    try {
                        XposedHelpers.callMethod(copy, "setMacAddress", "02:00:00:00:00:00");
                    } catch (Throwable ignored) {
                    }
                    if (st.privacy()) hideSsid(copy);
                    p.setResult(copy);
                } catch (Throwable t) {
                    HookEntry.log("getConnectionInfo mask failed: " + t);
                }
            }
        });
        wifiState = n > 0 ? "ok" : "no-methods";
        HookEntry.log("wifi hooks installed: " + n + " via " + wifi.getClassLoader());
    }

    /**
     * An empty value of the same shape the caller expects. Android 8–14 return a bare
     * {@code List<ScanResult>}; Android 15+ wrap it in {@code ParceledListSlice<ScanResult>}
     * (com.android.modules.utils), whose only list constructor takes {@code List}, not
     * {@code ArrayList} – matching {@code ArrayList} throws NoSuchMethodError and the block silently
     * fails. Construct it against {@code List.class} so scan suppression works on Android 15/16.
     */
    private static Object emptyLike(Object res) throws Exception {
        if (res instanceof List) return new ArrayList<>();
        return XposedHelpers.newInstance(res.getClass(), new Class[]{List.class},
                java.util.Collections.emptyList());
    }

    /**
     * Android 12+: every delivery goes through Registration.acceptLocationChange(LocationResult) of
     * one of LocationProviderManager's nested registration classes. Hook each class that declares it.
     */
    private static int installAcceptHooks(Class<?> lpm, SpoofState st) {
        int n = 0;
        AcceptHook hook = new AcceptHook(st);
        try {
            // getDeclaredClasses() needs the dex MemberClasses annotation, which some OEM builds
            // strip – so also try the AOSP names directly.
            java.util.LinkedHashSet<Class<?>> classes = new java.util.LinkedHashSet<>();
            try {
                classes.addAll(java.util.Arrays.asList(lpm.getDeclaredClasses()));
            } catch (Throwable ignored) {
            }
            for (String n2 : new String[]{"LocationRegistration", "LocationListenerRegistration",
                    "LocationPendingIntentRegistration", "GetCurrentLocationListenerRegistration"}) {
                Class<?> c = XposedHelpers.findClassIfExists(lpm.getName() + "$" + n2, lpm.getClassLoader());
                if (c != null) classes.add(c);
            }
            for (Class<?> inner : classes) {
                for (java.lang.reflect.Method m : inner.getDeclaredMethods()) {
                    if (!m.getName().equals("acceptLocationChange")
                            || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                    try {
                        de.robv.android.xposed.XposedBridge.hookMethod(m, hook);
                        n++;
                    } catch (Throwable t) {
                        HookEntry.log("hook " + inner.getSimpleName() + ".acceptLocationChange failed: " + t);
                    }
                }
            }
        } catch (Throwable t) {
            HookEntry.log("acceptLocationChange hooks failed: " + t);
        }
        return n;
    }

    /** Summary of what got hooked, for the app's environment check. */
    static String hookSummary() {
        return "last=" + lastHooks + " deliver=" + deliverHooks + " report=" + reportHooks
                + " accept=" + acceptHooks + " mock=" + mockGrantHooks + " wifi=" + wifiState
                + " conn=" + connState + " cellgate=" + cellGateHooks + " pump=" + Pump.status();
    }

    /** SSID → <unknown ssid>, network id → -1 on a WifiInfo copy (privacy mode). */
    static void hideSsid(Object wifiInfo) {
        try {
            Class<?> ssidCls = Class.forName("android.net.wifi.WifiSsid");
            Object none = null;
            // API 33 refactored WifiSsid: fromBytes(null) → empty ssid; 30–32: createFromHex(null); older: NONE
            for (String m : new String[]{"fromBytes", "createFromHex"}) {
                try {
                    none = XposedHelpers.callStaticMethod(ssidCls, m, (Object) null);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (none == null) none = XposedHelpers.getStaticObjectField(ssidCls, "NONE");
            XposedHelpers.callMethod(wifiInfo, "setSSID", none);
            XposedHelpers.callMethod(wifiInfo, "setNetworkId", -1);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Privacy mode: Android ID and hardware serial as seen by normal apps. Both are served from
     * system_server – the Settings provider answers Settings.Secure(ANDROID_ID) through
     * ContentProvider.Transport.call, Build.getSerial() goes through DeviceIdentifiersPolicyService.
     */
    private static void installIdentityHooks(ClassLoader cl, final SpoofState st) {
        Class<?> transport = XposedHelpers.findClassIfExists("android.content.ContentProvider$Transport", cl);
        if (transport != null) {
            HookUtil.hookAll(transport, "call", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getThrowable() != null || !(p.getResult() instanceof Bundle)) return;
                    boolean get = false, aid = false;
                    for (Object a : p.args) {
                        if ("GET_secure".equals(a)) get = true;
                        else if ("android_id".equals(a)) aid = true;
                    }
                    if (!get || !aid || !st.idSpoof()) return;
                    int uid = Binder.getCallingUid();
                    if (HookUtil.isSystemUid(uid)) return;
                    Object provider = null;
                    try {
                        provider = XposedHelpers.getSurroundingThis(p.thisObject);
                    } catch (Throwable ignored) {
                    }
                    String pkg = HookUtil.callerPackage(provider, p.args, uid);
                    if (HookUtil.isInfraPackage(pkg) || st.isExempt(pkg)) return;
                    String fake = st.str(Keys.FAKE_ANDROID_ID, null);
                    if (fake == null) return;
                    Bundle b = new Bundle((Bundle) p.getResult());
                    b.putString("value", fake);
                    p.setResult(b);
                    if (st.debug()) HookEntry.log("android_id → fake for " + pkg);
                }
            });
        }
        Class<?> policy = XposedHelpers.findClassIfExists(
                "com.android.server.os.DeviceIdentifiersPolicyService$DeviceIdentifiersPolicy", cl);
        if (policy != null) {
            XC_MethodHook serial = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    // keep SecurityException for apps that may not read the serial at all
                    if (p.getThrowable() != null || !st.idSpoof()) return;
                    int uid = Binder.getCallingUid();
                    if (HookUtil.isSystemUid(uid)) return;
                    String pkg = HookUtil.callerPackage(null, p.args, uid);
                    if (HookUtil.isInfraPackage(pkg) || st.isExempt(pkg)) return;
                    String fake = st.str(Keys.FAKE_SERIAL, null);
                    if (fake != null) p.setResult(fake);
                }
            };
            HookEntry.log("serial hooks: " + HookUtil.hookByPrefix(policy, new String[]{"getSerial"}, serial));
        }
    }

    /** Common gate for WiFi/cell blocking: started, feature on, caller is a normal app that is not exempt. */
    static boolean shouldBlock(SpoofState st, Object service, Object[] args, String featureKey) {
        if (!(st.started() || st.privacy()) || !st.bool(featureKey, true)) return false;
        int uid = Binder.getCallingUid();
        if (HookUtil.isSystemUid(uid)) return false;
        return shouldBlockPackage(st, uid, HookUtil.callerPackage(service, args, uid), featureKey);
    }

    /** Same gate for a known (uid, package) pair, e.g. a stored registration; uid -1 = unknown. */
    static boolean shouldBlockPackage(SpoofState st, int uid, String pkg, String featureKey) {
        if (!(st.started() || st.privacy()) || !st.bool(featureKey, true)) return false;
        if (uid >= 0 && HookUtil.isSystemUid(uid)) return false;
        if (pkg == null) return uid >= 0;   // unnamed caller: block only when we know it is a normal app
        return !HookUtil.isInfraPackage(pkg) && !st.isExempt(pkg);
    }

    /** getLastLocation(...) → spoofed Location for non-exempt callers. */
    static final class LastLocationHook extends XC_MethodHook {
        private final SpoofState st;

        LastLocationHook(SpoofState st) {
            this.st = st;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            String provider = requestedProvider(p.args);
            // liveness probe from our own app, works even when spoofing is off; the extras tell
            // the app what system_server actually sees (config readable? started? which hooks?)
            if (Keys.STATE_PROVIDER.equals(provider)) {
                // This channel contains only the module's simulated settings, never app-private keys/history.
                if (!HookUtil.isSystemUid(Binder.getCallingUid()) && !callerHasLocationPermission(p.thisObject)) return;
                Location state = new Location(Keys.STATE_PROVIDER);
                Bundle b = new Bundle(); b.putInt("protocol", ConfigSnapshot.PROTOCOL);
                b.putString("state", st.snapshotJson()); state.setExtras(b); p.setResult(state); return;
            }
            if (provider != null && provider.startsWith(Keys.PUMP_PROVIDER)) {
                // the app's driver asks the framework to hand out one round of spoofed fixes;
                // "anydoor.pump:gps,network" limits the round to the providers without a test provider
                if (!Keys.PKG.equals(HookUtil.callerPackage(p.thisObject, p.args, Binder.getCallingUid()))) return;
                int colon = provider.indexOf(':');
                Pump.kick(colon > 0 ? provider.substring(colon + 1) : null);
                Location ack = new Location(Keys.PUMP_PROVIDER);
                Bundle b = new Bundle();
                b.putString("pump", Pump.status());
                b.putLong("injected", Pump.injected());
                b.putLong("lastInject", Pump.lastInject());
                ack.setExtras(b);
                p.setResult(ack);
                return;
            }
            if (Keys.PROBE_PROVIDER.equals(provider)) {
                Location probe = st.build(Keys.PROBE_PROVIDER, null);
                Bundle b = new Bundle();
                b.putInt("protocol", ConfigSnapshot.PROTOCOL);
                b.putString("version", BuildInfo.VERSION);
                b.putLong("revision", st.revision());
                b.putString("error", st.error());
                b.putBoolean("lease", st.leaseValid());
                b.putBoolean("liveHook", deliverHooks > 0 || acceptHooks > 0);
                b.putLong("deliveries", deliveries.get());
                b.putLong("lastDelivery", lastDelivery);
                b.putBoolean("prefs", st.configReadable());
                b.putString("channel", st.channel());
                b.putBoolean("started", st.started());
                b.putBoolean("mockGrant", mockGrantHooks > 0);
                b.putInt("sdk", Build.VERSION.SDK_INT);
                b.putString("hooks", hookSummary());
                b.putString("wifi", wifiState);
                b.putString("conn", connState);
                b.putInt("cellGate", cellGateHooks);
                b.putString("pump", Pump.status());
                b.putLong("injected", Pump.injected());
                b.putLong("lastInject", Pump.lastInject());
                probe.setExtras(b);
                p.setResult(probe);
                return;
            }
            if (!st.started() || p.hasThrowable()) return;
            int uid = Binder.getCallingUid();
            String pkg = HookUtil.callerPackage(p.thisObject, p.args, uid);
            if (st.isExempt(pkg)) return;
            Object res = p.getResult();
            if (res == null && !callerHasLocationPermission(p.thisObject)) return;
            Location orig = res instanceof Location ? (Location) res : null;
            if (provider == null && orig != null) provider = orig.getProvider();
            if (provider == null) provider = "gps";
            if (st.debug()) HookEntry.log("getLastLocation → spoof for " + pkg + " (" + provider + ")");
            p.setResult(st.build(provider, orig));
        }

        private static String requestedProvider(Object[] args) {
            if (args == null) return null;
            if (args.length > 0 && args[0] instanceof String) return (String) args[0];
            for (Object a : args) {
                if (a != null && a.getClass().getName().equals("android.location.LocationRequest")) {
                    try {
                        Object pr = XposedHelpers.callMethod(a, "getProvider");
                        if (pr instanceof String) return (String) pr;
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        }

        private static boolean callerHasLocationPermission(Object service) {
            if (Binder.getCallingPid() == Process.myPid()) return true;
            Context ctx = HookUtil.context(service);
            if (ctx == null) return false;
            return ctx.checkCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ctx.checkCallingPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /** Receiver.callLocationChangedLocked(Location) → replace the delivered Location. */
    static final class DeliverHook extends XC_MethodHook {
        private final SpoofState st;

        DeliverHook(SpoofState st) {
            this.st = st;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (Pump.injecting() || !st.started() || p.args.length == 0) return;
            String pkg = receiverPackage(p.thisObject);
            if (st.isExempt(pkg)) return;
            Location o = p.args[0] instanceof Location ? (Location) p.args[0] : null;
            p.args[0] = st.build(o != null ? o.getProvider() : "gps", o);
            deliveries.incrementAndGet(); lastDelivery = System.currentTimeMillis();
            if (st.debug()) HookEntry.log("deliver → spoof for " + pkg);
        }

        static String receiverPackage(Object receiver) {
            try {
                Object id = XposedHelpers.getObjectField(receiver, "mIdentity");
                return (String) XposedHelpers.getObjectField(id, "mPackageName");
            } catch (Throwable ignored) {
            }
            try {
                Object id = XposedHelpers.getObjectField(receiver, "mCallerIdentity");
                try {
                    return (String) XposedHelpers.getObjectField(id, "packageName");
                } catch (Throwable ignored) {
                    return (String) XposedHelpers.getObjectField(id, "mPackageName");
                }
            } catch (Throwable ignored) {
            }
            try {
                return (String) XposedHelpers.getObjectField(receiver, "mPackageName");
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** Provider name of a LocationProviderManager, for logging. */
    private static String managerName(Object manager) {
        try {
            Object n = XposedHelpers.getObjectField(manager, "mName");
            if (n instanceof String) return (String) n;
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** Spoofed LocationResult of the same class as {@code lr}; null if it cannot be built. */
    static Object spoofResult(SpoofState st, Object lr, Object manager) {
        try {
            Location first = null;
            try {
                first = (Location) XposedHelpers.callMethod(lr, "getLastLocation");
            } catch (Throwable ignored) {
            }
            String provider = first != null ? first.getProvider() : null;
            if (provider == null && manager != null) {
                try {
                    provider = (String) XposedHelpers.getObjectField(manager, "mName");
                } catch (Throwable ignored) {
                }
            }
            Location fake = st.build(provider == null ? "gps" : provider, first);
            try {
                return XposedHelpers.callStaticMethod(lr.getClass(), "wrap", (Object) new Location[]{fake});
            } catch (Throwable t) {
                List<Location> l = new ArrayList<>();
                l.add(fake);
                return XposedHelpers.callStaticMethod(lr.getClass(), "create", l);
            }
        } catch (Throwable t) {
            HookEntry.log("LocationResult replace failed: " + t);
            return null;
        }
    }

    /**
     * Android 12+: LocationProviderManager$…Registration.acceptLocationChange(LocationResult).
     * Rewrites only this recipient, including cached delivery; leaves the global cache intact.
     *
     * <p>onReportLocation already rewrote the shared result, so this hook exists to (a) catch
     * deliveries that skip it on some OEM builds and (b) hand the original result back to exempt
     * apps. Because onReportLocation is not per-package it cannot skip an exempt app there; it
     * parks the pre-rewrite result in REAL_RESULTS instead, and we restore it here.
     */
    static final class AcceptHook extends XC_MethodHook {
        private final SpoofState st;

        AcceptHook(SpoofState st) {
            this.st = st;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            if (Pump.injecting() || !st.started() || p.args.length == 0 || p.args[0] == null) return;
            String pkg = registrationPackage(p.thisObject);
            if (st.isExempt(pkg)) {
                Object real = REAL_RESULTS.get(p.args[0]);
                if (real == null) return;
                p.args[0] = real;
                if (st.debug()) HookEntry.log("accept → real for " + pkg);
                return;
            }
            Object manager = null;
            try {
                manager = XposedHelpers.getSurroundingThis(p.thisObject);
            } catch (Throwable ignored) {
            }
            Object replaced = spoofResult(st, p.args[0], manager);
            if (replaced == null) return;
            p.args[0] = replaced;
            deliveries.incrementAndGet(); lastDelivery = System.currentTimeMillis();
            if (st.debug()) HookEntry.log("accept → spoof for " + pkg);
        }

        private static String registrationPackage(Object reg) {
            try {
                Object id = XposedHelpers.callMethod(reg, "getIdentity");
                return (String) XposedHelpers.callMethod(id, "getPackageName");
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
