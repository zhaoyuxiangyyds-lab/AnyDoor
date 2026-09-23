package io.github.zhaoyuxiangyyds_lab.anydoor;

import java.util.*;

/** Immutable, validated cross-process state. An active driver must renew its lease. */
public final class ConfigSnapshot {
    public static final String REVISION = "_revision", SCHEMA = "_schema", WALL = "_wall", ELAPSED = "_elapsed";
    public static final int PROTOCOL = 1;
    public static final long LEASE_MS = 15000;
    /**
     * Upper bound for {@link #intentValid}: how long a stale heartbeat still counts as
     * "the driver is meant to be on". Must outlast the worst case AlarmManager wakeup gap
     * (~9 min in Doze); 30 min gives ~3 alarm chances before expiring. Must stay bounded so
     * a crashed driver cannot spoof forever.
     */
    public static final long GRACE_MS = 1800000;
    public final Map<String, String> values;
    public final long revision;
    private ConfigSnapshot(Map<String, String> values, long revision) {
        this.values = Collections.unmodifiableMap(values); this.revision = revision;
    }
    public static ConfigSnapshot from(Map<String, ?> source) {
        if (source == null) return null;
        try {
            Map<String,String> m = new HashMap<>();
            for (Map.Entry<String,?> e : source.entrySet()) if (e.getValue() != null) m.put(e.getKey(), String.valueOf(e.getValue()));
            long revision = Long.parseLong(m.get(REVISION));
            double lat = Double.parseDouble(m.get(Keys.LAT)), lng = Double.parseDouble(m.get(Keys.LNG));
            if (!"1".equals(m.get(SCHEMA)) || revision <= 0 || !Double.isFinite(lat) || !Double.isFinite(lng)
                    || Math.abs(lat) > 90 || Math.abs(lng) > 180 || !m.containsKey(Keys.STARTED)) return null;
            if (!"true".equals(m.get(Keys.STARTED)) && !"false".equals(m.get(Keys.STARTED))) return null;
            Long.parseLong(m.get(WALL)); Long.parseLong(m.get(ELAPSED));
            return new ConfigSnapshot(m, revision);
        } catch (RuntimeException e) { return null; }
    }
    public boolean leaseValid(long wall, long elapsed) {
        long w = wall - Long.parseLong(values.get(WALL)), e = elapsed - Long.parseLong(values.get(ELAPSED));
        return w >= -1000 && w <= LEASE_MS && e >= 0 && e <= LEASE_MS;
    }
    public boolean started(long wall, long elapsed) {
        return "true".equals(values.get(Keys.STARTED)) && leaseValid(wall, elapsed);
    }
    /**
     * Fallback for a driver whose process is frozen (Android 12+ Cached Apps Freezer, OEM
     * background managers): the heartbeat cannot run, so {@link #leaseValid} fails even though
     * the user never asked to stop. Failing open here would leak the real fix to the app, which
     * is worse than showing a stale fake one, so we keep spoofing until {@link #GRACE_MS} runs out.
     * Both clocks are checked because elapsedRealtime resets on reboot: a stale timestamp from a
     * previous boot must not be accepted.
     */
    public boolean intentValid(long wall, long elapsed) {
        if (!"true".equals(values.get(Keys.STARTED))) return false;
        long grace = graceMs();
        long w = wall - Long.parseLong(values.get(WALL)), e = elapsed - Long.parseLong(values.get(ELAPSED));
        return w >= -1000 && w <= grace && e >= -1000 && e <= grace;
    }
    /**
     * The configured grace window, clamped to [{@link #LEASE_MS}, {@link #GRACE_MS}]. A lower bound
     * is needed because the heartbeat interval is 3000 ms, so anything below the lease would make
     * intentValid flap between ticks; the upper bound keeps a corrupted pref from spoofing forever.
     */
    private long graceMs() {
        try {
            long v = Long.parseLong(values.get(Keys.LEASE_GRACE));
            return Math.max(LEASE_MS, Math.min(GRACE_MS, v));
        } catch (RuntimeException e) { return GRACE_MS; }
    }
}
