
package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class DockingService extends Service implements DockingManager.DockingCallback {
    private static final String CHANNEL_ID = "docking_channel";
    private static final int NOTIF_ID = 101;

    // New: Action for forced protocol stop (from DockingEndReceiver)
    public static final String ACTION_FORCE_STOP = "com.example.myapplication.FORCE_STOP_DOCKING";

    // Broadcasts
    public static final String ACTION_TRANSFER_DONE = "com.example.myapplication.TRANSFER_DONE";
    public static final String ACTION_TRANSFER_FAILED = "com.example.myapplication.TRANSFER_FAILED";

    // Retry
    private static final long RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(1);
    private long firstFailureAtMs = 0L;

    private DockingManager dockingManager;
    private Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isWithinDockingWindow()) {
                updateNotification("Retrying docking protocol...");
                handler.post(() -> dockingManager.startNightDockingFlow());
            } else {
                long delay = millisUntilWindowOpens();
                updateNotification("Waiting for docking window to reopen...");
                handler.postDelayed(this, delay);
            }
        }
    };

    private final BroadcastReceiver transferDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // On transfer success, broadcast sync start for UI
            Intent syncStart = new Intent("com.example.myapplication.ACTION_SYNC_START");
            syncStart.setPackage(getPackageName());
            sendBroadcast(syncStart);
            updateNotification("File transfer completed. Syncing files...");
            cancelRetry();
            // When sync is done, broadcast sync done for UI
            Intent syncDone = new Intent("com.example.myapplication.ACTION_SYNC_DONE");
            syncDone.setPackage(getPackageName());
            sendBroadcast(syncDone);
            stopSelf();
        }
    };

    private final BroadcastReceiver transferFailedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            Log.w("DockingService", "Transfer failed. Reason=" + reason);
            updateNotification("Transfer failed" + (reason != null ? " (" + reason + ")" : "") + ". Entering silent state...");
            sendDockingStatus("Transfer failed. Entering silent state...");
            // Do not schedule a retry here; let DockingManager manage silent and restart
            if (dockingManager != null) dockingManager.forceSilentState();
        }
    };

    private void sendDockingStatus(String status) {
        Intent i = new Intent("com.example.myapplication.DOCKING_STATUS");
        i.setPackage(getPackageName());
        i.putExtra("status", status);
        sendBroadcast(i);
    }

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                Log.w("DockingService", "Bluetooth turned off. Forcing silent state.");
                updateNotification("Bluetooth off. Entering silent state...");
                sendDockingStatus("Bluetooth off. Entering silent state...");
                if (dockingManager != null) dockingManager.forceSilentState();
                // Removed scheduleRetry(); let DockingManager resume after its silent timer
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("DockingService", "DockingService created");
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Docking protocol running..."));

        // Acquire WakeLock to keep CPU awake during docking protocol
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DockingService::WakeLock");
            wakeLock.acquire();
            Log.d("DockingService", "WakeLock acquired");
        }

        // Load latest window hours
        SharedPreferences prefs = getSharedPreferences("docking_prefs", MODE_PRIVATE);
        int startHour = prefs.getInt("night_start_hour", 20);
        int startMinute = prefs.getInt("night_start_minute", 0);
        int endHour = prefs.getInt("night_end_hour", 9);
        int endMinute = prefs.getInt("night_end_minute", 0);

        dockingManager = new DockingManager(this, this);
        dockingManager.nightStartHour = startHour;
        dockingManager.nightStartMinute = startMinute;
        dockingManager.nightEndHour = endHour;
        dockingManager.nightEndMinute = endMinute;

        handler.post(() -> dockingManager.startNightDockingFlow());
        // Receivers: removed connectivityReceiver; docking is Bluetooth-only
        registerReceiver(transferDoneReceiver, new IntentFilter(ACTION_TRANSFER_DONE), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(transferFailedReceiver, new IntentFilter(ACTION_TRANSFER_FAILED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        // Register receiver for forced protocol stop
        registerReceiver(forceStopReceiver, new IntentFilter(ACTION_FORCE_STOP), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
            // Reset forceStopped flag to allow protocol restart
            if (dockingManager != null) {
                Log.d("Docking","Resetting Flag");
                dockingManager.forceStopped = false;
            }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        cancelRetry();
        
        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d("DockingService", "WakeLock released");
        }
        
        try { unregisterReceiver(transferDoneReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(transferFailedReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(forceStopReceiver); } catch (Exception ignored) {}
        // connectivityReceiver registration removed; no unregister

        // Restart ScanningService
        // Intent scanIntent = new Intent(this, ScanningService.class);
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        //     startForegroundService(scanIntent);
        // } else {
        //     startService(scanIntent);
        // }
        // Log.d("DockingService", "DockingService destroyed, ScanningService restarted");
    }
    // New: Receiver for forced protocol stop
    private final BroadcastReceiver forceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.w("DockingService", "Received FORCE_STOP_DOCKING. Cleaning up protocol...");
            if (dockingManager != null) 
                // dockingManager.forceSilentState();
                dockingManager.forceStopProtocol();
            updateNotification("Docking protocol forcibly stopped.");
            sendDockingStatus("Docking protocol forcibly stopped.");
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Docking Protocol", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shimmer Docking Protocol")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();
    }

    // DockingManager.DockingCallback
    @Override
    public void onDocked() {
        cancelRetry();
        String mac = null;
        if (dockingManager != null) {
            mac = dockingManager.getCurrentMac();
        }
        String msg = (mac != null && !mac.isEmpty())
            ? ("Shimmer " + mac + " docked. Preparing file transfer...")
            : "Shimmer docked. Preparing file transfer...";
        updateNotification(msg);
        sendDockingStatus(msg);
    }

    @Override
    public void onUndocked() {
        updateNotification("Shimmer undocked. Entering silent state...");
        sendDockingStatus("Shimmer undocked. Entering silent state...");
        // Removed scheduleRetry(); DockingManager handles silent window and resume
    }

    @Override
    public void onAmbiguous() {
        updateNotification("Ambiguous state. Querying dock status...");
        sendDockingStatus("Ambiguous state. Querying dock status...");
    }

    @Override
    public void onFileTransferStart() {
        cancelRetry();
        String mac = null;
        if (dockingManager != null) {
            mac = dockingManager.getCurrentMac();
        }
        String msg = (mac != null && !mac.isEmpty()) ? ("File transfer started for " + mac) : "File transfer started.";
        updateNotification(msg);
        sendDockingStatus(msg);
        // Notify UI that transfer started
        try {
            Intent i = new Intent("com.example.myapplication.ACTION_TRANSFER_START");
            i.setPackage(getPackageName());
            if (mac != null) i.putExtra("mac", mac);
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIF_ID, buildNotification(text));
    }

    private void scheduleRetry() {
        if (firstFailureAtMs == 0L) {
            firstFailureAtMs = System.currentTimeMillis();
        }
        cancelRetry();
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void cancelRetry() {
        handler.removeCallbacks(retryRunnable);
        firstFailureAtMs = 0L;
    }

    private boolean isWithinDockingWindow() {
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int start = dockingManager.nightStartHour;
        int end = dockingManager.nightEndHour;
        if (start == end) return false;
        if (start < end) return now >= start && now < end;
        return now >= start || now < end;
    }

    private long millisUntilWindowOpens() {
        Calendar cal = Calendar.getInstance();
        int start = dockingManager.nightStartHour;

        Calendar next = (Calendar) cal.clone();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (cal.get(Calendar.HOUR_OF_DAY) >= start) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        next.set(Calendar.HOUR_OF_DAY, start);
        long diff = next.getTimeInMillis() - cal.getTimeInMillis();
        return Math.max(1000L, diff);
    }
}