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

    public Bitmap extractFrame(Uri videoUri, long timeUs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            return retriever.getFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST
            );
        } finally {
            retriever.release();
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
