package com.pv.androidfacefusion;

import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

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

    public void processVideo(Uri inputVideo, Uri outputVideo, float[] sourceEmbedding) {
        throw new UnsupportedOperationException("Video processing not implemented yet");
    }
}
