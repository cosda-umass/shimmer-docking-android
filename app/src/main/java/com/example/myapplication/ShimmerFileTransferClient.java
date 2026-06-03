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

    // Configuration
    private static final int CHUNK_GROUP_SIZE = 16;

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

            // --- STEP 2: Request File Count ---
            out.write(new byte[]{LIST_FILES_COMMAND});
            out.flush();
            Log.d(TAG, "Sent LIST_FILES_COMMAND (D0)");
            crashlytics.log("Sent LIST_FILES_COMMAND (D0)");

            int responseId = in.read();
            while (responseId == 0xFF) {
                responseId = in.read();
            }
            if (responseId != (FILE_LIST_RESPONSE & 0xFF)) {
                Log.e(TAG, "Expected FILE_LIST_RESPONSE (D3) but got: " + String.format("%02X", responseId));
                crashlytics.log("Expected FILE_LIST_RESPONSE (D3) but got: " + String.format("%02X", responseId));

                uiErrorAndRetry("Unexpected header, restarting after 1:00", 60, "unexpected_header", macAddress);
                return;
            }
            int fileCount = in.read() & 0xFF;
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

            // --- STEP 3: Transfer Each File ---
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

                // Send TRANSFER_FILE_COMMAND
                out.write(new byte[]{TRANSFER_FILE_COMMAND});
                out.flush();
                Log.d(TAG, "Sent TRANSFER_FILE_COMMAND (0xD1)");

                // Wait for TRANSFER_START_PACKET
                int startByte = in.read();
                while (startByte == 0xFF) {
                    startByte = in.read();
                }
                if (startByte != (TRANSFER_START_PACKET & 0xFF)) {
                    Log.e(TAG, "Expected TRANSFER_START_PACKET (FD) but got: " + String.format("%02X", startByte));
                    uiErrorAndRetry("Device disconnected or unexpected start. Restarting after 1:00", 60, "unexpected_start", macAddress);
                    return; // abort this session; do NOT send TRANSFER_DONE
                }

                // Extract metadata
                int protocolVersion = in.read();
                int filenameLen = in.read();
                byte[] filenameBytes = readExact(in, filenameLen);
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
                byte[] totalSizeBytes = readExact(in, 4);
                int totalFileSize = ((totalSizeBytes[3] & 0xFF) << 24) |
                        ((totalSizeBytes[2] & 0xFF) << 16) |
                        ((totalSizeBytes[1] & 0xFF) << 8) |
                        (totalSizeBytes[0] & 0xFF);
                byte[] chunkSizeBytes = readExact(in, 2);
                int chunkSize = ((chunkSizeBytes[1] & 0xFF) << 8) | (chunkSizeBytes[0] & 0xFF);
                byte[] totalChunksBytes = readExact(in, 2);
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
                out.write(new byte[]{READY_FOR_CHUNKS_COMMAND});
                out.flush();
                Log.d(TAG, "Sent READY_FOR_CHUNKS_COMMAND (0xD2)");

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
                    byte[] firstChunkNumBytes = new byte[2]; // Store the first chunk number of the group

                    while (chunksProcessed < totalChunks) {
                        int remainingChunks = totalChunks - chunksProcessed;
                        int chunksToRead = Math.min(CHUNK_GROUP_SIZE, remainingChunks);

                        // Log if this is the last chunk group
                        if (remainingChunks <= CHUNK_GROUP_SIZE) {
                            Log.d(TAG, "Processing the last chunk group. Remaining chunks: " + remainingChunks);
                        }

                        boolean chunksAreValid = true; // Flag to track if chunks are valid

                        for (int i = 0; i < chunksToRead; i++) {
                            int packetId = in.read();
                            while (packetId == 0xFF) {
                                packetId = in.read(); // Skip 0xFF
                            }

                            if (packetId != (CHUNK_DATA_PACKET & 0xFF)) {
                                Log.w(TAG, "Unexpected header packet received: " + String.format("%02X", packetId));
                                // Try to read and log all available bytes (up to 32) for debugging
                                try {
                                    int available = in.available();
                                    if (available > 0) {
                                        int toRead = Math.min(available, 32);
                                        byte[] debugBytes = new byte[toRead];
                                        int bytesRead = in.read(debugBytes);
                                        if (bytesRead > 0) {
                                            StringBuilder debugData = new StringBuilder("Next available bytes after unexpected header: ");
                                            for (int j = 0; j < bytesRead; j++) {
                                                debugData.append(String.format("%02X ", debugBytes[j]));
                                            }
                                            Log.w("DockingTransferError", debugData.toString().trim());
                                        }
                                    } else {
                                        Log.w("DockingTransferError", "No additional bytes available after unexpected header.");
                                    }
                                } catch (Exception e) {
                                    Log.e("DockingTransferError", "Error reading bytes after unexpected header: " + e.getMessage(), e);
                                }
                                // Delete incomplete file and DB entry
                                if (outputFile.exists()) outputFile.delete();
                                FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
                                SQLiteDatabase db = dbHelper.getWritableDatabase();
                                db.delete("files", "FILE_PATH=?", new String[]{outputFile.getAbsolutePath()});
                                db.close();

                                uiErrorAndRetry("Unexpected header, restarting after 1:00", 60, "unexpected_header", macAddress);
                                return;
                            }

                            byte[] chunkNumBytes = readExact(in, 2);
                           

                            byte[] totalBytes = readExact(in, 2);
                            int chunkSizeForThisChunk = ((totalBytes[1] & 0xFF) << 8) | (totalBytes[0] & 0xFF);
                            byte[] chunkData = readExact(in, chunkSizeForThisChunk);

                            Log.d(TAG, "Chunk number (raw bytes): " + String.format("%02X %02X", chunkNumBytes[0], chunkNumBytes[1])+
                                   ", Total bytes (raw bytes): " + String.format("%02X %02X", totalBytes[0], totalBytes[1]) +
                                   ", Chunk size: " + chunkSizeForThisChunk);

                            // Write raw binary data to the output file
                            binaryWriter.write(chunkData);

                            // Write raw hexadecimal data to the debug file with header
                            StringBuilder hexLine = new StringBuilder();
                            hexLine.append(String.format("%02X ", packetId)); // Add header (starting with FC)
                            hexLine.append(String.format("%02X %02X ", chunkNumBytes[0], chunkNumBytes[1])); // Add chunk number
                            hexLine.append(String.format("%02X %02X ", totalBytes[0], totalBytes[1])); // Add total bytes
                            for (byte b : chunkData) {
                                hexLine.append(String.format("%02X ", b)); // Add chunk data
                            }
                            debugWriter.write(hexLine.toString().trim() + "\n");

                            if (i == 0) {
                                firstChunkNumBytes[0] = chunkNumBytes[0]; // LSB
                                firstChunkNumBytes[1] = chunkNumBytes[1]; // MSB
                            }

                            chunksProcessed++;
                            
                        }

                        // Send ACK or NACK based on validity
                        byte ackStatusToSend = chunksAreValid ? (byte) 0x01 : (byte) 0x00;

                        byte[] ackPacket = new byte[]{
                                chunksAreValid ? CHUNK_DATA_ACK : CHUNK_DATA_NACK, // Send ACK or NACK
                                firstChunkNumBytes[0],
                                firstChunkNumBytes[1],
                                ackStatusToSend
                        };

                        // --- ACK Retry Protocol ---
                       int retryCount = 0;
                    boolean gotResponse = false;
                    while (retryCount < 2 && !gotResponse) {
                        out.write(ackPacket);
                        out.flush();
                        Log.d(TAG,  " Sent " + (chunksAreValid ? "ACK" : "NACK") + " packet (retry " + retryCount + "): " +
                                String.format("%02X %02X %02X %02X", ackPacket[0], ackPacket[1], ackPacket[2], ackPacket[3]));

                        // Wait for response with timeout
                        long startTime = System.currentTimeMillis();
                        while (System.currentTimeMillis() - startTime < 10000) { // 10 seconds
                            if (in.available() > 0) {
                                gotResponse = true;
                                break;
                            }
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }
                        if (!gotResponse) {
                            retryCount++;
                            Log.w(TAG, " No response after ACK, resending ACK (attempt " + (retryCount+1) + ")");
                        }
                    }
                    
                    if (!gotResponse) {
                            Log.e(TAG, "No response after 2 ACK retries. Scheduling transfer restart in 1 minute.");
                            // Delete incomplete file and DB entry
                            if (outputFile.exists()) outputFile.delete();
                            FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
                            SQLiteDatabase db = dbHelper.getWritableDatabase();
                            db.delete("files", "FILE_PATH=?", new String[]{outputFile.getAbsolutePath()});
                            db.close();

                            uiErrorAndRetry("No response from sensor, restarting after 1:00", 60, "ack_timeout", macAddress);
                            return; // Exit the transfer method
                        }
                        // --- END OF ACK Retry Protocol ---

                        // Log progress to Firebase
                        Bundle progressBundle = new Bundle();
                        progressBundle.putString("mac_address", macAddress);
                        progressBundle.putInt("chunks_processed", chunksProcessed);
                        progressBundle.putInt("total_chunks", totalChunks);
                        firebaseAnalytics.logEvent("file_transfer_progress", progressBundle);

                        // Restart transfer if chunks are invalid
                        if (!chunksAreValid) {
                            Log.e(TAG, "Chunks are invalid. Entering silent state and broadcasting failure...");
                            broadcastFailure("chunks_invalid");
                            return;
                        }
                    }
                    if (chunksProcessed >= totalChunks) {
                        Log.d(TAG, "Last chunk group processed. Skipping bytes until TRANSFER_END_PACKET with valid status...");
                        int packetId;
                        int transferStatus = -1;
                        while (true) {
                            packetId = in.read();
                            if (packetId == (TRANSFER_END_PACKET & 0xFF)) {
                                transferStatus = in.read();
                                Log.d(TAG, "Received TRANSFER_END_PACKET with status: " + String.format("%02X", transferStatus));
                                if (transferStatus == 0x01) {
                                    transferSuccess = true; // <-- Mark as success
                                    break;
                                } 
                                else if (transferStatus == 0x00) {
                                    transferSuccess = false;
                                    break;
                                }
                                else {
                                    transferSuccess = false;
                                    Log.d(TAG, "Status after FE was not 00 or 01, continuing to skip...");
                                }
                            }
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
                    .url("https://5cp7icawy1.execute-api.us-east-2.amazonaws.com/missing-files/")
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
                    .url("https://5cp7icawy1.execute-api.us-east-2.amazonaws.com/decode-and-store/")
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
                    .url("https://5cp7icawy1.execute-api.us-east-2.amazonaws.com/generate-upload-url/?filename=" + file.getName())
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