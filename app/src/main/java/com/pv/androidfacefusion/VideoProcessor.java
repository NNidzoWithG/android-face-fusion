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
        java.util.Map<Integer, float[]> mapping = new java.util.HashMap<>();
        mapping.put(0, sourceEmbedding);
        return faceProcessor.processFaceFusionWithMapping(frame, mapping);
    }

    public void processVideo(Uri inputVideo, Uri outputVideo, float[] sourceEmbedding) throws Exception {
        if (sourceEmbedding == null || sourceEmbedding.length == 0)
            throw new IllegalArgumentException("Source face missing");
        VideoInfo info = getVideoInfo(inputVideo);
        int width = Math.max(2, (info.width / 2) * 2);
        int height = Math.max(2, (info.height / 2) * 2);
        int fps = 10;
        long frameStepUs = 1000000L / fps;
        File tempVideo = File.createTempFile("gswap_", ".mp4", context.getCacheDir());
        try {
            try (VideoEncoder encoder = new VideoEncoder(
                    tempVideo.getAbsolutePath(), width, height, fps)) {
                for (long timeUs = 0; timeUs < info.durationUs; timeUs += frameStepUs) {
                android.util.Log.i("GSwapVideo", "Extracting frame at " + timeUs + " us");
                    Bitmap frame = extractFrame(inputVideo, timeUs);
                    if (frame == null) continue;
                    try {
                        Bitmap result = swapVideoFrame(frame, sourceEmbedding);
                    android.util.Log.i("GSwapVideo", "Face swap completed at " + timeUs + " us");
                        try {
                            encoder.addFrame(result);
                        android.util.Log.i("GSwapVideo", "Frame encoded at " + timeUs + " us");
                        } finally {
                            if (result != frame) result.recycle();
                        }
                    } finally {
                        frame.recycle();
                    }
                }
                encoder.finish();
            }
            try (FileInputStream input = new FileInputStream(tempVideo);
                 OutputStream output = context.getContentResolver().openOutputStream(outputVideo, "w")) {
                if (output == null) throw new java.io.IOException("Cannot open output video");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            tempVideo.delete();
        }
    }
}
