package com.pv.androidfacefusion;

public class VideoInfo {
    public final int width;
    public final int height;
    public final long durationUs;
    public final int rotation;

    public VideoInfo(int width, int height, long durationUs, int rotation) {
        this.width = width;
        this.height = height;
        this.durationUs = durationUs;
        this.rotation = rotation;
    }
}
