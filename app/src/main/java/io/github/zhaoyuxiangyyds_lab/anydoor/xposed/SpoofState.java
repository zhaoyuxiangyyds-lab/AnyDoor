package io.github.zhaoyuxiangyyds_lab.anydoor.xposed;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.zhaoyuxiangyyds_lab.anydoor.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import de.robv.android.xposed.XSharedPreferences;

/** Reads validated snapshots, never equates File.canRead() with successful framework IPC. */
final class SpoofState {
    private static final Set<String> ALWAYS_EXEMPT = new HashSet<>(Arrays.asList(Keys.PKG, "com.android.location.fused"));
    private XSharedPreferences prefs;
    private long lastReload = -1000, lastOpen = -10000, newestRevision;
    private final boolean systemProcess;
    private final String clientPackage;
    private boolean refreshing;
    private volatile ConfigSnapshot snapshot;
    private volatile String source = "none", error = "尚未读取";
    SpoofState() { this(true); }
    SpoofState(boolean systemProcess) { this(systemProcess, null); }
    SpoofState(boolean systemProcess, String clientPackage) { this.systemProcess = systemProcess; this.clientPackage = clientPackage; }
    private boolean clientAllowed() {
        return systemProcess || clientPackage == null || (!isExempt(clientPackage)
                && ("com.android.phone".equals(clientPackage) || "com.android.bluetooth".equals(clientPackage) || rawBool(Keys.APP_HOOK, true)));
    }

    synchronized void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (refreshing || now - lastReload < 300) return;
        refreshing = true; lastReload = now;
        try {
            ConfigSnapshot best = null;
            String channel = "none";
            error = "";
            // Ordinary scoped processes use the system's selected snapshot. This works even when
            // SELinux denies access to both the app's private XML and /data/system.
            if (!systemProcess) {
                try {
                    Context c = AndroidAppHelper.currentApplication();
                    if (c != null) {
                        LocationManager lm = (LocationManager)c.getSystemService(Context.LOCATION_SERVICE);
                        Location l = lm.getLastKnownLocation(Keys.STATE_PROVIDER);
                        if (l != null && Keys.STATE_PROVIDER.equals(l.getProvider()) && l.getExtras() != null
                                && l.getExtras().getInt("protocol", 0) == ConfigSnapshot.PROTOCOL) {
                            accept(parse(l.getExtras().getString("state", "{}")), "system");
                            return;
                        }
                    }
                } catch (Throwable t) { error = "system bridge: " + t.getClass().getSimpleName(); }
            }
            try {
                if (prefs == null && now - lastOpen >= 5000) {
                    lastOpen = now; prefs = new XSharedPreferences(Keys.PKG, Keys.CONFIG);
                }
                if (prefs != null) {
                    prefs.reload(); best = ConfigSnapshot.from(prefs.getAll());
                    if (best != null) channel = "prefs";
                }
            } catch (Throwable t) { prefs = null; error = "prefs: " + t.getClass().getSimpleName(); }
            // Independent of XSharedPreferences success. Bounded read, no mtime-only cache, writer renames atomically.
            try (InputStream in = new FileInputStream(Config.MIRROR_PATH)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (bytes.size() + n > 262144) throw new IOException("snapshot too large");
                    bytes.write(buf, 0, n);
                }
                ConfigSnapshot root = parse(new String(bytes.toByteArray(), "UTF-8"));
                if (root != null && (best == null || root.revision >= best.revision)) { best = root; channel = "root"; }
            } catch (Exception e) { if (best == null) error = "root: " + e.getClass().getSimpleName(); }
            accept(best, channel);
        } finally { refreshing = false; }
    }
    private void accept(ConfigSnapshot candidate, String channel) {
        if (candidate == null || candidate.revision < newestRevision) {
            snapshot = null; source = "none";
            if (error.isEmpty()) error = "配置缺失、格式不兼容或版本倒退";
            return;
        }
        newestRevision = candidate.revision; snapshot = candidate; source = channel; error = "";
    }
    private static ConfigSnapshot parse(String json) throws Exception {
        JSONObject o = new JSONObject(json); Map<String,Object> m = new HashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext();) { String k = it.next(); m.put(k, o.get(k)); }
        return ConfigSnapshot.from(m);
    }
    String snapshotJson() { refresh(); ConfigSnapshot s = snapshot; return s == null ? "{}" : new JSONObject(s.values).toString(); }
    long revision() { refresh(); ConfigSnapshot s = snapshot; return s == null ? 0 : s.revision; }
    String error() { refresh(); return error; }
    boolean leaseValid() { refresh(); ConfigSnapshot s = snapshot; return s != null && s.leaseValid(System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
    boolean intentValid() { refresh(); ConfigSnapshot s = snapshot; return s != null && s.intentValid(System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
    // The gate every hook uses. Deliberately keyed on intentValid(), not leaseValid(): a frozen
    // driver cannot renew its lease, and dropping the spoof then would hand the app a real fix.
    boolean started() { refresh(); ConfigSnapshot s = snapshot; return s != null && clientAllowed() && s.intentValid(System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
    boolean configReadable() { refresh(); return snapshot != null; }
    String channel() { refresh(); return source; }
    private boolean rawBool(String k, boolean def) { String v = rawStr(k, null); return v == null ? def : Boolean.parseBoolean(v); }
    private String rawStr(String k, String def) { ConfigSnapshot s = snapshot; return s == null ? def : s.values.getOrDefault(k, def); }
    private int rawInt(String k, int def) { try { return Integer.parseInt(rawStr(k, "")); } catch (RuntimeException e) { return def; } }
    private long rawLong(String k, long def) { try { return Long.parseLong(rawStr(k, "")); } catch (RuntimeException e) { return def; } }

    boolean bool(String k, boolean def) {
        refresh();
        return rawBool(k, def);
    }

    double num(String k, double def) {
        refresh();
        String s = rawStr(k, null);
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    int interval() {
        refresh();
        return Math.max(200, rawInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL));
    }

    boolean debug() {
        return bool(Keys.DEBUG_LOG, false);
    }

    String str(String k, String def) {
        refresh();
        String s = rawStr(k, def);
        return s == null || s.isEmpty() ? def : s;
    }

    /** Privacy hardening master switch – independent of whether location spoofing is started. */
    boolean privacy() {
        return clientAllowed() && bool(Keys.PRIVACY, false);
    }

    boolean idSpoof() {
        return privacy() && bool(Keys.ID_SPOOF, true);
    }

    boolean stepFake() {
        return started() && bool(Keys.STEP_FAKE, false);
    }

    /** Which fake identifier a telephony/identity getter should return, by method name; null = leave alone. */
    String fakeIdFor(String method) {
        if (method.startsWith("getDeviceId") || method.startsWith("getImei")) return str(Keys.FAKE_IMEI, null);
        if (method.startsWith("getMeid")) return str(Keys.FAKE_MEID, null);
        if (method.startsWith("getSubscriberId")) return str(Keys.FAKE_IMSI, null);
        if (method.startsWith("getIccSerialNumber") || method.startsWith("getSimSerialNumber")) return str(Keys.FAKE_ICCID, null);
        if (method.startsWith("getLine1Number")) return str(Keys.FAKE_PHONE, null);
        if (method.startsWith("getSerial")) return str(Keys.FAKE_SERIAL, null);
        return null;
    }

    boolean isExempt(String pkg) {
        if (pkg == null) return false;
        if (ALWAYS_EXEMPT.contains(pkg)) return true;
        refresh();
        String ex = rawStr(Keys.EXEMPT, "");
        if (ex == null || ex.isEmpty()) return false;
        for (String s : ex.split(",")) {
            if (pkg.equals(s.trim())) return true;
        }
        return false;
    }

    /** Whether the user configured any exempt app (the built-in always-exempt entries do not count). */
    boolean hasExempt() {
        refresh();
        String ex = rawStr(Keys.EXEMPT, "");
        return ex != null && !ex.trim().isEmpty();
    }

    /** Current spoofed fix (after jitter). */
    Fix current() {
        refresh();
        double lat = num(Keys.LAT, 39.9042), lng = num(Keys.LNG, 116.4074);
        double jitter = num(Keys.JITTER, 0);
        if (jitter > 0) {
            long seed = rawLong(Keys.SEED, 0);
            long bucket = System.currentTimeMillis() / interval();
            double[] j = GeoMath.jitter(lat, lng, jitter, seed, bucket);
            lat = j[0];
            lng = j[1];
        }
        Fix f = new Fix();
        f.lat = lat;
        f.lng = lng;
        f.alt = num(Keys.ALT, 50);
        f.acc = (float) num(Keys.ACC, 8);
        f.speed = (float) num(Keys.SPEED, 0);
        f.bearing = (float) num(Keys.BEARING, 0);
        return f;
    }

    /** Build a fresh, non-mock Location. */
    Location build(String provider, Location orig) {
        Fix f = current();
        String prov = provider == null ? "gps" : provider;
        Location l = new Location(prov);
        l.setLatitude(f.lat);
        l.setLongitude(f.lng);
        l.setAltitude(f.alt);
        l.setAccuracy(f.acc);
        l.setSpeed(f.speed);
        l.setBearing(f.bearing);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        try {
            l.setVerticalAccuracyMeters(orig != null && orig.hasVerticalAccuracy() ? orig.getVerticalAccuracyMeters() : 3f);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setBearingAccuracyDegrees(f.speed > 0.3f ? 10f : 90f);
        } catch (Throwable ignored) {
        }
        // Never copy the original extras: fused fixes carry "noGPSLocation" (the real network
        // position) and other provider-private data. Only a real GNSS fix's satellite info is
        // reproduced, because location SDKs (Amap, Baidu, Tencent) treat a "gps" fix without
        // extras.satellites > 0 as a mocked location and silently drop it.
        if ("gps".equals(prov)) l.setExtras(gpsExtras(orig));
        return l;
    }

    /**
     * satellites / maxCn0 / meanCn0 as GnssLocationProvider attaches them to every real fix. The
     * count drifts slowly with time so it looks like a live receiver, and never drops below 6.
     */
    static Bundle gpsExtras(Location orig) {
        Bundle b = new Bundle();
        int sats = 0;
        if (orig != null && orig.getExtras() != null) {
            try {
                sats = orig.getExtras().getInt("satellites", 0);
            } catch (Throwable ignored) {
            }
        }
        if (sats < 6) {
            long bucket = System.currentTimeMillis() / 20000L;
            long h = bucket * 0x9E3779B97F4A7C15L;
            h ^= (h >>> 29);
            sats = 9 + (int) ((h >>> 3) & 7);          // 9..16
        }
        b.putInt("satellites", sats);
        b.putFloat("maxCn0", 38f + (sats % 5) * 1.5f);  // dB-Hz, typical open-sky values
        b.putFloat("meanCn0", 26f + (sats % 4) * 1.25f);
        return b;
    }

    static final class Fix {
        double lat, lng, alt;
        float acc, speed, bearing;
    }
}
