package com.pv.androidfacefusion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoProcessingService extends Service {

    public static final String EXTRA_INPUT_URI = "input_uri";
    public static final String EXTRA_EMBEDDING = "embedding";

    private static final String TAG = "GSwapService";
    private static final String CHANNEL_ID = "gswap_video_processing";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private NotificationManager notificationManager;

    private String lastErrorMessage = null;
    private String lastPerformanceReport = null;

    private void saveVideoError(Throwable error) {
        lastErrorMessage = error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage());

        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));

            File file = new File(getFilesDir(), "gswap_last_error.txt");

            try (FileOutputStream out = new FileOutputStream(file, false)) {
                String report = "G Swap video processing error\n"
                        + "Time: " + new java.util.Date() + "\n"
                        + "Error: " + lastErrorMessage + "\n\n"
                        + trace;

                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception writeError) {
            Log.e(TAG, "Cannot save video error report", writeError);
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "G Swap video processing",
                NotificationManager.IMPORTANCE_LOW
        );

        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text, int progress, boolean ongoing) {
        Intent openApp = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("G Swap Video")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);

        if (ongoing) {
            builder.setProgress(100, Math.max(0, progress), progress < 0);
        }

        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (!busy.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }

        if (intent == null) {
            busy.set(false);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        String inputString = intent.getStringExtra(EXTRA_INPUT_URI);
        float[] embedding = intent.getFloatArrayExtra(EXTRA_EMBEDDING);

        if (inputString == null || embedding == null || embedding.length == 0) {
            busy.set(false);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        try {
            Notification notification =
                    buildNotification("Starting video processing...", -1, true);

            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot start foreground service", e);
            busy.set(false);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        executor.execute(() -> processVideo(
                Uri.parse(inputString),
                embedding,
                startId
        ));

        return START_NOT_STICKY;
    }

    private Uri createOutputVideoUri() {
        ContentValues values = new ContentValues();

        values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "GSwap_" + System.currentTimeMillis() + ".mp4"
        );

        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "Movies/GSwap/Videos"
        );

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        return getContentResolver().insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
        );
    }

    private void processVideo(Uri inputUri, float[] embedding, int startId) {

        FaceDetector detector = null;
        FaceEmbedder embedder = null;
        FaceSwapper swapper = null;
        Uri outputUri = null;
        boolean success = false;

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Loading face models...", -1, true)
            );

            detector = new FaceDetector(this);
            detector.initialize();

            embedder = new FaceEmbedder(this);
            embedder.initialize();

            swapper = new FaceSwapper(this);
            swapper.initialize();

            FaceFusionProcessor processor =
                    new FaceFusionProcessor(detector, embedder, swapper);

            VideoProcessor videoProcessor = new VideoProcessor(this, processor);

            videoProcessor.setProgressListener((percent, stage) -> {
                if (percent == 100 && stage.startsWith("Frames:")) {
                    lastPerformanceReport = stage;
                    try {
                        File report = new File(getFilesDir(), "gswap_last_performance.txt");
                        try (FileOutputStream out = new FileOutputStream(report, false)) {
                            out.write(stage.getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Cannot save performance report", e);
                    }
                }
                notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(stage + " - " + percent + "%", percent, true)
                );
            });

            outputUri = createOutputVideoUri();

            if (outputUri == null) {
                throw new IllegalStateException("Cannot create output video");
            }

            videoProcessor.processVideo(inputUri, outputUri, embedding);

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, values, null, null);
            }

            success = true;

        } catch (Exception e) {
            Log.e(TAG, "Video processing failed", e);
            saveVideoError(e);

            if (outputUri != null) {
                try {
                    getContentResolver().delete(outputUri, null, null);
                } catch (Exception deleteError) {
                    Log.e(TAG, "Cannot delete failed output", deleteError);
                }
            }



        } finally {
            try {
                if (detector != null) detector.close();
            } catch (Exception e) {
                Log.e(TAG, "Detector cleanup failed", e);
            }

            try {
                if (embedder != null) embedder.close();
            } catch (Exception e) {
                Log.e(TAG, "Embedder cleanup failed", e);
            }

            try {
                if (swapper != null) swapper.close();
            } catch (Exception e) {
                Log.e(TAG, "Swapper cleanup failed", e);
            }

            stopForeground(STOP_FOREGROUND_REMOVE);

            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                            success
                                    ? (lastPerformanceReport == null
                                            ? "Video saved in Movies/GSwap/Videos"
                                            : lastPerformanceReport)
                                    : (lastErrorMessage == null
                                            ? "Video processing failed"
                                            : "Failed: " + lastErrorMessage),
                            success ? 100 : 0,
                            false
                    )
            );

            busy.set(false);
            stopSelfResult(startId);
        }
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
