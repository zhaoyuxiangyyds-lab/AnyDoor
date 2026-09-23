package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Keeps the driver's lease fresh while its process is frozen.
 *
 * <p>Everything the hooks check (wifi/cell/gnss blocking, last-known rewriting, test-provider
 * injection) is gated on {@code SpoofState.started()}, which requires a heartbeat younger than
 * {@link ConfigSnapshot#LEASE_MS}. That heartbeat is a {@code Handler} post inside this app's
 * process, so Android 12+ Cached Apps Freezer (and OEM background managers) stop it dead a few
 * seconds after the UI is gone - the app looks exactly like "switched off" to every hook, and the
 * real fix leaks through. {@link ConfigSnapshot#intentValid} buys a grace window for that case;
 * this alarm is what keeps the window from ever running out.
 */
final class Lease {
    /**
     * Wakeup period. Must stay comfortably below {@link ConfigSnapshot#GRACE_MS} because the OS
     * throttles idle wakeups to roughly one per 9 minutes in Doze - the alarm firing late is
     * expected, the alarm never firing is not.
     */
    static final long INTERVAL_MS = 5 * 60 * 1000L;
    /** Fixed request code: there is exactly one lease alarm, never a queue of them. */
    private static final int REQUEST = 0x0D00;

    private Lease() {}

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, LeaseReceiver.class).setAction(LeaseReceiver.ACTION);
        return PendingIntent.getBroadcast(c, REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arm (or re-arm) the next wakeup. Idempotent - FLAG_UPDATE_CURRENT replaces the old alarm. */
    static void arm(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + INTERVAL_MS;
        PendingIntent pi = pending(c);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (RuntimeException e) {
            // Some OEM builds reject exact idle alarms for background apps; the inexact idle alarm
            // still survives Doze and is only ever late, never missing.
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(c));
    }
}
