package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Foreground service that drives the spoof: keeps the "started" flag, pushes mock fixes through
 * test providers (so apps get updates even without a real GPS fix), runs route simulation and the
 * floating joystick.
 */
public class SpoofService extends Service {
    private static final String TAG = "AnyDoor";
    public static final String ACTION_START = Keys.PKG + ".START";
    public static final String ACTION_STOP = Keys.PKG + ".STOP";
    public static final String ACTION_JOY = Keys.PKG + ".JOY";
    private static final String CHANNEL = "spoof";
    private static final int NOTIF_ID = 1;

    static volatile SpoofService instance;

    private LocationManager lm;
    private Handler ui;
    private Handler bg;
    private HandlerThread bgThread;
    private volatile boolean running;
    private ProviderController providers;
    private volatile long generation;
    private volatile boolean permissionsReady;
    private long lastHeartbeat;
    private volatile String providerNote = "";
    private volatile String mockError = "";
    private volatile long lastPush;
    // system_server fallback driver ("系统直推"), used whenever no test provider is running
    private volatile String pumpStatus = "";
    private volatile long pumpInjected, pumpLast, pumpKicks;

    // base position (WGS-84) – the un-jittered target
    private double baseLat, baseLng, alt, acc, speed, bearing;
    private long lastTick;

    // route simulation
    private double[][] route;          // [i] = {lat, lng, stopFlag}
    private double routeSpeed;         // target m/s
    private String routeLoop = "none"; // none | loop | pingpong
    private String routeMode = "walking";
    private double routeVar;           // 0..1 fraction of random speed variation
    private boolean routePause;        // random pauses at crossings
    private double routeStride;        // meters per step, 0 = no steps
    private volatile boolean routeActive;
    private int routeSeg;
    private double routeOffset;
    private double routeTotal;
    private double speedNoise;         // smoothed random walk in [-1, 1]
    private long pauseUntil;           // elapsedRealtime ms
    private final java.util.Random rnd = new java.util.Random();

    // pedometer
    private double stepsTotal;         // cumulative fake steps, persisted in config
    private double burstLeft, burstRate;   // "add N steps at R steps/s" without moving

    // joystick
    private JoystickOverlay joystick;
    private volatile float joyX, joyY;
    private double joySpeed = 1.5;
    private long lastConfigWrite;

    public static boolean isRunning() {
        SpoofService s = instance;
        return s != null && s.running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ui = new Handler(Looper.getMainLooper());
        bgThread = new HandlerThread("anydoor-bg");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        providers = new ProviderController(new ProviderController.Backend() {
            public void add(String p) throws Exception {
                addOneProvider(p, "gps".equals(p));
                Config.app(SpoofService.this).edit().putBoolean("owned_" + p, true).commit();
            }
            public void enable(String p) { lm.setTestProviderEnabled(p, true); }
            public void remove(String p) {
                try { lm.removeTestProvider(p); } catch (IllegalArgumentException absent) { /* already removed */ }
                Config.app(SpoofService.this).edit().putBoolean("owned_" + p, false).commit();
            }
            public void push(String p) {
                double[] pos = jittered();
                pushLocation(p, pos[0], pos[1]);
            }
        });
        for (String p : new String[]{"gps", "network"}) {
            if (Config.app(this).getBoolean("owned_" + p, false)) providers.restoreOwnership(p);
        }
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null && !Config.config(this).getBoolean(Keys.STARTED, false)) {
            stopSpoof(); return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSpoof();
        } else if (ACTION_JOY.equals(action)) {
            setJoystickVisible(!(joystick != null && joystick.isShown()));
        } else {
            startSpoof();
        }
        return running ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        permissionsReady = false;
        ui.removeCallbacks(ticker);
        Config.commit(Config.config(this).edit().putBoolean(Keys.STARTED, false));
        // The driver is gone for real, so stop asking the OS to wake us: a live alarm with no driver
        // behind it would only refresh a lease that nothing honours.
        Lease.cancel(this);
        removeProviders();
        setJoystickVisible(false);
        bg.removeCallbacksAndMessages(null);
        bgThread.quitSafely();
        instance = null;
        super.onDestroy();
    }

    /**
     * Re-entry point for {@link LeaseReceiver}: the alarm can fire while this process is frozen or
     * already dead. A frozen process is thawed by the broadcast itself, so its ticker resumes on its
     * own; a dead one has to be brought back, otherwise the alarm would keep refreshing a lease with
     * nothing behind it and every hook would go on faking a location forever.
     */
    static boolean revive(Context c) {
        SpoofService s = instance;
        if (s != null && s.running) return true;
        Intent i = new Intent(c, SpoofService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (RuntimeException e) {
            // Alarm broadcasts normally whitelist a foreground-service start; some OEM builds do not.
            Log.w(TAG, "lease revive failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void startSpoof() {
        Config.ensureDefaults(this);
        SharedPreferences c = Config.config(this);
        baseLat = Config.num(c, Keys.LAT, 39.908722);
        baseLng = Config.num(c, Keys.LNG, 116.397499);
        stepsTotal = Config.num(c, Keys.STEPS, 0);
        reloadParams(c);
        Config.commit(c.edit().putBoolean(Keys.STARTED, true));
        // First line of defence against being frozen: an alarm that wakes us before the lease can
        // expire. The in-process ticker keeps the lease fresh while we are scheduled; this covers the
        // case where we are not.
        Lease.arm(this);
        startForeground(NOTIF_ID, buildNotification());
        if (running) return;
        running = true;
        permissionsReady = false;
        generation = providers.start();
        final long token = generation;
        lastTick = SystemClock.elapsedRealtime();
        bg.post(new Runnable() {
            @Override
            public void run() {
                ensureMockPermission();
                ui.post(() -> {
                    if (!running || generation != token) return;
                    permissionsReady = true;
                    addProviders();
                    pushNow();
                });
            }
        });
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void stopSpoof() {
        running = false;
        routeActive = false;
        burstLeft = 0;
        ui.removeCallbacks(ticker);
        Config.commit(Config.config(this).edit().putBoolean(Keys.STARTED, false)
                .putString(Keys.SPEED, "0"));
        // Symmetric to startSpoof(): once the user really switched off, a pending wakeup would only
        // keep refreshing a lease that nothing behind it honours.
        Lease.cancel(this);
        setJoystickVisible(false);
        removeProviders();
        stopForeground(true);
        stopSelf();
    }

    /** Re-read tunables that the UI may change while running. */
    private void reloadParams(SharedPreferences c) {
        alt = Config.num(c, Keys.ALT, 50);
        acc = Config.num(c, Keys.ACC, 8);
        if (!routeActive && joyX == 0 && joyY == 0) {
            speed = Config.num(c, Keys.SPEED, 0);
            bearing = Config.num(c, Keys.BEARING, 0);
        }
        try {
            joySpeed = Double.parseDouble(Config.app(this).getString(Keys.JOY_SPEED, "1.5"));
        } catch (Exception ignored) {
        }
    }

    /** Called by the UI when the user picks a new target. */
    public void setBase(double lat, double lng) {
        baseLat = lat;
        baseLng = lng;
        routeActive = false;
        writeConfig(true);
        pushNow();
    }

    // ------------------------------------------------------------------ ticking

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            double dt = Math.min(2.0, (now - lastTick) / 1000.0);
            lastTick = now;
            SharedPreferences c = Config.config(SpoofService.this);
            reloadParams(c);
            if (permissionsReady) addProviders();
            if (now - lastHeartbeat >= 3000) {
                lastHeartbeat = now;
                Config.commit(c.edit());
            }
            boolean moving = false;
            if (joyX != 0 || joyY != 0) {
                double mag = Math.min(1.0, Math.sqrt(joyX * joyX + joyY * joyY));
                double v = joySpeed * mag;
                bearing = (Math.toDegrees(Math.atan2(joyX, joyY)) + 360) % 360;
                double[] p = GeoMath.destination(baseLat, baseLng, bearing, v * dt);
                baseLat = p[0];
                baseLng = p[1];
                speed = v;
                moving = true;
                // walking-speed joystick movement also counts steps
                double stride = Config.num(c, Keys.STRIDE, 0.7);
                if (v <= 4.5 && stride > 0) stepsTotal += v * dt / stride;
            } else if (routeActive) {
                advanceRoute(dt);
                moving = true;
            } else if (!c.getString(Keys.LAT, "").equals(fmt(baseLat)) || !c.getString(Keys.LNG, "").equals(fmt(baseLng))) {
                // UI (or another component) changed the target in prefs
                baseLat = Config.num(c, Keys.LAT, baseLat);
                baseLng = Config.num(c, Keys.LNG, baseLng);
            }
            if (burstLeft > 0) {
                double add = Math.min(burstLeft, burstRate * dt);
                stepsTotal += add;
                burstLeft -= add;
                moving = true;
            }
            if (moving) writeConfig(false);
            pushNow();
            int interval = Math.max(200, c.getInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL));
            ui.postDelayed(this, moving ? Math.min(interval, 250) : interval);
        }
    };

    private static String fmt(double d) {
        return String.valueOf(d);
    }

    private void writeConfig(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastConfigWrite < 200) return;
        lastConfigWrite = now;
        Config.commit(Config.config(this).edit()
                .putString(Keys.LAT, fmt(baseLat)).putString(Keys.LNG, fmt(baseLng))
                .putString(Keys.SPEED, fmt(speed)).putString(Keys.BEARING, fmt(bearing))
                .putString(Keys.STEPS, fmt(Math.floor(stepsTotal))));
    }

    /** Current (jittered) fix, identical to what the hooks compute for this time bucket. */
    private double[] jittered() {
        SharedPreferences c = Config.config(this);
        double jitter = Config.num(c, Keys.JITTER, 0);
        int interval = Math.max(200, c.getInt(Keys.INTERVAL, Keys.DEFAULT_INTERVAL));
        long bucket = System.currentTimeMillis() / interval;
        return GeoMath.jitter(baseLat, baseLng, jitter, c.getLong(Keys.SEED, 0), bucket);
    }

    private void pushNow() {
        if (!running || !permissionsReady) return;
        providers.push(System.currentTimeMillis());
        lastPush = providers.lastPush();
        String missing = missingProviders();
        if (missing != null) kickPump(missing);
        publishProviderStatus();
    }

    /** Providers (gps/network) without a running test provider, comma separated; null when all run. */
    private String missingProviders() {
        StringBuilder b = new StringBuilder();
        for (String p : new String[]{"gps", "network"}) {
            if (providers.enabled(p)) continue;
            if (b.length() > 0) b.append(',');
            b.append(p);
        }
        return b.length() == 0 ? null : b.toString();
    }

    /**
     * A test provider is not running (exempt apps configured, driver disabled, network mock off,
     * or the ROM refused the mock-location op): ask the framework hook to hand one round of spoofed
     * fixes directly to the location registrations of non-exempt apps on those providers. The real
     * providers keep running for exempt apps.
     */
    private void kickPump(String missing) {
        try {
            Location ack = lm.getLastKnownLocation(Keys.PUMP_PROVIDER + ":" + missing);
            Bundle b = ack != null && Keys.PUMP_PROVIDER.equals(ack.getProvider()) ? ack.getExtras() : null;
            if (b == null) {
                pumpStatus = "";
                return;
            }
            pumpKicks++;
            pumpStatus = b.getString("pump", "");
            pumpInjected = b.getLong("injected", 0);
            pumpLast = b.getLong("lastInject", 0);
        } catch (Throwable t) {
            pumpStatus = "";
        }
    }

    private void pushLocation(String provider, double lat, double lng) {
        Location l = new Location(provider);
        l.setLatitude(lat);
        l.setLongitude(lng);
        l.setAltitude(alt);
        l.setAccuracy((float) (LocationManager.NETWORK_PROVIDER.equals(provider) ? Math.max(acc, 20) : acc));
        l.setSpeed((float) speed);
        l.setBearing((float) bearing);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) {
            l.setVerticalAccuracyMeters(3f);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setBearingAccuracyDegrees(speed > 0.3 ? 10f : 90f);
        }
        // real GNSS fixes carry satellites/maxCn0/meanCn0; SDKs treat a gps fix without them as mocked
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            Bundle extras = new Bundle();
            extras.putInt("satellites", 9 + (int) ((System.currentTimeMillis() / 20000L) % 7));
            extras.putFloat("maxCn0", 41f);
            extras.putFloat("meanCn0", 29f);
            l.setExtras(extras);
        }
        lm.setTestProviderLocation(provider, l);
    }


    // ------------------------------------------------------------------ mock providers

    private void ensureMockPermission() {
        try {
            RootShell.Result r = RootShell.run("appops get " + Keys.PKG + " android:mock_location");
            if (r.out.contains("allow")) return;
            RootShell.run("appops set " + Keys.PKG + " android:mock_location allow");
        } catch (Throwable t) {
            Log.w(TAG, "appops: " + t);
        }
    }

    private void addProviders() {
        if (!running || !permissionsReady) return;
        SharedPreferences c = Config.config(this);
        boolean exempt = !c.getString(Keys.EXEMPT, "").trim().isEmpty();
        boolean driver = c.getBoolean(Keys.MOCK_DRIVER, true);
        providers.reconcile(generation, driver && !exempt,
                c.getBoolean(Keys.MOCK_NETWORK, true), SystemClock.elapsedRealtime());
        if (exempt) providerNote = "已设置豁免应用：测试定位源已停用，其他应用改由系统框架直推模拟定位；豁免应用照常收到真实定位";
        else if (!driver) providerNote = "测试定位源已关闭：由系统框架直推模拟定位";
        else if (!providers.hasProviders() && !providers.error().isEmpty()) providerNote = "测试定位源不可用：暂由系统框架直推模拟定位";
        else providerNote = "";
        publishProviderStatus();
    }

    private void publishProviderStatus() {
        String next = providers.error();
        if (!next.equals(mockError)) {
            mockError = next;
            Config.app(this).edit().putString(Keys.MOCK_ERROR, next).apply();
        }
    }

    /**
     * Register one test provider. Android 12+ (API 31) deprecated the multi-boolean signature in
     * favor of addTestProvider(String, ProviderProperties); on Android 15/16 we use that via
     * reflection, falling back to the legacy call on Android 8–11.
     */
    private void addOneProvider(String name, boolean gps) throws Exception {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Class<?> ppc = Class.forName("android.location.provider.ProviderProperties");
                Class<?> bc = Class.forName("android.location.provider.ProviderProperties$Builder");
                Object b = bc.getConstructor().newInstance();
                setBool(bc, b, "setHasNetworkRequirement", !gps);
                setBool(bc, b, "setHasSatelliteRequirement", gps);
                setBool(bc, b, "setHasCellRequirement", false);
                setBool(bc, b, "setHasMonetaryCost", false);
                setBool(bc, b, "setHasAltitudeSupport", true);
                setBool(bc, b, "setHasSpeedSupport", true);
                setBool(bc, b, "setHasBearingSupport", true);
                bc.getMethod("setPowerUsage", int.class).invoke(b, gps ? 3 : 2);   // HIGH / MEDIUM
                bc.getMethod("setAccuracy", int.class).invoke(b, gps ? 1 : 2);     // FINE / COARSE
                Object props = bc.getMethod("build").invoke(b);
                LocationManager.class.getMethod("addTestProvider", String.class, ppc).invoke(lm, name, props);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "modern addTestProvider failed, falling back: " + t);
            }
        }
        lm.addTestProvider(name, !gps, gps, !gps, false, true, true, true,
                gps ? Criteria.POWER_HIGH : Criteria.POWER_MEDIUM,
                gps ? Criteria.ACCURACY_FINE : Criteria.ACCURACY_COARSE);
    }

    private static void setBool(Class<?> cls, Object obj, String method, boolean v) throws Exception {
        cls.getMethod(method, boolean.class).invoke(obj, v);
    }

    private void removeProviders() {
        permissionsReady = false;
        generation++;
        providers.stop();
        publishProviderStatus();
        // Failed cleanup ownership remains persisted and is retried on the next service start.
    }

    // ------------------------------------------------------------------ route

    /**
     * @param json {"points":[{"lat","lng","s"?}],"speed":m/s,"loop":"none|loop|pingpong"|bool,
     *             "var":0..1,"pause":bool,"stride":m,"mode":"walking|running|bicycling|driving"}
     */
    public boolean startRoute(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray pts = o.getJSONArray("points");
            if (pts.length() < 2) return false;
            double[][] r = new double[pts.length()][3];
            routeTotal = 0;
            for (int i = 0; i < pts.length(); i++) {
                JSONObject p = pts.getJSONObject(i);
                r[i][0] = p.getDouble("lat");
                r[i][1] = p.getDouble("lng");
                r[i][2] = p.optInt("s", 0);
                if (i > 0) routeTotal += GeoMath.distance(r[i - 1][0], r[i - 1][1], r[i][0], r[i][1]);
            }
            route = r;
            routeSpeed = Math.max(0.1, o.optDouble("speed", 1.4));
            Object loop = o.opt("loop");
            routeLoop = loop instanceof Boolean ? (((Boolean) loop) ? "loop" : "none") : o.optString("loop", "none");
            routeMode = o.optString("mode", "walking");
            routeVar = Math.max(0, Math.min(1, o.optDouble("var", 0)));
            routePause = o.optBoolean("pause", false);
            routeStride = Math.max(0, o.optDouble("stride", 0));
            routeSeg = 0;
            routeOffset = 0;
            speedNoise = 0;
            pauseUntil = 0;
            baseLat = r[0][0];
            baseLng = r[0][1];
            bearing = GeoMath.bearing(r[0][0], r[0][1], r[1][0], r[1][1]);
            speed = routeSpeed;
            routeActive = true;
            joyX = joyY = 0;
            writeConfig(true);
            if (!running) startSpoof();
            else pushNow();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "route: " + e);
            return false;
        }
    }

    public void stopRoute() {
        routeActive = false;
        speed = 0;
        writeConfig(true);
    }

    private void advanceRoute(double dt) {
        double[][] r = route;
        if (r == null || routeSeg >= r.length - 1) {
            routeActive = false;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pauseUntil > now) {
            speed = 0;
            return;
        }
        // Ornstein-Uhlenbeck style noise so the speed drifts smoothly instead of jumping
        speedNoise += (-speedNoise * 0.2 + rnd.nextGaussian() * 0.45) * dt;
        speedNoise = Math.max(-1, Math.min(1, speedNoise));
        double v = routeSpeed * (1 + routeVar * speedNoise);
        v = Math.max(routeSpeed * 0.25, v);
        double remaining = v * dt;
        double moved = 0;
        while (remaining > 0) {
            double[] a = r[routeSeg], b = r[routeSeg + 1];
            double segLen = GeoMath.distance(a[0], a[1], b[0], b[1]);
            double left = segLen - routeOffset;
            if (remaining < left) {
                routeOffset += remaining;
                moved += remaining;
                remaining = 0;
            } else {
                remaining -= left;
                moved += left;
                routeOffset = 0;
                routeSeg++;
                if (routeSeg >= r.length - 1) {
                    if ("loop".equals(routeLoop)) {
                        routeSeg = 0;
                    } else if ("pingpong".equals(routeLoop)) {
                        double[][] rev = new double[r.length][];
                        for (int i = 0; i < r.length; i++) rev[i] = r[r.length - 1 - i];
                        route = r = rev;
                        routeSeg = 0;
                    } else {
                        baseLat = r[r.length - 1][0];
                        baseLng = r[r.length - 1][1];
                        routeActive = false;
                        speed = 0;
                        countSteps(moved);
                        return;
                    }
                    if (routePause) {
                        pauseUntil = now + 3000 + rnd.nextInt(8000);
                        remaining = 0;
                    }
                } else if (routePause && r[routeSeg][2] > 0 && rnd.nextDouble() < pauseChance()) {
                    // stop at a crossing: red light for cars, a short wait for everybody else
                    boolean car = "driving".equals(routeMode);
                    pauseUntil = now + (car ? 5000 + rnd.nextInt(25000) : 1500 + rnd.nextInt(6000));
                    remaining = 0;
                }
            }
        }
        double[] a = r[routeSeg], b = r[routeSeg + 1];
        bearing = GeoMath.bearing(a[0], a[1], b[0], b[1]);
        double[] p = GeoMath.destination(a[0], a[1], bearing, routeOffset);
        baseLat = p[0];
        baseLng = p[1];
        speed = pauseUntil > now ? 0 : v;
        countSteps(moved);
    }

    private double pauseChance() {
        if ("driving".equals(routeMode)) return 0.35;
        if ("bicycling".equals(routeMode)) return 0.2;
        return 0.08;
    }

    private void countSteps(double meters) {
        if (routeStride > 0 && meters > 0) stepsTotal += meters / routeStride;
    }

    // ------------------------------------------------------------------ pedometer

    /** Add {@code n} steps over time at {@code perMinute} steps/min without moving. */
    public void stepBurst(double n, double perMinute) {
        burstLeft = Math.max(0, n);
        burstRate = Math.max(1, perMinute) / 60.0;
        if (!running) startSpoof();
    }

    public void stopSteps() {
        burstLeft = 0;
    }

    public void setSteps(double n) {
        stepsTotal = Math.max(0, n);
        writeConfig(true);
    }

    public double steps() {
        return stepsTotal;
    }

    // ------------------------------------------------------------------ joystick

    public void setJoystickVisible(boolean show) {
        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                RootShell.run("appops set " + Keys.PKG + " SYSTEM_ALERT_WINDOW allow");
            }
            if (!Settings.canDrawOverlays(this)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + Keys.PKG));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Throwable ignored) {
                }
                return;
            }
            if (joystick == null) joystick = new JoystickOverlay(this, new JoystickOverlay.Listener() {
                @Override
                public void onMove(float x, float y) {
                    joyX = x;
                    joyY = y;
                    if (x == 0 && y == 0) {
                        speed = 0;
                        writeConfig(true);
                    } else if (running) {
                        ui.removeCallbacks(ticker);
                        ui.post(ticker);
                    }
                }

                @Override
                public void onSpeedChanged(double metersPerSecond) {
                    joySpeed = metersPerSecond;
                    Config.app(SpoofService.this).edit().putString(Keys.JOY_SPEED, String.valueOf(metersPerSecond)).apply();
                }

                @Override
                public void onClose() {
                    setJoystickVisible(false);
                }
            });
            joystick.show(joySpeed);
            if (!running) startSpoof();
        } else {
            joyX = joyY = 0;
            if (joystick != null) joystick.hide();
        }
    }

    // ------------------------------------------------------------------ status

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            double[] p = running ? jittered() : new double[]{baseLat, baseLng};
            o.put("running", running);
            o.put("lat", baseLat).put("lng", baseLng);
            o.put("curLat", p[0]).put("curLng", p[1]);
            o.put("speed", speed).put("bearing", bearing);
            o.put("routeActive", routeActive);
            o.put("paused", routeActive && pauseUntil > SystemClock.elapsedRealtime());
            o.put("steps", Math.floor(stepsTotal)).put("burstLeft", Math.ceil(burstLeft));
            if (route != null) {
                double done = 0;
                for (int i = 0; i < routeSeg && i < route.length - 1; i++)
                    done += GeoMath.distance(route[i][0], route[i][1], route[i + 1][0], route[i + 1][1]);
                done += routeOffset;
                o.put("routeDone", done).put("routeTotal", routeTotal).put("routeSeg", routeSeg);
            }
            o.put("joystick", joystick != null && joystick.isShown());
            o.put("providers", providers.hasProviders());
            o.put("gpsReady", providers.enabled("gps"));
            o.put("networkReady", providers.enabled("network"));
            o.put("providerNote", providerNote);
            o.put("mockError", mockError);
            o.put("lastPush", lastPush);
            String missing = running ? missingProviders() : null;
            o.put("pumpActive", missing != null);
            o.put("pumpProviders", missing == null ? "" : missing);
            o.put("pumpStatus", pumpStatus).put("pumpInjected", pumpInjected)
                    .put("pumpLast", pumpLast).put("pumpKicks", pumpKicks);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "位置模拟", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, SpoofService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, flags);
        Intent joy = new Intent(this, SpoofService.class).setAction(ACTION_JOY);
        PendingIntent pj = PendingIntent.getService(this, 2, joy, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("任意门 · 位置模拟中")
                .setContentText(String.format(Locale.US, "%.6f, %.6f", baseLat, baseLng))
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "摇杆", pj).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "停止", ps).build());
        return b.build();
    }

    void refreshNotification() {
        if (!running) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }
}
