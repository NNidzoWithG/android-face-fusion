package com.pv.androidfacefusion;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder implements AutoCloseable {
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final int width, height, fps, colorFormat;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int track = -1;
    private int frameNumber = 0;
    private long lastPresentationTimeUs = -1;
    private final int[] pixelBuffer;
    private final byte[] yuvBuffer;
    private boolean muxerStarted = false;
    private boolean finished = false;

    public VideoEncoder(String path, int width, int height, int fps)
            throws IOException {
        if (width <= 0 || height <= 0 || fps <= 0
                || width % 2 != 0 || height % 2 != 0)
            throw new IllegalArgumentException("Invalid dimensions or FPS");

        this.width = width;
        this.height = height;
        this.fps = fps;
        this.pixelBuffer = new int[width * height];
        this.yuvBuffer = new byte[width * height * 3 / 2];

        MediaCodec selected = null;
        int selectedFormat = -1;

        MediaCodecList list = new MediaCodecList(
                MediaCodecList.REGULAR_CODECS);

        for (MediaCodecInfo candidate : list.getCodecInfos()) {
            if (!candidate.isEncoder()) continue;

            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = candidate.getCapabilitiesForType(
                        MediaFormat.MIMETYPE_VIDEO_AVC);
            } catch (IllegalArgumentException e) {
                continue;
            }

            int format = -1;
            for (int value : caps.colorFormats) {
                if (value == MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatYUV420Planar) {
                    format = value;
                    break;
                }
                if (value == MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatYUV420SemiPlanar) {
                    format = value;
                }
            }

            if (format == -1) continue;

            try {
                selected = MediaCodec.createByCodecName(
                        candidate.getName());
                selectedFormat = format;
                break;
            } catch (IOException e) {
                // Try another encoder.
            }
        }

        if (selected == null)
            throw new IOException("No supported YUV420 AVC encoder");

        codec = selected;
        colorFormat = selectedFormat;

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaMuxer createdMuxer = null;
        try {
            codec.configure(format, null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdMuxer = new MediaMuxer(path,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec.start();
        } catch (Exception e) {
            codec.release();
            if (createdMuxer != null) createdMuxer.release();
            throw new IOException("Cannot start video encoder", e);
        }
        muxer = createdMuxer;
    }

    public void addFrame(Bitmap bitmap, long presentationTimeUs) {
        if (presentationTimeUs < 0)
            throw new IllegalArgumentException("Negative frame timestamp");
        if (finished)
            throw new IllegalStateException("Encoder finished");
        if (bitmap == null)
            throw new IllegalArgumentException("Null frame");

        int[] pixels = pixelBuffer;
        Bitmap scaled = bitmap;
        if (bitmap.getWidth() != width || bitmap.getHeight() != height)
            scaled = Bitmap.createScaledBitmap(
                    bitmap, width, height, true);

        try {
            scaled.getPixels(pixels, 0, width,
                    0, 0, width, height);
        } finally {
            if (scaled != bitmap) scaled.recycle();
        }

        byte[] yuv = yuvBuffer;
        int frameSize = width * height;
        int uvIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = pixels[y * width + x];
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;

                int yy = clamp((77*r + 150*g + 29*b) >> 8);
                int u = clamp(((-43*r - 85*g + 128*b) >> 8) + 128);
                int v = clamp(((128*r - 107*g - 21*b) >> 8) + 128);

                yuv[y * width + x] = (byte) yy;

                if ((x & 1) == 0 && (y & 1) == 0) {
                    if (colorFormat == MediaCodecInfo.CodecCapabilities
                            .COLOR_FormatYUV420Planar) {
                        int offset = (y / 2) * (width / 2) + x / 2;
                        yuv[frameSize + offset] = (byte) u;
                        yuv[vIndex + offset] = (byte) v;
                    } else {
                        yuv[uvIndex++] = (byte) u;
                        yuv[uvIndex++] = (byte) v;
                    }
                }
            }
        }

        int index = codec.dequeueInputBuffer(10000000);
        if (index < 0)
            throw new IllegalStateException("Encoder input timeout");

        ByteBuffer input = codec.getInputBuffer(index);
        if (input == null || input.capacity() < yuv.length)
            throw new IllegalStateException("Encoder buffer too small");

        input.clear();
        input.put(yuv);

        long pts = presentationTimeUs;
        if (frameNumber > 0 && pts <= lastPresentationTimeUs)
            throw new IllegalArgumentException("Non-monotonic frame timestamp");
        lastPresentationTimeUs = pts;
        codec.queueInputBuffer(index, 0, yuv.length, pts, 0);
        frameNumber++;
        drain(false);
    }

    private static int clamp(int n) {
        return Math.max(0, Math.min(255, n));
    }

    private void drain(boolean end) {
        if (end) {
            int index = codec.dequeueInputBuffer(10000000);
            if (index < 0)
                throw new IllegalStateException("EOS input timeout");
            codec.queueInputBuffer(index, 0, 0,
                    lastPresentationTimeUs + 1000000L / fps,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        long deadline = System.nanoTime() + 30000000000L;

        while (true) {
            if (end && System.nanoTime() > deadline)
                throw new IllegalStateException("Encoder EOS timeout");

            int status = codec.dequeueOutputBuffer(info, 10000);

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                continue;
            }

            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted)
                    throw new IllegalStateException(
                            "Output format changed twice");
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }

            if (status < 0) continue;

            ByteBuffer data = codec.getOutputBuffer(status);

            if (data != null && info.size > 0) {
                if (!muxerStarted)
                    throw new IllegalStateException("Muxer not started");

                data.position(info.offset);
                data.limit(info.offset + info.size);
                muxer.writeSampleData(track, data, info);
            }

            boolean eos = (info.flags &
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

            codec.releaseOutputBuffer(status, false);
            if (eos) return;
        }
    }

    public void finish() {
        if (finished) return;
        drain(true);
        finished = true;
    }

    @Override
    public void close() {
        try {
            if (!finished) finish();
        } finally {
            try {
                codec.stop();
            } finally {
                codec.release();
                if (muxerStarted) muxer.stop();
                muxer.release();
            }
        }
    }
}
