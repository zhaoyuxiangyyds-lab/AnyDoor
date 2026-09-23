package io.github.zhaoyuxiangyyds_lab.anydoor;

/** Shared preference names/keys. "config" is world-readable (read by hooks); "app" is private UI state. */
public final class Keys {
    private Keys() {}

    public static final String PKG = "io.github.zhaoyuxiangyyds_lab.anydoor";
    public static final String CONFIG = "config";
    public static final String APP = "app";

    // spoof state (config)
    public static final String STARTED = "started";
    /**
     * How long (ms) a stale heartbeat is still treated as "the driver is meant to be on".
     * Only consulted when the driver process cannot be scheduled at all, so it has to outlast
     * the worst-case AlarmManager wakeup gap (~9 min while the device is in Doze).
     */
    public static final String LEASE_GRACE = "lease_grace";
    public static final String LAT = "lat";            // String(double), WGS-84
    public static final String LNG = "lng";
    public static final String ALT = "alt";            // String(double) meters
    public static final String ACC = "acc";            // String(float) meters
    public static final String SPEED = "speed";        // String(float) m/s
    public static final String BEARING = "bearing";    // String(float) deg
    public static final String JITTER = "jitter";      // String(double) meters, 0 = off
    public static final String INTERVAL = "interval";  // int ms between pushes/jitter buckets
    public static final String SEED = "seed";          // long
    public static final String WIFI_BLOCK = "wifi_block";
    public static final String CELL_BLOCK = "cell_block";
    public static final String GNSS_BLOCK = "gnss_block";
    public static final String MOCK_DRIVER = "mock_driver";
    public static final String MOCK_NETWORK = "mock_network";
    public static final String EXEMPT = "exempt";      // comma separated packages that keep real location
    public static final String APP_HOOK = "app_hook";  // per-app hooks in scoped apps
    public static final String DEBUG_LOG = "debug_log";

    // route / pedometer (config)
    public static final String STEPS = "steps";          // String(double) cumulative fake step count
    public static final String STEP_FAKE = "step_fake";  // feed fake steps to step sensors in scoped apps
    public static final String STRIDE = "stride";        // String(double) meters per step for joystick walking

    // privacy hardening (config)
    public static final String PRIVACY = "privacy";          // master switch
    public static final String ID_SPOOF = "id_spoof";        // spoof IMEI/IMSI/ICCID/Android ID/serial/phone
    public static final String BT_BLOCK = "bt_block";        // drop Bluetooth scan results
    public static final String SENSOR_BLOCK = "sensor_block";// drop barometer etc. in scoped apps
    public static final String FAKE_IMEI = "fake_imei";
    public static final String FAKE_MEID = "fake_meid";
    public static final String FAKE_IMSI = "fake_imsi";
    public static final String FAKE_ICCID = "fake_iccid";
    public static final String FAKE_ANDROID_ID = "fake_android_id";
    public static final String FAKE_SERIAL = "fake_serial";
    public static final String FAKE_PHONE = "fake_phone";

    // app-only (app)
    public static final String FAVORITES = "favorites";
    public static final String HISTORY = "history";
    public static final String ROUTES = "routes";
    public static final String AMAP_KEY = "amap_key";
    public static final String MAP_LAYER = "map_layer";
    public static final String SEARCH_SRC = "search_src";
    public static final String INPUT_CRS = "input_crs";
    public static final String THEME = "theme";
    public static final String JOY_SPEED = "joy_speed";
    public static final String NOMINATIM = "nominatim";
    public static final String LAST_VIEW = "last_view";
    public static final String MOCK_ERROR = "mock_error";
    public static final String FIRST_RUN = "first_run";

    public static final int DEFAULT_INTERVAL = 1000;
    public static final String STATE_PROVIDER = "anydoor.state";
    public static final String PROBE_PROVIDER = "anydoor.probe";
    /** last-location probe name the driver uses to ask system_server for one round of direct delivery */
    public static final String PUMP_PROVIDER = "anydoor.pump";
}
