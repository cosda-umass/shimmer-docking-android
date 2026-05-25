package com.example.myapplication;
import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class ScanningService extends Service {
    private static final String TAG = "BluetoothScanService";
    private static final String CHANNEL_ID = "ShimmerScanChannel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_TIMER_UPDATE = "com.example.myapplication.ACTION_TIMER_UPDATE";
    public static final String ACTION_SCAN_RESULTS = "com.example.myapplication.ACTION_SCAN_RESULTS";
    public static final String ACTION_SLEEP_TIMER_UPDATE = "com.example.myapplication.ACTION_SLEEP_TIMER_UPDATE";
    public static final String ACTION_STATUS_UPDATE = "com.example.myapplication.ACTION_STATUS_UPDATE";
    public static final String EXTRA_RESET_PROTOCOL = "com.example.myapplication.EXTRA_RESET_PROTOCOL";
    private BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    // Persistent: MAC address -> last seen timestamp (all-time)
    private final java.util.HashMap<String, Long> allTimeLastSeen = new java.util.HashMap<>();
    // Persistent: MAC address -> device name (all-time)
    private final java.util.HashMap<String, String> allTimeDeviceNames = new java.util.HashMap<>();
    // Per-scan: MAC address -> last seen timestamp (this scan only)
    private final java.util.HashMap<String, Long> scanDevices = new java.util.HashMap<>();
    // Per-scan: MAC address -> device name (this scan only)
    private final java.util.HashMap<String, String> scanDeviceNames = new java.util.HashMap<>();
    // Per-scan: set of unique Shimmers found in this scan
    private final java.util.HashSet<String> currentScanShimmers = new java.util.HashSet<>();

    // Extended search flag and elapsed time tracker.
    private long extendedSearchElapsed = 0;
    private boolean isExtendedSearch = false;
    private boolean isScanning = false;

    // Timer durations and sleep intervals - aggressive scanning for Shimmer's 30sec/30min Bluetooth cycle:
    private static final long SCAN_DURATION_MS = 90 * 1000;                  // 1.5 min scan (aggressive)
    private static final long SCAN_INTERVAL_MS = 10 * 1000;                  // 10 sec break between scans
    private static final long EXTENDED_SEARCH_TOTAL_MS = 60 * 60 * 1000;     // total extended search period = 1 hour
    private static final long SLEEP_30_MIN_MS = 3 * 60 * 1000;              // 30 * 60 * 1000;  sleep 30 minutes if ≥2 devices found
    private static final long SLEEP_20_MIN_MS = 20 * 60 * 1000;              // sleep 20 minutes if <2 devices found after 1 hour

    private long scanStartTime = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    // Add a flag to track service active state.
    private boolean isServiceActive = false;

    // Add a new field to prevent duplicate handling.
    private boolean scanFinishedHandled = false;

    // Prevent scans while sleeping between cycles
    private volatile boolean isSleeping = false;

    // Flag to prevent unintended restarts while sleeping
    private boolean hasEverStartedScan = false;

    // Timer runnable for scan countdown.
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsed = System.currentTimeMillis() - scanStartTime;
            long remaining = SCAN_DURATION_MS - elapsed;
            Intent intent = new Intent(ACTION_TIMER_UPDATE);
            intent.putExtra("remaining_time", Math.max(remaining, 0));
            intent.setPackage(getApplicationContext().getPackageName());
            Log.d(TAG, "Broadcasting scan timer: " + Math.max(remaining, 0) + " ms");
            sendBroadcast(intent);

            if (remaining > 0 && currentScanShimmers.size() < 2) {
                timerHandler.postDelayed(this, 1000);
            } else {
                Log.d(TAG, "Scan duration ended or two devices found. Final broadcast sent.");
                onScanFinished();
            }
        }
    };

    // Sleep timer runnable to count down sleep period.
    private class SleepTimerRunnable implements Runnable {
        private long remainingSleep;

        public void setRemainingSleep(long sleepMs) {
            this.remainingSleep = sleepMs;
        }

        @Override
        public void run() {
            remainingSleep -= 1000;
            Intent intent = new Intent(ACTION_SLEEP_TIMER_UPDATE);
            intent.putExtra("remaining_sleep", Math.max(remainingSleep, 0));
            intent.setPackage(getApplicationContext().getPackageName());
            Log.d(TAG, "Broadcasting sleep timer: " + Math.max(remainingSleep, 0) + " ms");
            sendBroadcast(intent);

            sendStatusUpdate("Sleeping: " + (remainingSleep / 60000) + " min " +
                    ((remainingSleep % 60000) / 1000) + " sec left");

            if (remainingSleep > 0) {
                handler.postDelayed(this, 1000);
            } else {
                Log.d(TAG, "Sleep timer ended.");
                isSleeping = false;
                // Clear persisted sleep state
                try { getSharedPreferences("app_state", MODE_PRIVATE).edit().remove("sleep_until").apply(); } catch (Exception ignored) {}
                // After sleep, always start with an initial scan.
                startInitialScan();
            }
        }
    }

    // Use our custom sleep timer runnable.
    private final SleepTimerRunnable sleepTimerRunnable = new SleepTimerRunnable();

    // Add a field to track the current SleepTimerRunnable instance.
    private SleepTimerRunnable currentSleepTimer = null;

    // Bluetooth discovery receiver.
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBluetoothEvents(intent);
        }
    };

    // Listen for Bluetooth state changes to clear stale devices when BT turns OFF
    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    // If sleeping, cancel sleep and wait for ON to restart from the beginning
                    if (isSleeping) {
                        try { if (currentSleepTimer != null) handler.removeCallbacks(currentSleepTimer); } catch (Exception ignored) {}
                        isSleeping = false;
                        try { getSharedPreferences("app_state", MODE_PRIVATE).edit().remove("sleep_until").apply(); } catch (Exception ignored) {}
                        updateNotification("Bluetooth turned off. Sleep cancelled.");
                        sendStatusUpdate("Bluetooth turned off. Sleep cancelled.");
                        // Do not schedule retries here; restart when BT turns ON
                        return;
                    }
                    // Not sleeping: stop timers and clear list; do not schedule retries
                    timerHandler.removeCallbacksAndMessages(null);
                    handler.removeCallbacksAndMessages(null);
                    // foundDevices.clear();
                    currentScanShimmers.clear(); // Track unique Shimmers found in this scan
                    broadcastEmptyScanResults();
                    updateNotification("Bluetooth is off. Waiting for it to turn on...");
                    sendStatusUpdate("Bluetooth is off. Waiting for it to turn on...");
                } else if (state == BluetoothAdapter.STATE_ON) {
                    // If sleeping, do nothing; wait for sleep timer to end
                    if (isSleeping) {
                        updateNotification("Bluetooth enabled (sleeping). Will resume after sleep...");
                        sendStatusUpdate("Bluetooth enabled. Sleeping...");
                        return;
                    }
                    // Restart protocol from the beginning only when not sleeping
                    updateNotification("Bluetooth enabled. Restarting scan...");
                    sendStatusUpdate("Bluetooth enabled. Restarting scan...");
                    startInitialScan();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceActive = true;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        Log.d(TAG, "Service created");

        // Register for Bluetooth discovery events.
        registerReceiver(discoveryReceiver, createBluetoothIntentFilter());
        // Register for Bluetooth state changes.
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Service started"));
        
        // Acquire WakeLock to keep CPU awake during scanning operations
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScanningService::WakeLock");
            wakeLock.acquire();
            Log.d(TAG, "WakeLock acquired");
        }
        
        // startService(new Intent(this, ScanningService.class));

        // Clear any stale device list in UI at service start
        // (No need to clear foundDevices; it has been replaced by allTimeLastSeen/scanDevices)
        broadcastEmptyScanResults();

        // If we were sleeping before process restart, resume sleeping and do NOT start scanning
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
            long until = prefs.getLong("sleep_until", 0L);
            long now = System.currentTimeMillis();
            if (until > now) {
                long remaining = until - now;
                isSleeping = true;
                if (currentSleepTimer != null) handler.removeCallbacks(currentSleepTimer);
                currentSleepTimer = new SleepTimerRunnable();
                currentSleepTimer.setRemainingSleep(remaining);
                handler.post(currentSleepTimer);
                updateNotification("Sleeping for " + (remaining / 60000) + " minutes");
                sendStatusUpdate("Sleeping: " + (remaining / 60000) + " min " + ((remaining % 60000) / 1000) + " sec left");
                return; // do not start scans while resuming sleep
            }
        } catch (Exception ignored) {}

        // Do not start scanning here; onStartCommand will handle it
    }

    private void saveStatusToPrefs(String status) {
        android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putString("status_text", status == null ? "" : status).apply();
    }

    private void persistDevices(java.util.List<String> devices) {
        android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        if (devices != null) {
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(devices.get(i));
            }
        }
        // Newline format (existing readers)
        prefs.edit().putString("scanned_devices", sb.toString()).apply();
        // JSON format (backward/forward compatible restore)
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            if (devices != null) for (String d : devices) arr.put(d);
            prefs.edit().putString("scanned_devices_json", arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void broadcastEmptyScanResults() {
        Intent updateIntent = new Intent(ACTION_SCAN_RESULTS);
        updateIntent.putStringArrayListExtra("devices", new ArrayList<>());
        updateIntent.setPackage(getApplicationContext().getPackageName());
        sendBroadcast(updateIntent);
        // Persist empty list
        persistDevices(new ArrayList<>());
    }

    // Create intent filter for Bluetooth discovery events.
    private IntentFilter createBluetoothIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        return filter;
    }

    // Build a persistent notification.
    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shimmer Bluetooth Scanner")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // use high to ensure visibility
                .setOnlyAlertOnce(true)
                .build();
    }

    // Create notification channel for API 26+
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Shimmer Scan Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel for Shimmer Scan notifications");
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    // Update our persistent notification with the given message.
    private void updateNotification(String message) {
        Log.d(TAG, "Updating notification status to: " + message);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(message));
        }
    }

    // Handle Bluetooth discovery events.
    private void handleBluetoothEvents(Intent intent) {
        // Ignore discovery events during sleep
        if (isSleeping) {
            Log.d(TAG, "Ignoring BT discovery event during sleep: " + intent.getAction());
            return;
        }
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
            Log.d(TAG, "Discovery started");
            updateNotification("Scanning for Shimmer devices...");
            sendStatusUpdate("Scanning for Shimmer devices...");
        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            Log.d(TAG, "Discovery finished");
            // If Bluetooth got turned OFF mid-scan, do not treat as a normal scan finish.
            if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
                Log.d(TAG, "Bluetooth turned OFF during scan. Scheduling 15s retry via enable flow.");
                // Stop the scan timer updates immediately
                timerHandler.removeCallbacksAndMessages(null);
                updateNotification("Bluetooth turned off during scan. Please turn it on. Retrying in 15 sec...");
                sendStatusUpdate("Bluetooth turned off during scan. Please turn it on. Retrying in 15 sec...");
                handler.postDelayed(() -> enableBluetoothIfNeeded(ScanningService.this::startDiscovery), 15_000);
                return;
            }
            // Do NOT call onScanFinished here; let the timer control it
        } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null)
                processFoundDevice(device);
        }
    }

    // Process a discovered Bluetooth device.
    private void processFoundDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String name = device.getName();
        if (name != null && name.startsWith("Shimmer")) {
            String mac = device.getAddress();
            long now = System.currentTimeMillis();
            // Always update all-time last seen and name
            allTimeLastSeen.put(mac, now);
            allTimeDeviceNames.put(mac, name);
            // Update per-scan device lists
            scanDevices.put(mac, now);
            scanDeviceNames.put(mac, name);
            boolean isNewInThisScan = currentScanShimmers.add(mac);
            Log.d(TAG, "Found device: " + name + " - " + mac + " @ " + now);
            sendStatusUpdate("Device found: " + name + " - " + mac);

            if (isNewInThisScan) {
                // Build device info list for broadcast: "Name - MAC (Last seen: HH:mm:ss)" (this scan only)
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
                java.util.ArrayList<String> deviceInfoList = new java.util.ArrayList<>();
                for (String m : scanDevices.keySet()) {
                    long ts = scanDevices.get(m);
                    String devName = scanDeviceNames.get(m);
                    if (devName == null) devName = "Shimmer";
                    String info = devName + " - " + m + " (Last seen: " + sdf.format(new java.util.Date(ts)) + ")";
                    deviceInfoList.add(info);
                }

                // Persist current scan list so UI can restore on reopen
                persistDevices(deviceInfoList);

                // Immediately update the UI list so user can select without waiting
                Intent listIntent = new Intent(ACTION_SCAN_RESULTS);
                listIntent.putStringArrayListExtra("devices", deviceInfoList);
                listIntent.setPackage(getApplicationContext().getPackageName());
                sendBroadcast(listIntent);
            }

            // Only stop scanning after two unique Shimmer devices are found in THIS scan
            if (currentScanShimmers.size() >= 2) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                    Log.d(TAG, "Bluetooth discovery cancelled after two Shimmer devices found in current scan.");
                }
                // Broadcast device info via ACTION_TIMER_UPDATE.
                Intent infoIntent = new Intent(ACTION_TIMER_UPDATE);
                infoIntent.putExtra("device_info", name + " - " + mac);
                infoIntent.setPackage(getApplicationContext().getPackageName());
                sendBroadcast(infoIntent);
                // Stop the scan timer and finish scan immediately
                timerHandler.removeCallbacksAndMessages(null);
                onScanFinished();
            }
        }
    }

    // Called when a scan completes.
    private void onScanFinished() {
        // If onScanFinished has already been handled for this cycle, skip further processing.
        if (scanFinishedHandled) {
            return;
        }
        scanFinishedHandled = true; // Mark as handled
        isScanning = false; // mark scan as ended

        timerHandler.removeCallbacksAndMessages(null);
        int count = scanDevices.size();
        Log.d(TAG, "Scan finished. Devices found: " + count);
        updateNotification("Scan finished. " + count + " device(s) found");
        sendStatusUpdate("Scan finished. " + count + " device(s) found");

        // Always build device info list from allTimeLastSeen/allTimeDeviceNames
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        java.util.ArrayList<String> deviceInfoList = new java.util.ArrayList<>();
        for (String m : allTimeLastSeen.keySet()) {
            long ts = allTimeLastSeen.get(m);
            String devName = allTimeDeviceNames.get(m);
            if (devName == null) devName = "Shimmer";
            String info = devName + " - " + m + " (Last seen: " + sdf.format(new java.util.Date(ts)) + ")";
            deviceInfoList.add(info);
        }
        // Broadcast all-time device list to UI
        Intent updateIntent = new Intent(ACTION_SCAN_RESULTS);
        updateIntent.putStringArrayListExtra("devices", deviceInfoList);
        updateIntent.setPackage(getApplicationContext().getPackageName());
        sendBroadcast(updateIntent);
        // Persist all-time device list
        persistDevices(deviceInfoList);

        // Adaptive logic:
        if (!isExtendedSearch) {  // Initial scan branch.
            if (count < 2) {
                Log.d(TAG, "Less than 2 devices found in initial scan. Starting extended search.");
                startExtendedSearch();
            } else {
                Log.d(TAG, "2 or more devices found in initial scan. Sleeping for 30 minutes.");
                sleepThenRestart(SLEEP_30_MIN_MS);
            }
        } else {  // Extended Search branch.
            if (count >= 2) {
                Log.d(TAG, "2 or more devices found during extended search. Sleeping for 30 minutes.");
                sleepThenRestart(SLEEP_30_MIN_MS);
            } else {
                extendedSearchElapsed += SCAN_INTERVAL_MS;
                if (extendedSearchElapsed >= EXTENDED_SEARCH_TOTAL_MS) {
                    Log.d(TAG, "Extended search reached max duration (1 hour). Sleeping for 20 minutes and resetting to initial scan.");
                    isExtendedSearch = false;
                    sleepThenRestart(SLEEP_20_MIN_MS);
                } else {
                    Log.d(TAG, "Extended search ongoing. Scheduling next scan after 20 seconds.");
                    scheduleNextScan();
                }
            }
        }
    }

    // Schedule the next scan after a 20-second interval.
    private void scheduleNextScan() {
        updateNotification("Waiting " + (SCAN_INTERVAL_MS / 1000) + " seconds before next scan");
        sendStatusUpdate("Waiting " + (SCAN_INTERVAL_MS / 1000) + " seconds before next scan");
        Log.d(TAG, "Scheduling next scan in " + (SCAN_INTERVAL_MS / 1000) + " seconds");
        handler.postDelayed(() -> {
            // Double-check that the service is still active.
            if (isServiceActive) {
                // Ensure Bluetooth enable flow runs so user sees proper messages
                enableBluetoothIfNeeded(ScanningService.this::startDiscovery);
            }
        }, SCAN_INTERVAL_MS);
    }

    // Sleep for a specified duration and then restart scanning.
    private void sleepThenRestart(long sleepMs) {
        updateNotification("Sleeping for " + (sleepMs / 60000) + " minutes");
        sendStatusUpdate("Sleeping for " + (sleepMs / 60000) + " minutes");
        Log.d(TAG, "Sleeping for " + sleepMs + " ms, then restart scan");
        // Enter sleep mode so no scans can start
        isSleeping = true;

        // Persist sleep end time so we can resume if the process restarts
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
            prefs.edit().putLong("sleep_until", System.currentTimeMillis() + sleepMs).apply();
        } catch (Exception ignored) {}

        // Fully stop any ongoing/queued scan work before scheduling sleep
        try { timerHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { handler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (Exception ignored) {}

        // Remove any pending sleep timer callbacks from the previous instance.
        if (currentSleepTimer != null) {
            handler.removeCallbacks(currentSleepTimer);
        }

        // Create a fresh SleepTimerRunnable.
        currentSleepTimer = new SleepTimerRunnable();
        currentSleepTimer.setRemainingSleep(sleepMs);
        handler.post(currentSleepTimer);
    }

    // Start an initial scan.
    private void startInitialScan() {
        // Do not allow initial scan to start while sleeping
        if (isSleeping) {
            Log.d(TAG, "startInitialScan called while sleeping; ignoring");
            return;
        }
        // Extra guard: if persisted sleep_until is still in future, restore sleep state and do not start
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
            long until = prefs.getLong("sleep_until", 0L);
            if (until > System.currentTimeMillis()) {
                isSleeping = true;
                Log.d(TAG, "startInitialScan: persisted sleep active; ignoring");
                return;
            }
        } catch (Exception ignored) {}
        scanFinishedHandled = false; // Reset flag for new cycle.
        isExtendedSearch = false;
        extendedSearchElapsed = 0;
        isSleeping = false; // defensively clear sleeping
        hasEverStartedScan = true; // mark that we initiated scanning
        // foundDevices.clear(); // Do not clear device list at start
        updateNotification("Starting initial scan");
        sendStatusUpdate("Starting initial scan");
        enableBluetoothIfNeeded(ScanningService.this::startDiscovery);
    }

    // Start an extended search cycle.
    private void startExtendedSearch() {
        scanFinishedHandled = false; // Reset flag for new cycle.
        isExtendedSearch = true;
        extendedSearchElapsed = 0;
        isSleeping = false; // ensure not sleeping in extended search
        // foundDevices.clear(); // Do not clear device list at start
        updateNotification("Starting extended search");
        sendStatusUpdate("Starting extended search");
        scheduleNextScan();
    }

    // Start Bluetooth discovery.
    private void startDiscovery() {
        // Do not start discovery while sleeping or if a scan is already in progress
        if (isSleeping || isScanning) return;
        // If Bluetooth is OFF (user toggled during wait), route through enable flow for proper UI
        if (!bluetoothAdapter.isEnabled()) {
            enableBluetoothIfNeeded(ScanningService.this::startDiscovery);
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        // Reset state for a fresh scan cycle so results broadcast correctly
        scanFinishedHandled = false;
        // Clear per-scan device lists at the start of every scan
        scanDevices.clear();
        scanDeviceNames.clear();
        currentScanShimmers.clear(); // Track unique Shimmers found in this scan
        bluetoothAdapter.startDiscovery();
        isScanning = true;
        Log.d(TAG, "Bluetooth discovery started");

        scanStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        handler.postDelayed(() -> {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                Log.d(TAG, "Discovery cancelled after duration expired");
            }
        }, SCAN_DURATION_MS);
    }

    // Enable Bluetooth if it’s not enabled, then run onEnabled action.
    private void enableBluetoothIfNeeded(Runnable onEnabled) {
        // Do not try to enable/start scanning while sleeping
        if (isSleeping) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            // Surface permission issue to UI and notification as well
            updateNotification("Missing Bluetooth permission");
            sendStatusUpdate("Missing Bluetooth permission");
            return;
        }
        if (bluetoothAdapter.isEnabled()) {
            onEnabled.run();
        } else {
            // Notify user via notification and UI
            updateNotification("Enabling Bluetooth... Waiting 2 sec.");
            sendStatusUpdate("Enabling Bluetooth... Waiting 2 sec.");

            bluetoothAdapter.enable();
            Log.d(TAG, "Bluetooth not enabled. Enabling... Waiting 2 sec.");
            handler.postDelayed(() -> {
                if (bluetoothAdapter.isEnabled()) {
                    updateNotification("Bluetooth enabled. Starting scan...");
                    sendStatusUpdate("Bluetooth enabled. Starting scan...");
                    onEnabled.run();
                } else {
                    // Could not enable; inform UI and retry after 15 sec
                    updateNotification("Bluetooth could not be enabled. Please turn it on. Retrying in 15 sec...");
                    sendStatusUpdate("Bluetooth could not be enabled. Please turn it on. Retrying in 15 sec...");
                    handler.postDelayed(() -> enableBluetoothIfNeeded(onEnabled), 15_000);
                }
            }, 2000);
        }
    }

    // Disable Bluetooth.
    private void disableBluetooth() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.disable();
            Log.d(TAG, "Bluetooth disabled for sleep period.");
        }
    }

    // Broadcast a status update.
    private void sendStatusUpdate(String status) {
        // Persist status for UI restoration
        saveStatusToPrefs(status);
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra("status", status);
        intent.setPackage(getApplicationContext().getPackageName());
        Log.d(TAG, "Broadcasting status update: " + status);
        sendBroadcast(intent);
    }

    private void clearScanStatusState() {
        android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        // Keep scanned_devices and status_text so UI can persist across app restarts; only clear volatile timers
        prefs.edit()
                .remove("remaining_time")
                .remove("remaining_sleep")
                .apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If DockingService is running, do not run scanning to avoid conflicting timers/UI
        // if (isDockingServiceRunning()) {
        //     stopSelf();
        //     return START_NOT_STICKY;
        // }
        // if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
        //         createNotificationChannel();
        //         startForeground(NOTIFICATION_ID, buildNotification("Service started"));
        
        // } else {
        //     // Log a warning or update notification.
        //     Log.e(TAG, "Bluetooth scan permission not granted on boot.");
        // }

    
        boolean reset = intent != null && intent.getBooleanExtra(EXTRA_RESET_PROTOCOL, false);
        if (reset) {
            try { if (currentSleepTimer != null) handler.removeCallbacks(currentSleepTimer); } catch (Exception ignored) {}
            isSleeping = false;
            try { getSharedPreferences("app_state", MODE_PRIVATE).edit().remove("sleep_until").apply(); } catch (Exception ignored) {}
            hasEverStartedScan = false;
            if (!isScanning) startInitialScan();
            Log.d(TAG, "onStartCommand called (reset)");
            return START_STICKY;
        }
        // Persisted sleep guard: if still within sleep window, do not start scanning on app reopen
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
            long until = prefs.getLong("sleep_until", 0L);
            long now = System.currentTimeMillis();
            if (until > now) {
                isSleeping = true;
                Log.d(TAG, "onStartCommand: still sleeping; ignoring start");
                return START_STICKY;
            }
        } catch (Exception ignored) {}
        // If sleeping, ignore app reopen starts; keep sleeping
        if (isSleeping) {
            Log.d(TAG, "onStartCommand received while sleeping; ignoring");
            return START_STICKY;
        }
        // Start scanning only once on first start if not already scanning
        if (!hasEverStartedScan && !isScanning) {
            startInitialScan();
        }
        Log.d(TAG, "onStartCommand called");
        return START_STICKY;
    }

    private boolean isDockingServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo s : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.example.myapplication.DockingService".equals(s.service.getClassName())) return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy: Cleaning up timers and receivers");
        isServiceActive = false;
        timerHandler.removeCallbacksAndMessages(null);
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(discoveryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        
        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }
        
        //clearScanStatusState();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // In ScanningService.java, when scanning starts after sleep:
    private void startScanning() {
        // ...existing code to start scanning...
        long scanDuration = SCAN_DURATION_MS;
        long scanStartTime = System.currentTimeMillis();
        long remainingTime = scanDuration;

        // Persist remaining time
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putLong("remaining_time", remainingTime).apply();

        // Broadcast remaining time
        Intent timerIntent = new Intent(ACTION_TIMER_UPDATE);
        timerIntent.putExtra("remaining_time", remainingTime);
        sendBroadcast(timerIntent);

        // Start the actual scanning process
        enableBluetoothIfNeeded(this::startDiscovery);
    }
}