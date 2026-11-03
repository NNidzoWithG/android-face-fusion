package com.pv.androidfacefusion;

import android.content.Context;
import android.net.Uri;

public class VideoProcessor {

    private final Context context;
    private final FaceFusionProcessor faceProcessor;

    public VideoProcessor(Context context, FaceFusionProcessor faceProcessor) {
        this.context = context.getApplicationContext();
        this.faceProcessor = faceProcessor;
    }

    public void processVideo(Uri inputVideo, Uri outputVideo, float[] sourceEmbedding) {
        throw new UnsupportedOperationException("Video processing not implemented yet");
    }
}
