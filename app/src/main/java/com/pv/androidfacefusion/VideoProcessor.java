
package com.pv.androidfacefusion;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaExtractor;
import android.media.MediaCodecList;
import java.nio.ByteBuffer;

public class VideoProcessor {
    public interface ProgressListener { void onProgress(int percent, String stage); }
    private ProgressListener progressListener;
    public void setProgressListener(ProgressListener listener) { this.progressListener = listener; }
    private void reportProgress(int percent, String stage) { if (progressListener != null) progressListener.onProgress(percent, stage); }

    private final Context context;
    private final FaceFusionProcessor faceProcessor;

    public VideoProcessor(Context context, FaceFusionProcessor faceProcessor) {
        this.context = context.getApplicationContext();
        this.faceProcessor = faceProcessor;
    }

    public VideoInfo getVideoInfo(Uri videoUri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, videoUri);
            int width = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            long durationUs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
            String rotationValue = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rotationValue == null ? 0 : Integer.parseInt(rotationValue);
            return new VideoInfo(width, height, durationUs, rotation);
        } finally {
            try { r.release(); } catch (java.io.IOException e) { android.util.Log.e("GSwap", "Video metadata release failed", e); }
        }
    }

    public Bitmap extractFrame(Uri videoUri, long timeUs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            return retriever.getFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST
            );
        } finally {
            try { retriever.release(); } catch (java.io.IOException e) { android.util.Log.e("GSwap", "Video frame release failed", e); }
        }
    }

    public Bitmap swapVideoFrame(Bitmap frame, float[] sourceEmbedding) throws Exception {
        return faceProcessor.processVideoFaceFusion(frame, sourceEmbedding);
    }



    private int detectVideoFps(Uri videoUri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, videoUri, null);

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime == null || !mime.startsWith("video/"))
                    continue;

                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    int fps = format.getInteger(
                            MediaFormat.KEY_FRAME_RATE);

                    if (fps >= 1 && fps <= 120) {
                        return fps;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("GSwapVideo",
                    "Cannot detect source FPS", e);
        } finally {
            extractor.release();
        }

        android.util.Log.w("GSwapVideo",
                "FPS unavailable, using fallback 10");
        return 10;
    }

    public void processVideo(Uri inputVideo, Uri outputVideo, float[] sourceEmbedding) throws Exception {
        if (sourceEmbedding == null || sourceEmbedding.length == 0)
            throw new IllegalArgumentException("Source face missing");
        faceProcessor.resetVideoTiming();
        VideoInfo info = getVideoInfo(inputVideo);
        reportProgress(0, "Preparing video");
        // MediaMetadataRetriever may return already-rotated bitmaps. Derive
        // output geometry from an actual frame to avoid portrait distortion.
        Bitmap geometryFrame = extractFrame(inputVideo, 0);
        if (geometryFrame == null)
            throw new java.io.IOException("Cannot decode first video frame");
        final int width;
        final int height;
        try {
            width = Math.max(2, geometryFrame.getWidth() & ~1);
            height = Math.max(2, geometryFrame.getHeight() & ~1);
        } finally {
            geometryFrame.recycle();
        }
        int fps = detectVideoFps(inputVideo);
        long frameStepUs = 1000000L / fps; // Used for final-frame EOS estimate only.
        android.util.Log.i("GSwapVideo",
                "Input FPS selected: " + fps
                + ", durationUs: " + info.durationUs);
        long extractionNs = 0;
        long fusionNs = 0;
        long encodingNs = 0;
        long finalizeNs = 0;
        long audioNs = 0;
        int processedFrames = 0;
        File tempVideo = File.createTempFile("gswap_", ".mp4", context.getCacheDir());
        try {
            MediaMetadataRetriever frameRetriever = new MediaMetadataRetriever();
            try {
                frameRetriever.setDataSource(context, inputVideo);
                try (VideoEncoder encoder = new VideoEncoder(
                        tempVideo.getAbsolutePath(), width, height, fps)) {
                    MediaExtractor timeline = new MediaExtractor();
                    try {
                        timeline.setDataSource(context, inputVideo, null);

                        int videoTrack = -1;
                        for (int i = 0; i < timeline.getTrackCount(); i++) {
                            String mime = timeline.getTrackFormat(i)
                                    .getString(MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("video/")) {
                                videoTrack = i;
                                break;
                            }
                        }

                        if (videoTrack < 0)
                            throw new java.io.IOException("No video track");

                        timeline.selectTrack(videoTrack);

                        java.util.ArrayList<Long> timestamps =
                                new java.util.ArrayList<>();

                        while (true) {
                            long sampleTimeUs = timeline.getSampleTime();
                            if (sampleTimeUs < 0) break;

                            timestamps.add(sampleTimeUs);

                            if (!timeline.advance()) break;
                        }

                        java.util.Collections.sort(timestamps);
                        if (timestamps.isEmpty())
                            throw new java.io.IOException("Video contains no samples");
                        long firstTimeUs = timestamps.get(0);
                        long previousTimeUs = -1;

                        for (long timeUs : timestamps) {
                            if (timeUs <= previousTimeUs) continue;
                            previousTimeUs = timeUs;
                android.util.Log.i("GSwapVideo", "Extracting frame at " + timeUs + " us");
                    long t0 = System.nanoTime();
                    Bitmap frame = frameRetriever.getFrameAtTime(
                            timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    extractionNs += System.nanoTime() - t0;
                    if (frame == null) {
                        throw new java.io.IOException(
                                "Cannot extract video frame at " + timeUs + " us");
                    }
                    try {
                        long t1 = System.nanoTime();
                        Bitmap result = swapVideoFrame(frame, sourceEmbedding);
                        fusionNs += System.nanoTime() - t1;
                    android.util.Log.i("GSwapVideo", "Face swap completed at " + timeUs + " us");
                        try {
                            long t2 = System.nanoTime();
                            encoder.addFrame(result, timeUs - firstTimeUs);
                            encodingNs += System.nanoTime() - t2;
                            processedFrames++;
                        android.util.Log.i("GSwapVideo", "Frame encoded at " + timeUs + " us");
                        reportProgress(Math.min(95, (int) (((long) processedFrames * 95L)
                                / Math.max(1, timestamps.size()))), "Processing frames");
                        } finally {
                            if (result != frame) result.recycle();
                        }
                    } finally {
                        frame.recycle();
                    }
                        }
                    } finally {
                        timeline.release();
                    }

                    if (processedFrames == 0)
                        throw new java.io.IOException(
                                "No video frames processed");

                    long finishStartNs = System.nanoTime();
                    encoder.finish();
                    finalizeNs += System.nanoTime() - finishStartNs;
                }
            } finally {
                try {
                    frameRetriever.release();
                } catch (java.io.IOException e) {
                    android.util.Log.e("GSwapVideo", "Frame retriever release failed", e);
                }
            }
            File finalVideo = File.createTempFile("gswap_final_", ".mp4", context.getCacheDir());
            try {
                long audioStartNs = System.nanoTime();
                AudioMuxer.merge(context, tempVideo, inputVideo, finalVideo);
                audioNs = System.nanoTime() - audioStartNs;
                try (FileInputStream input = new FileInputStream(finalVideo);
                     OutputStream output = context.getContentResolver().openOutputStream(outputVideo, "w")) {
                if (output == null) throw new java.io.IOException("Cannot open output video");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            } finally {
                finalVideo.delete();
            }
            String timing = String.format(java.util.Locale.US,
                    "Frames:%d Extract:%.1fs Swap:%.1fs Encode:%.1fs Finish:%.1fs Audio:%.1fs",
                    processedFrames,
                    extractionNs / 1e9,
                    fusionNs / 1e9,
                    encodingNs / 1e9,
                    finalizeNs / 1e9,
                    audioNs / 1e9);
            android.util.Log.i("GSwapVideo", timing);
            reportProgress(100, timing + " "
                    + faceProcessor.getVideoTimingReport());
        } finally {
            tempVideo.delete();
        }
    }
}
