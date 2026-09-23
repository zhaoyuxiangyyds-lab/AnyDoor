package io.github.zhaoyuxiangyyds_lab.anydoor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;

/**
 * Fired by {@link Lease} while the spoof is meant to be on. Delivering this broadcast thaws the
 * frozen app process long enough to bump the heartbeat and, if the driver died outright, to bring
 * it back. When the user has actually switched off it cancels itself instead of renewing.
 *
 * <p>The mirror write ({@link Config#mirror}) is asynchronous — scheduled 100 ms ahead on a
 * background executor. If {@code onReceive} returns immediately the Cached Apps Freezer re-freezes
 * the process before that executor ever runs, so the hooks (which read the mirror file, not the
 * app's private SharedPreferences) never see the renewed lease and the grace window runs out.
 * We therefore hold the broadcast open with {@link #goAsync()} plus a partial wakelock until
 * {@link Config#syncNow} confirms the mirror is on disk.
 */
public class LeaseReceiver extends BroadcastReceiver {
    static final String ACTION = Keys.PKG + ".LEASE";

    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences p = Config.config(c);
        if (!p.getBoolean(Keys.STARTED, false)) {
            // Switched off (or never started) while we were frozen: stop renewing and go quiet.
            Lease.cancel(c);
            return;
        }

        // Keep the process thawed until the mirror write lands. Without this the freezer
        // re-freezes us the instant onReceive returns, the 100 ms-delayed mirror write never
        // runs, and the hooks keep reading a stale lease until the grace window expires.
        final PendingResult result = goAsync();
        final PowerManager.WakeLock wl = acquireWakeLock(c);

        new Thread(() -> {
            try {
                // Renewing the timestamps is the whole point: it is what keeps
                // ConfigSnapshot.intentValid() accepting the lease even though the driver's
                // own ticker may never run again.
                Config.commit(p.edit());
                Config.syncNow(c); // block until the mirror file is on disk
                // Re-check after sync: the user may have stopped spoofing while we were
                // frozen or syncing. Re-arming now would fight stopSpoof()'s cancel and
                // revive() could flip STARTED back to true.
                if (!p.getBoolean(Keys.STARTED, false)) {
                    Lease.cancel(c);
                    return; // finally releases wakelock + finishes
                }
                // Only re-arm if the service is alive or could be revived. If revive fails
                // (OEM background restriction, etc.) let the grace window expire rather than
                // spoofing forever with no driver behind it.
                if (SpoofService.revive(c)) {
                    Lease.arm(c);
                }
            } finally {
                try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
                result.finish();
            }
        }, "AnyDoor-lease").start();
    }

    private static PowerManager.WakeLock acquireWakeLock(Context c) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            if (pm == null) return null;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnyDoor:lease");
            wl.acquire(16000); // keep CPU awake past syncNow's 14 s timeout
            return wl;
        } catch (Throwable t) {
            return null;
        }
    }
}
