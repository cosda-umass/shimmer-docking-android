package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.stream.Collectors;

// Import DockingTimestampModel for timestamp support
import com.example.myapplication.DockingTimestampModel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.SystemClock;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ShimmerFileTransferClient {
    private static final String TAG = "ShimmerTransfer";
    private static final String FIREBASE_TAG = "FirebaseLogs";
    private static final String SYNC_TAG = "FileSync";
    private FirebaseAnalytics firebaseAnalytics;
    private FirebaseCrashlytics crashlytics;

    private final Context context;
    private BluetoothSocket socket = null;

    // Constructor
    public ShimmerFileTransferClient(Context ctx) {
        this.context = ctx.getApplicationContext();
        firebaseAnalytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
        crashlytics = FirebaseCrashlytics.getInstance();
    }

    // Command identifiers
    private static final byte LIST_FILES_COMMAND       = (byte) 0xD0;
    private static final byte FILE_LIST_RESPONSE       = (byte) 0xD3;
    private static final byte TRANSFER_FILE_COMMAND    = (byte) 0xD1;
    private static final byte READY_FOR_CHUNKS_COMMAND = (byte) 0xD2;
    private static final byte CHUNK_DATA_ACK           = (byte) 0xD4;
    private static final byte CHUNK_DATA_NACK          = (byte) 0xD5;
    private static final byte TRANSFER_START_PACKET    = (byte) 0xFD;
    private static final byte CHUNK_DATA_PACKET        = (byte) 0xFC;
    private static final byte TRANSFER_END_PACKET      = (byte) 0xFE;
    private static final byte CHECK_DOCK_STATUS        = (byte) 0xD5;
    private static final byte RESPONSE_DOCK_STATE      = (byte) 0xD6;
    private static final byte ACK_SUCCESS              = (byte) 0x01;
    private static final byte ACK_FAIL                 = (byte) 0x00;
    private static final byte TRANSFER_STATUS_SUCCESS  = (byte) 0x01;
    private static final byte FILE_TRANSFER_VERSION    = (byte) 0x01;

    // Configuration
    private static final int CHUNK_GROUP_SIZE = 16;
    private static final int MAX_FILENAME_LENGTH = 64;
    private static final int NUM_DOCK_STATUS_QUERIES = 3;
    private static final int DEVICE_CLOCK_RATE_HZ = 32768;
    private static final double DOCK_TIMESTAMP_TOLERANCE_S = 1.0;
    private static final int CRC_INIT = 0xB0CA;
    private static final int CRC_OFF = 0;
    private static final int CRC_1BYTE_ENABLED = 1;
    private static final int CRC_2BYTES_ENABLED = 2;
    private static final int RX_CRC_MODE = CRC_2BYTES_ENABLED;
    private static final int TX_CRC_MODE = CRC_OFF;
    private static final int DATA_CHUNK_CRC_MODE = CRC_OFF;

    // Overloaded transfer method with timestamp
    public void transferOneFileFullFlow(String macAddress, DockingTimestampModel timestampModel) {
        // Log the start of the file transfer
        Log.d(TAG, "Starting file transfer for MAC address: " + macAddress);
        Log.d("DockingManager", "Starting file transfer for MAC address: " + macAddress);
        Log.d(FIREBASE_TAG, "Logging file transfer start to Firebase for MAC address: " + macAddress);
        crashlytics.log("File transfer started for MAC address: " + macAddress);
        if (timestampModel != null) {
            Log.d(TAG, "Using DockingTimestampModel: shimmerRtc=" + timestampModel.shimmerRtc + ", androidRtc=" + timestampModel.androidRtc);
        }

        Bundle startBundle = new Bundle();
        startBundle.putString("mac_address", macAddress);
        firebaseAnalytics.logEvent("file_transfer_started", startBundle);

        boolean allFilesTransferred = false; // track overall success

        try {
            // --- STEP 1: Establish Bluetooth Connection ---
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Previous socket closed before starting new transfer");
                } catch (IOException e) {
                    Log.e(TAG, "Error closing previous socket", e);
                    crashlytics.recordException(e);
                }
                socket = null;
            }
            

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission. Aborting file transfer.");
                crashlytics.log("Missing BLUETOOTH_CONNECT permission. Aborting file transfer.");
                try {
                    Intent fail = new Intent("com.example.myapplication.TRANSFER_FAILED");
                    fail.setPackage(context.getPackageName());
                    fail.putExtra("reason", "missing_bluetooth_connect_permission");
                    context.sendBroadcast(fail);
                } catch (Exception ignored) {}
                return;
            }

            BluetoothDevice device = adapter.getRemoteDevice(macAddress);
            socket = device.createInsecureRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            adapter.cancelDiscovery();
            Thread.sleep(1000);

            boolean connected = false;
            for (int attempts = 1; attempts <= 3 && !connected; attempts++) {
                try {
                    socket.connect();
                    connected = true;
                } catch (IOException e) {
                    Log.e(TAG, "Socket connect attempt " + attempts + " failed.", e);
                    crashlytics.log("Socket connect attempt " + attempts + " failed");
                    crashlytics.recordException(e);
                    if (attempts < 3) Thread.sleep(1000);
                }
            }

            if (!connected) {
                Log.e(TAG, "Unable to connect to sensor after 3 retries");
                crashlytics.log("Unable to connect to sensor after 3 retries");
                // Centralized UI + retry handling
                // Update timer
                uiErrorAndRetry("Failed to connect to sensor. Retrying after 15:00", 60, "connect", macAddress);
                return;
            }
            Log.d(TAG, "Connected to Shimmer: " + macAddress);
            crashlytics.log("Connected to Shimmer");

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // --- STEP 2: Verify dock status on the active transfer socket ---
            DockStatusSample[] dockStatusSamples = queryDockStatusSamples(in, out);
            DockStatusSample lastDockStatus = dockStatusSamples[dockStatusSamples.length - 1];
            if (!validateDockResponseTimestamps(dockStatusSamples)) {
                Log.e(TAG, "Dock timestamp validation failed before file list request.");
                uiErrorAndRetry("Device timestamps are not correct, please try again later.", 60, "dock_timestamp_invalid", macAddress);
                return;
            }
            if (lastDockStatus.dockState != 1) {
                Log.w(TAG, "Shimmer is not docked before transfer. Last dockState=" + lastDockStatus.dockState);
                uiErrorAndRetry("Shimmer is not docked. Restarting after 1:00", 60, "not_docked", macAddress);
                return;
            }
            timestampModel = new DockingTimestampModel(
                    lastDockStatus.deviceTimestamp,
                    (int) (lastDockStatus.clientResponseMs / 1000L)
            );
            Log.d(TAG, "Using verified dock timestamp for header stamping: shimmerRtc=" +
                    timestampModel.shimmerRtc + ", androidRtc=" + timestampModel.androidRtc);

            // --- STEP 3: Request File Count ---
            writePacketWithCrc(out, new byte[]{LIST_FILES_COMMAND}, TX_CRC_MODE, "LIST_FILES_COMMAND");
            crashlytics.log("Sent LIST_FILES_COMMAND (D0)");

            byte[] fileListPacket = readFixedPacket(in, FILE_LIST_RESPONSE, 2, RX_CRC_MODE, "FILE_LIST_RESPONSE");
            int fileCount = fileListPacket[1] & 0xFF;
            Log.d(TAG, "FILE_LIST_RESPONSE: File count = " + fileCount);
            crashlytics.log("FILE_LIST_RESPONSE: File count = " + fileCount);

            // Log file count to Firebase Analytics
            Bundle fileCountBundle = new Bundle();
            fileCountBundle.putString("mac_address", macAddress);
            fileCountBundle.putInt("file_count", fileCount);
            firebaseAnalytics.logEvent("file_count_received", fileCountBundle);

            if (fileCount <= 0) {
                Log.e(TAG, "No files available for transfer");
                crashlytics.log("No files available for transfer");
                // Send TRANSFER_DONE broadcast even if no files
                Intent doneIntent = new Intent("com.example.myapplication.TRANSFER_DONE");
                doneIntent.setPackage(context.getPackageName());
                context.sendBroadcast(doneIntent);
                return;
            }

            // --- STEP 4: Transfer Each File ---
            // Before starting the file transfer loop
            Intent progressIntent = new Intent("com.example.myapplication.TRANSFER_PROGRESS");
            progressIntent.setPackage(context.getPackageName());
            progressIntent.putExtra("progress", 0);
            progressIntent.putExtra("total", fileCount);
            progressIntent.putExtra("filename", "");
            context.getApplicationContext().sendBroadcast(progressIntent);

            for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
                Log.d(TAG, "Processing file index: " + fileIndex);
                Log.d(FIREBASE_TAG, "Logging file processing start to Firebase for file index: " + fileIndex);
                crashlytics.log("Processing file index: " + fileIndex);

                // Log file processing start to Firebase Analytics
                Bundle fileStartBundle = new Bundle();
                fileStartBundle.putString("mac_address", macAddress);
                fileStartBundle.putInt("file_index", fileIndex);
                firebaseAnalytics.logEvent("file_processing_started", fileStartBundle);

                // Send parameter-less TRANSFER_FILE_COMMAND for the next queued Shimmer file
                writePacketWithCrc(out, new byte[]{TRANSFER_FILE_COMMAND}, TX_CRC_MODE, "TRANSFER_FILE_COMMAND");

                // Wait for TRANSFER_START_PACKET
                byte[] startPacket = readTransferStartPacket(in);
                int protocolVersion = startPacket[1] & 0xFF;
                int filenameLen = startPacket[2] & 0xFF;
                byte[] filenameBytes = new byte[filenameLen];
                System.arraycopy(startPacket, 3, filenameBytes, 0, filenameLen);
                String relativeFilename = new String(filenameBytes);
                // Minimal tag extraction from filename
                String experimentTag = null, shimmerIDTag = null;
                String[] filenameParts = relativeFilename.split("/");
                for (String part : filenameParts) {
                    if (part.startsWith("FullC_") || part.startsWith("TEST") || part.startsWith("Test")) experimentTag = part;
                    if (part.startsWith("Shimmer_")) shimmerIDTag = part;
                }
                java.util.Map<String, String> tags = new java.util.HashMap<>();
                if (experimentTag != null) tags.put("experiment", experimentTag);
                if (shimmerIDTag != null) tags.put("shimmerID", shimmerIDTag);
                Log.d(SYNC_TAG, "EXTRACTED TAGS FROM FILENAME: " + tags);
                int metadataOffset = 3 + filenameLen;
                byte[] totalSizeBytes = new byte[]{
                        startPacket[metadataOffset],
                        startPacket[metadataOffset + 1],
                        startPacket[metadataOffset + 2],
                        startPacket[metadataOffset + 3]
                };
                int totalFileSize = ((totalSizeBytes[3] & 0xFF) << 24) |
                        ((totalSizeBytes[2] & 0xFF) << 16) |
                        ((totalSizeBytes[1] & 0xFF) << 8) |
                        (totalSizeBytes[0] & 0xFF);
                byte[] chunkSizeBytes = new byte[]{startPacket[metadataOffset + 4], startPacket[metadataOffset + 5]};
                int chunkSize = ((chunkSizeBytes[1] & 0xFF) << 8) | (chunkSizeBytes[0] & 0xFF);
                byte[] totalChunksBytes = new byte[]{startPacket[metadataOffset + 6], startPacket[metadataOffset + 7]};
                int totalChunks = ((totalChunksBytes[1] & 0xFF) << 8) | (totalChunksBytes[0] & 0xFF);

                Log.d(TAG, "TRANSFER_START_PACKET: version=" + protocolVersion
                        + ", filename=" + relativeFilename
                        + ", totalSize=" + totalFileSize
                        + ", chunkSize=" + chunkSize
                        + ", totalChunks=" + totalChunks);

                // Log metadata to Firebase Analytics
                Bundle fileMetadataBundle = new Bundle();
                fileMetadataBundle.putString("mac_address", macAddress);
                fileMetadataBundle.putString("file_name", relativeFilename);
                fileMetadataBundle.putInt("file_size", totalFileSize);
                fileMetadataBundle.putInt("chunk_size", chunkSize);
                fileMetadataBundle.putInt("total_chunks", totalChunks);
                firebaseAnalytics.logEvent("file_metadata_received", fileMetadataBundle);

                // Log metadata to Crashlytics
                crashlytics.setCustomKey("file_name", relativeFilename);
                crashlytics.setCustomKey("file_size", totalFileSize);
                crashlytics.setCustomKey("chunk_size", chunkSize);
                crashlytics.setCustomKey("total_chunks", totalChunks);

                // Send READY_FOR_CHUNKS_COMMAND
                writePacketWithCrc(out, new byte[]{READY_FOR_CHUNKS_COMMAND}, TX_CRC_MODE, "READY_FOR_CHUNKS_COMMAND");

                // Receive file chunks
                // Get username and timestamp ONCE per file
                String phoneMac = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (phoneMac == null || phoneMac.isEmpty()) phoneMac = "user";
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                String baseName = new File(relativeFilename).getName();
                // Output filename: <phoneMac>__<timestamp>__<experimentName>__<shimmerID>__<baseName>.txt
                String experimentName = experimentTag != null ? experimentTag : "";
                String shimmerID = shimmerIDTag != null ? shimmerIDTag : "";

               

                String newFilename = phoneMac + "__" + timestamp + "__" + experimentName + "__" + shimmerID + "__" + baseName;
                if(shimmerID.replaceAll(".*_(\\w{4})-.*", "$1").
                                        matches(macAddress.replace(":", "").substring(macAddress.replace(":", "").length() - 4))) { // id = "E169"
                    Log.d(TAG, "Shimmer ID matches MAC address suffix: " + shimmerID + macAddress);
                    newFilename +=  ".txt";
                }
                else {
                    Log.w(TAG, "Shimmer ID does NOT match MAC address suffix.");
                    newFilename += "__wrong.txt";

                }
                
                File dataDir = new File(context.getFilesDir(), "data");
                if (!dataDir.exists()) dataDir.mkdirs();
                File outputFile = new File(dataDir, newFilename);

                // Create the file
                File debugFile = new File(context.getFilesDir(), "debug_log.txt");
                boolean transferSuccess = false; // Track transfer status

                // Write timestamp header if available
                try (java.io.FileOutputStream binaryWriter = new java.io.FileOutputStream(outputFile);
                     FileWriter debugWriter = new FileWriter(debugFile, true)) {

                    Log.d(TAG, "File created successfully: " + outputFile.getAbsolutePath());
                    Log.d(TAG, "Receiving chunks...");

                    int chunksProcessed = 0;

                    while (chunksProcessed < totalChunks) {
                        int groupStartChunk = chunksProcessed;
                        int remainingChunks = totalChunks - chunksProcessed;
                        int chunksToRead = Math.min(CHUNK_GROUP_SIZE, remainingChunks);
                        byte[][] groupDataBuffer = new byte[chunksToRead][];

                        // Log if this is the last chunk group
                        if (remainingChunks <= CHUNK_GROUP_SIZE) {
                            Log.d(TAG, "Processing the last chunk group. Remaining chunks: " + remainingChunks);
                        }

                        boolean chunksAreValid = true; // Flag to track if the current group is valid

                        for (int i = 0; i < chunksToRead; i++) {
                            ChunkPacket chunkPacket = readChunkPacket(in, chunkSize);
                            int chunkSizeForThisChunk = chunkPacket.payload.length;

                            Log.d(TAG, "Chunk received: chunkNum=" + chunkPacket.chunkNumber +
                                    ", expected=" + (groupStartChunk + i) +
                                    ", groupStart=" + groupStartChunk +
                                    ", chunkSize=" + chunkSizeForThisChunk +
                                    ", crcOk=" + chunkPacket.crcOk);

                            if (!chunkPacket.crcOk || chunkPacket.chunkNumber != groupStartChunk + i) {
                                chunksAreValid = false;
                                Log.w(TAG, "Invalid chunk in group. chunkNum=" + chunkPacket.chunkNumber +
                                        ", expected=" + (groupStartChunk + i) +
                                        ", crcOk=" + chunkPacket.crcOk +
                                        ". Group will be NACKed from " + groupStartChunk);
                            } else {
                                groupDataBuffer[i] = chunkPacket.payload;
                            }

                            // Write raw hexadecimal data to the debug file with header
                            StringBuilder hexLine = new StringBuilder();
                            hexLine.append(String.format("%02X ", CHUNK_DATA_PACKET)); // Add header (starting with FC)
                            hexLine.append(String.format("%02X %02X ", chunkPacket.chunkNumLsb, chunkPacket.chunkNumMsb)); // Add chunk number
                            hexLine.append(String.format("%02X %02X ", chunkPacket.payloadSizeLsb, chunkPacket.payloadSizeMsb)); // Add total bytes
                            for (byte b : chunkPacket.payload) {
                                hexLine.append(String.format("%02X ", b)); // Add chunk data
                            }
                            debugWriter.write(hexLine.toString().trim() + "\n");
                            
                        }

                        if (chunksAreValid) {
                            for (int i = 0; i < chunksToRead; i++) {
                                binaryWriter.write(groupDataBuffer[i]);
                            }
                            binaryWriter.flush();
                            chunksProcessed += chunksToRead;
                        }

                        sendGroupAck(out, groupStartChunk, chunksAreValid);
                        Log.d(TAG, "Sent GROUP " + (chunksAreValid ? "ACK" : "NACK") +
                                " for groupStart=" + groupStartChunk +
                                ", chunksInGroup=" + chunksToRead +
                                ", chunksProcessed=" + chunksProcessed + "/" + totalChunks);

                        // Log progress to Firebase
                        Bundle progressBundle = new Bundle();
                        progressBundle.putString("mac_address", macAddress);
                        progressBundle.putInt("chunks_processed", chunksProcessed);
                        progressBundle.putInt("total_chunks", totalChunks);
                        firebaseAnalytics.logEvent("file_transfer_progress", progressBundle);

                        if (!chunksAreValid) {
                            Log.w(TAG, "Group NACK sent. Waiting for Shimmer to retransmit group starting at " + groupStartChunk);
                        }
                    }
                    if (chunksProcessed >= totalChunks) {
                        Log.d(TAG, "Last chunk group processed. Waiting for CRC-valid TRANSFER_END_PACKET...");
                        byte[] transferEndPacket = readFixedPacket(in, TRANSFER_END_PACKET, 2, RX_CRC_MODE, "TRANSFER_END_PACKET");
                        int transferStatus = transferEndPacket[1] & 0xFF;
                        Log.d(TAG, "Received TRANSFER_END_PACKET with status: " + String.format("%02X", transferStatus));
                        if (transferStatus == (TRANSFER_STATUS_SUCCESS & 0xFF)) {
                            transferSuccess = true;
                        } else {
                            transferSuccess = false;
                            Log.w(TAG, "Transfer end reported failure status: " + String.format("0x%02X", transferStatus));
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "!!! CHUNK-LEVEL IOException. Hard failure during active file writing.");
                    Log.e(TAG, "Error during file transfer: " + e.getMessage(), e);
                    crashlytics.recordException(e);

                    uiErrorAndRetry("Bluetooth disconnected. Restarting after 1:00", 60, "io", macAddress);

                    // Log available bytes in the input stream before aborting (best-effort)
                    try {
                        int availableBytes = in.available();
                        try (FileWriter debugWriter = new FileWriter(debugFile, true)) {
                            debugWriter.write("Available bytes in stream before aborting: " + availableBytes + "\n");
                            byte[] debugBytes = new byte[availableBytes];
                            int bytesRead = in.read(debugBytes);
                            if (bytesRead > 0) {
                                StringBuilder debugData = new StringBuilder("Bytes in stream: ");
                                for (byte b : debugBytes) {
                                    debugData.append(String.format("%02X ", b));
                                }
                                debugWriter.write(debugData.toString().trim() + "\n");
                            }
                        }
                    } catch (IOException streamError) {
                        Log.e(TAG, "Error reading available bytes: " + streamError.getMessage(), streamError);
                    }

                    return;
                } finally {
                    // Delete incomplete file if transfer was not successful
                    if (!transferSuccess && outputFile.exists()) {
                        Log.w(TAG, "Deleting incomplete file: " + outputFile.getAbsolutePath());
                        outputFile.delete();
                    }
                }

                // Only record in DB if the file completed successfully
                if (transferSuccess) {
                    // Log timestamp header after file transfer is complete
                    if (timestampModel != null) {
                        Log.d(TAG, "[FileWrite-END] File transfer complete for " + macAddress + ": shimmerRtc64=" + timestampModel.shimmerRtc + ", androidRtc32=" + timestampModel.androidRtc);
                        // Update RTC and config time in file header
                        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputFile, "rw")) {
                            // Write shimmerRtc (uint64) to bytes 44-51
                            raf.seek(44);
                            long rtc = timestampModel.shimmerRtc;
                            for (int i = 0; i < 8; i++) {
                                raf.write((int) ((rtc >> (8 * i)) & 0xFF));
                            }
                            // Write androidRtc (uint32) to bytes 52-55
                            raf.seek(52);
                            int configTime = (int) timestampModel.androidRtc;
                            for (int i = 0; i < 4; i++) {
                                raf.write((configTime >> (8 * i)) & 0xFF);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating RTC/config time in file header", e);
                        }
                    } else {
                        Log.d(TAG, "[FileWrite-END] File transfer complete for " + macAddress + ", no timestamp provided.");
                    }
                    Log.d(TAG,"Added file to DB: " + outputFile.getAbsolutePath());

                    FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put("TIMESTAMP", timestamp);
                    values.put("FILE_PATH", outputFile.getAbsolutePath());
                    values.put("SYNCED", 0);
                    db.insert("files", null, values);
                    db.close();
                }

                progressIntent = new Intent("com.example.myapplication.TRANSFER_PROGRESS");
                progressIntent.setPackage(context.getPackageName());
                progressIntent.putExtra("progress", fileIndex + 1);
                progressIntent.putExtra("total", fileCount);
                progressIntent.putExtra("filename", newFilename);
                context.getApplicationContext().sendBroadcast(progressIntent);
            }
            // If we completed the loop without returning, mark overall success
            allFilesTransferred = true;

        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "!!! TOP-LEVEL IOException. Hard failure outside the file writing loop.");
            Log.e(TAG, "Error during file transfer: " + e.getMessage(), e);
            crashlytics.log("Error during file transfer: " + e.getMessage());
            crashlytics.recordException(e);

            Bundle transferErrorBundle = new Bundle();
            transferErrorBundle.putString("mac_address", macAddress);
            transferErrorBundle.putString("error_message", e.getMessage());
            firebaseAnalytics.logEvent("file_transfer_error", transferErrorBundle);

            uiErrorAndRetry(e.getMessage(), 5, "top_level", macAddress);
        } finally {
            // Close socket safely
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Socket closed after file transfer operation");
                } catch (IOException ignored) {
                    crashlytics.log("Error closing socket after file transfer");
                }
                socket = null;
            }

            // Only broadcast TRANSFER_DONE and upload to S3 if everything actually succeeded
            if (allFilesTransferred) {
                Intent doneIntent = new Intent("com.example.myapplication.TRANSFER_DONE");
                doneIntent.setPackage(context.getPackageName());
                context.sendBroadcast(doneIntent);
            }
        }
    }

    private byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buffer = new byte[len];
        int totalRead = 0;
        while (totalRead < len) {
            // Abort quickly if Bluetooth was turned off mid-transfer
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null && !ba.isEnabled()) {
                throw new IOException("Bluetooth disabled");
            }

            int read = in.read(buffer, totalRead, len - totalRead);
            if (read == -1) {
                throw new EOFException("Stream ended unexpectedly. Needed " + len + " bytes, but only got " + totalRead);
            }
            totalRead += read;
        }
        return buffer;
    }

    private DockStatusSample[] queryDockStatusSamples(InputStream in, OutputStream out) throws IOException, InterruptedException {
        DockStatusSample[] samples = new DockStatusSample[NUM_DOCK_STATUS_QUERIES];
        Log.d(TAG, "Checking Shimmer dock status " + NUM_DOCK_STATUS_QUERIES + " times before file list request.");
        for (int i = 0; i < NUM_DOCK_STATUS_QUERIES; i++) {
            long requestAtMs = System.currentTimeMillis();
            Log.d(TAG, "Sending CHECK_DOCK_STATUS query " + (i + 1) + "/" + NUM_DOCK_STATUS_QUERIES);
            writePacketWithCrc(out, new byte[]{CHECK_DOCK_STATUS}, TX_CRC_MODE, "CHECK_DOCK_STATUS");

            byte[] response = readFixedPacket(in, RESPONSE_DOCK_STATE, 10, RX_CRC_MODE, "RESPONSE_DOCK_STATE");
            long responseAtMs = System.currentTimeMillis();
            int dockState = response[1] & 0xFF;
            long deviceTimestamp = leUint64(response, 2);
            double roundTripS = (responseAtMs - requestAtMs) / 1000.0;
            samples[i] = new DockStatusSample(dockState, deviceTimestamp, responseAtMs, roundTripS);
            Log.d(TAG, "Dock status query " + (i + 1) + "/" + NUM_DOCK_STATUS_QUERIES +
                    ": dockState=" + dockState +
                    ", deviceTimestamp=" + deviceTimestamp +
                    ", roundTripS=" + String.format(java.util.Locale.US, "%.3f", roundTripS));

            if (i < NUM_DOCK_STATUS_QUERIES - 1) {
                Thread.sleep(3000);
            }
        }
        return samples;
    }

    private boolean validateDockResponseTimestamps(DockStatusSample[] samples) {
        if (samples == null || samples.length < 3) {
            Log.e(TAG, "Dock timestamp validation requires at least 3 samples.");
            return false;
        }

        boolean pass = true;
        for (int i = 0; i < 2; i++) {
            double localDeltaS = (samples[i + 1].clientResponseMs - samples[i].clientResponseMs) / 1000.0;
            double deviceDeltaS = (samples[i + 1].deviceTimestamp - samples[i].deviceTimestamp) / (double) DEVICE_CLOCK_RATE_HZ;
            double errorS = Math.abs(localDeltaS - deviceDeltaS);
            boolean pairPass = errorS <= DOCK_TIMESTAMP_TOLERANCE_S;
            Log.d(TAG, "Dock timestamp pair " + (i + 1) + "->" + (i + 2) +
                    ": localDeltaS=" + String.format(java.util.Locale.US, "%.3f", localDeltaS) +
                    ", deviceDeltaS=" + String.format(java.util.Locale.US, "%.3f", deviceDeltaS) +
                    ", errorS=" + String.format(java.util.Locale.US, "%.3f", errorS) +
                    ", pass=" + pairPass);
            pass = pass && pairPass;
        }
        Log.d(TAG, "Dock timestamp validation result: " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    private byte[] readFixedPacket(InputStream in, byte expectedId, int payloadLen, int crcMode, String label) throws IOException {
        int packetId = readProtocolByte(in);
        if (packetId != (expectedId & 0xFF)) {
            throw new IOException(label + " expected " + hex(expectedId) + " but got " + String.format("0x%02X", packetId));
        }

        byte[] payload = new byte[payloadLen];
        payload[0] = (byte) packetId;
        if (payloadLen > 1) {
            byte[] rest = readExact(in, payloadLen - 1);
            System.arraycopy(rest, 0, payload, 1, rest.length);
        }

        byte[] crcBytes = readExact(in, crcNumBytes(crcMode));
        if (!checkCrc(crcMode, payload, crcBytes)) {
            Log.e(TAG, label + " CRC failed. payload=" + bytesToHex(payload) + ", crc=" + bytesToHex(crcBytes));
            throw new IOException(label + " CRC validation failed");
        }

        Log.d(TAG, label + " received with CRC OK. payloadLen=" + payloadLen + ", crcMode=" + crcMode);
        return payload;
    }

    private byte[] readTransferStartPacket(InputStream in) throws IOException {
        int packetId = readProtocolByte(in);
        if (packetId != (TRANSFER_START_PACKET & 0xFF)) {
            throw new IOException("TRANSFER_START_PACKET expected " + hex(TRANSFER_START_PACKET) + " but got " + String.format("0x%02X", packetId));
        }

        byte[] header = readExact(in, 2);
        int version = header[0] & 0xFF;
        int nameLen = header[1] & 0xFF;
        if (nameLen == 0 || nameLen >= MAX_FILENAME_LENGTH) {
            throw new IOException("Invalid filename length in TRANSFER_START_PACKET: " + nameLen);
        }

        int payloadLen = 1 + 1 + 1 + nameLen + 4 + 2 + 2;
        byte[] payload = new byte[payloadLen];
        payload[0] = TRANSFER_START_PACKET;
        payload[1] = header[0];
        payload[2] = header[1];
        byte[] rest = readExact(in, nameLen + 4 + 2 + 2);
        System.arraycopy(rest, 0, payload, 3, rest.length);

        byte[] crcBytes = readExact(in, crcNumBytes(RX_CRC_MODE));
        if (!checkCrc(RX_CRC_MODE, payload, crcBytes)) {
            Log.e(TAG, "TRANSFER_START_PACKET CRC failed. payload=" + bytesToHex(payload) + ", crc=" + bytesToHex(crcBytes));
            throw new IOException("TRANSFER_START_PACKET CRC validation failed");
        }
        if (version != (FILE_TRANSFER_VERSION & 0xFF)) {
            throw new IOException("Unsupported file transfer version: " + version);
        }

        Log.d(TAG, "TRANSFER_START_PACKET received with CRC OK. nameLen=" + nameLen + ", version=" + version);
        return payload;
    }

    private ChunkPacket readChunkPacket(InputStream in, int maxChunkSize) throws IOException {
        int packetId = readProtocolByte(in);
        if (packetId != (CHUNK_DATA_PACKET & 0xFF)) {
            throw new IOException("CHUNK_DATA_PACKET expected " + hex(CHUNK_DATA_PACKET) + " but got " + String.format("0x%02X", packetId));
        }

        byte[] header = readExact(in, 4);
        int chunkNumber = ((header[1] & 0xFF) << 8) | (header[0] & 0xFF);
        int payloadSize = ((header[3] & 0xFF) << 8) | (header[2] & 0xFF);
        if (maxChunkSize > 0 && payloadSize > maxChunkSize) {
            throw new IOException("Invalid chunk payload size " + payloadSize + ", max expected " + maxChunkSize);
        }

        byte[] chunkData = readExact(in, payloadSize);
        byte[] payload = new byte[1 + header.length + chunkData.length];
        payload[0] = CHUNK_DATA_PACKET;
        System.arraycopy(header, 0, payload, 1, header.length);
        System.arraycopy(chunkData, 0, payload, 1 + header.length, chunkData.length);
        byte[] crcBytes = readExact(in, crcNumBytes(DATA_CHUNK_CRC_MODE));
        boolean crcOk = checkCrc(DATA_CHUNK_CRC_MODE, payload, crcBytes);
        if (!crcOk) {
            Log.w(TAG, "CHUNK_DATA_PACKET CRC failed for chunk " + chunkNumber +
                    ". payloadLen=" + payload.length + ", crc=" + bytesToHex(crcBytes));
        }

        return new ChunkPacket(chunkNumber, header[0], header[1], header[2], header[3], chunkData, crcOk);
    }

    private int readProtocolByte(InputStream in) throws IOException {
        int packetId;
        do {
            packetId = in.read();
            if (packetId == -1) {
                throw new EOFException("Stream ended before protocol packet ID");
            }
        } while (packetId == 0xFF || packetId == 0x00);
        return packetId;
    }

    private void writePacketWithCrc(OutputStream out, byte[] packet, int crcMode, String label) throws IOException {
        byte[] packetWithCrc = appendCrc(packet, crcMode);
        out.write(packetWithCrc);
        out.flush();
        Log.d(TAG, "Sent " + label + " " + bytesToHex(packetWithCrc) + " (crcMode=" + crcMode + ")");
    }

    private void sendGroupAck(OutputStream out, int groupStartChunk, boolean success) throws IOException {
        byte[] ackPacket = new byte[]{
                CHUNK_DATA_ACK,
                (byte) (groupStartChunk & 0xFF),
                (byte) ((groupStartChunk >> 8) & 0xFF),
                success ? ACK_SUCCESS : ACK_FAIL
        };
        writePacketWithCrc(out, ackPacket, TX_CRC_MODE, success ? "GROUP_ACK" : "GROUP_NACK");
    }

    private byte[] appendCrc(byte[] payload, int crcMode) {
        int crcBytes = crcNumBytes(crcMode);
        byte[] packet = new byte[payload.length + crcBytes];
        System.arraycopy(payload, 0, packet, 0, payload.length);
        if (crcBytes == 0) return packet;

        int crc = crcData(payload, payload.length);
        packet[payload.length] = (byte) (crc & 0xFF);
        if (crcBytes == 2) {
            packet[payload.length + 1] = (byte) ((crc >> 8) & 0xFF);
        }
        return packet;
    }

    private boolean checkCrc(int crcMode, byte[] payload, byte[] crcBytes) {
        int expectedBytes = crcNumBytes(crcMode);
        if (expectedBytes == 0) return true;
        if (crcBytes == null || crcBytes.length < expectedBytes) return false;

        int crc = crcData(payload, payload.length);
        if ((byte) (crc & 0xFF) != crcBytes[0]) return false;
        return expectedBytes != 2 || (byte) ((crc >> 8) & 0xFF) == crcBytes[1];
    }

    private int crcData(byte[] buffer, int len) {
        int crc = CRC_INIT & 0xFFFF;
        int paddedLen = (len % 2 == 0) ? len : len + 1;
        for (int i = 0; i < paddedLen; i++) {
            int value = i < len ? (buffer[i] & 0xFF) : 0;
            crc ^= (value << 8);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private int crcNumBytes(int crcMode) {
        if (crcMode == CRC_OFF) return 0;
        if (crcMode == CRC_1BYTE_ENABLED) return 1;
        if (crcMode == CRC_2BYTES_ENABLED) return 2;
        throw new IllegalArgumentException("Unsupported CRC mode: " + crcMode);
    }

    private long leUint64(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    private String hex(byte value) {
        return String.format("0x%02X", value);
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static class DockStatusSample {
        final int dockState;
        final long deviceTimestamp;
        final long clientResponseMs;
        final double roundTripS;

        DockStatusSample(int dockState, long deviceTimestamp, long clientResponseMs, double roundTripS) {
            this.dockState = dockState;
            this.deviceTimestamp = deviceTimestamp;
            this.clientResponseMs = clientResponseMs;
            this.roundTripS = roundTripS;
        }
    }

    private static class ChunkPacket {
        final int chunkNumber;
        final byte chunkNumLsb;
        final byte chunkNumMsb;
        final byte payloadSizeLsb;
        final byte payloadSizeMsb;
        final byte[] payload;
        final boolean crcOk;

        ChunkPacket(int chunkNumber, byte chunkNumLsb, byte chunkNumMsb, byte payloadSizeLsb, byte payloadSizeMsb, byte[] payload, boolean crcOk) {
            this.chunkNumber = chunkNumber;
            this.chunkNumLsb = chunkNumLsb;
            this.chunkNumMsb = chunkNumMsb;
            this.payloadSizeLsb = payloadSizeLsb;
            this.payloadSizeMsb = payloadSizeMsb;
            this.payload = payload;
            this.crcOk = crcOk;
        }
    }

    // Overloaded transfer method with timestamp
    public void transfer(String macAddress, DockingTimestampModel timestampModel) {
        transferOneFileFullFlow(macAddress, timestampModel);
    }

    // Original transfer method for backward compatibility
    public void transfer(String macAddress) {
        transferOneFileFullFlow(macAddress, null);
    }


    public List<File> getLocalUnsyncedFiles() {
        Log.d(SYNC_TAG, "Querying local DB for unsynced files...");
        FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<File> unsyncedFiles = new ArrayList<>();
        try (android.database.Cursor cursor = db.query("files", new String[]{"FILE_PATH"}, "SYNCED=0", null, null, null, null)) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                File file = new File(path);
                if (file.exists()) {
                    unsyncedFiles.add(file);
                    Log.d(SYNC_TAG, " Unsynced file: " + file.getName());
                }
                else {
                    Log.w(SYNC_TAG, "File listed in DB but does not exist on disk: " + path);
                    db.delete("files", "FILE_PATH=?", new String[]{path});
                }
            }
        }
        db.close();
        return unsyncedFiles;
    }

    public List<String> getMissingFilesOnS3(List<File> localFiles) {
        Log.d(SYNC_TAG, "Checking which of " + localFiles.size() + " local files are missing on S3...");
        List<String> missing = new ArrayList<>();
        if (localFiles.isEmpty()) return missing;

        try {
            OkHttpClient client = new OkHttpClient();
            JSONArray filenames = new JSONArray();
            for (File file : localFiles) filenames.put(file.getName());

            RequestBody body = RequestBody.create(filenames.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url("https://odb777ddnc.execute-api.us-east-2.amazonaws.com/missing-files/")
                    .post(body)
                    .build();

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(SYNC_TAG, "Error checking missing files, server responded with: " + response.code());
                return localFiles.stream().map(File::getName).collect(Collectors.toList());
            }
            JSONObject result = new JSONObject(response.body().string());
              JSONArray missingArr = result.getJSONArray("missing_files");
            for (int i = 0; i < missingArr.length(); i++) {
                missing.add(missingArr.getString(i));
            }
            Log.d(SYNC_TAG, "Found " + missing.size() + " files missing on S3.");
        } catch (Exception e) {
            Log.e(SYNC_TAG, "Error checking missing files: " + e.getMessage());
            // Do not Toast from background; signal by returning all files as missing
            // This prevents them from being incorrectly marked as synced.
            return localFiles.stream().map(File::getName).collect(Collectors.toList());
        }
        return missing;
    }

    private void notifyBackendDecodeAndStore(String fileName) {
        OkHttpClient client = new OkHttpClient();
        try {
            JSONObject body = new JSONObject();
            body.put("full_file_name", fileName);
            RequestBody reqBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url("https://odb777ddnc.execute-api.us-east-2.amazonaws.com/decode-and-store/")
                    .post(reqBody)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    Log.d(SYNC_TAG, "Metadata decode/store successful for: " + fileName);
                } else {
                    Log.e(SYNC_TAG, "Metadata decode/store failed for: " + fileName + " code: " + response.code());
                }
            }
        } catch (Exception e) {
            Log.e(SYNC_TAG, "Exception during metadata decode/store: " + e.getMessage(), e);
        }
    }


    public boolean uploadFileToS3(File file) {
        Log.d(SYNC_TAG, "Starting S3 upload for: " + file.getName());
        Log.d(SYNC_TAG, "File sync TRIGGERED from uploadFileToS3 for: " + file.getAbsolutePath());
        OkHttpClient client = new OkHttpClient();
        try {
            Request getUrlRequest = new Request.Builder()
                    .url("https://odb777ddnc.execute-api.us-east-2.amazonaws.com/generate-upload-url/?filename=" + file.getName())
                    .get()
                    .build();

            String uploadUrl;
            try (Response getUrlResponse = client.newCall(getUrlRequest).execute()) {
                if (!getUrlResponse.isSuccessful() || getUrlResponse.body() == null) {
                    Log.e(SYNC_TAG, "Failed to get pre-signed URL. Server responded with: " + getUrlResponse.code());
                    return false;
                }
                uploadUrl = new JSONObject(getUrlResponse.body().string()).getString("upload_url");
            }

            RequestBody fileBody = RequestBody.create(file, MediaType.parse("text/plain"));
            Request uploadRequest = new Request.Builder().url(uploadUrl).put(fileBody).build();

            try (Response uploadResponse = client.newCall(uploadRequest).execute()) {
                if (uploadResponse.isSuccessful()) {
                    Log.d(SYNC_TAG, "S3 upload successful for: " + file.getName());
                    // NEW: Notify backend to decode and store metadata
                    notifyBackendDecodeAndStore(file.getName());
                    return true;
                } else {
                    Log.e(SYNC_TAG, "S3 upload failed with code: " + uploadResponse.code());
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(SYNC_TAG, "Exception during S3 upload: " + e.getMessage(), e);
            crashlytics.recordException(e);
            // Do not Toast from background thread
            return false;
        }
    }

    public void markFileAsSynced(File file) {
        Log.d(SYNC_TAG, "Marking file as synced in DB: " + file.getName());
        FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("SYNCED", 1);
        db.update("files", values, "FILE_PATH=?", new String[]{file.getAbsolutePath()});
        db.close();
    }

    /**
     * Clears transfer progress from SharedPreferences and sends a broadcast to update the UI.
     * @param message The error message to display.
     * @param retrySeconds The number of seconds until a retry will be attempted.
     */
    private void clearTransferProgressStateAndNotifyUI(String message, int retrySeconds) {
        Log.d(TAG, "[CLEAR_STATE] Clearing transfer progress state (reason: " + message + ")");
        SharedPreferences prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE);
        prefs.edit()
            .remove("transfer_progress")
            .remove("transfer_total")
            .remove("transfer_filename")
            .remove("progress_visibility")
            .apply();

        Intent intent = new Intent("com.example.myapplication.TRANSFER_ERROR");
        intent.setPackage(context.getPackageName());
        intent.putExtra("error_message", message);
        intent.putExtra("retry_seconds", retrySeconds);
        context.getApplicationContext().sendBroadcast(intent);
    }

    // Central helper: reflect UI error, schedule retry, and broadcast failure reason
    private void uiErrorAndRetry(String message, int retrySeconds, String reason, String macAddress) {
    clearTransferProgressStateAndNotifyUI(message, retrySeconds);
        try {
            Intent fail = new Intent("com.example.myapplication.TRANSFER_FAILED");
            fail.setPackage(context.getPackageName());
            if (reason != null) fail.putExtra("reason", reason);
            context.sendBroadcast(fail);
        } catch (Exception ignored) {}
    }

    // Persist transfer state so UI can restore after app reopen
    private void persistTransferState(String status, String error, int retrySeconds, String mac) {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = prefs.edit();
            if (status != null) ed.putString("transfer_status", status); // running | failed | success | idle
            if (error != null) ed.putString("transfer_error", error); else ed.remove("transfer_error");
            ed.putInt("transfer_retry_sec", Math.max(0, retrySeconds));
            if (mac != null) ed.putString("transfer_mac", mac);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    // Example wrapper where chunks are written; adapt to your real write loop
    private void writeChunkToFile(File tempOutFile, byte[] data, int len) throws IOException {
        // ...existing code that writes 'len' bytes to 'tempOutFile'...
    }

    // Call this on any IO/disconnect error inside your transfer loop
    private void handleTransferError(File tempOutFile, String reason, Exception e) {
        Log.e(TAG, "Transfer failed: " + reason, e);
        safelyMarkPartial(tempOutFile);
        broadcastFailure(reason);
    }

    private void safelyMarkPartial(File tempOutFile) {
        if (tempOutFile == null) return;
        try {
            if (tempOutFile.exists()) {
                File partial = new File(tempOutFile.getParentFile(), tempOutFile.getName() + ".partial");
                // Rename temp file to .partial to inspect/debug later; do not delete silently
                boolean ok = tempOutFile.renameTo(partial);
                if (!ok) {
                    // Fallback: keep as-is, but do not delete
                    Log.w("ShimmerTransfer", "Failed to rename temp to .partial; leaving temp file.");
                }
            }
        } catch (Exception ex) {
            Log.w("ShimmerTransfer", "Partial file handling failed", ex);
        }
    }

    private void broadcastFailure(String reason) {
        Log.e(TAG,  "Broadcasting failure: " + reason);
        Intent i = new Intent(DockingService.ACTION_TRANSFER_FAILED);
        i.setPackage(context.getPackageName());
        i.putExtra("reason", reason);
        context.sendBroadcast(i);
    }

    public void forceStop() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }
}