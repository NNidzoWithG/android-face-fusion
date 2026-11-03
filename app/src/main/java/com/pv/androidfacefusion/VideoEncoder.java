
package com.pv.androidfacefusion;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder implements AutoCloseable {
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final Surface surface;
    private final int width;
    private final int height;
    private final MediaCodec.BufferInfo bufferInfo =
            new MediaCodec.BufferInfo();

    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private boolean finished = false;

    public VideoEncoder(String outputPath, int width,
                        int height, int fps) throws IOException {
        if (width <= 0 || height <= 0 || fps <= 0
                || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException(
                    "Invalid video dimensions or FPS");
        }

        this.width = width;
        this.height = height;

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        codec = MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = codec.createInputSurface();
        muxer = new MediaMuxer(outputPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        codec.start();
    }

    public void addFrame(Bitmap bitmap) {
        if (finished) {
            throw new IllegalStateException("Encoder already finished");
        }

        Canvas canvas = surface.lockCanvas(null);
        try {
            canvas.drawBitmap(bitmap, null,
                    new Rect(0, 0, width, height), null);
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }

        drain(false);
    }

    private void drain(boolean endOfStream) {
        if (endOfStream) {
            codec.signalEndOfInputStream();
        }

        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            if (endOfStream && System.nanoTime() > deadline) throw new IllegalStateException("Video encoder EOS timeout");
            int status = codec.dequeueOutputBuffer(bufferInfo, 10000);

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
                continue;
            }

            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IllegalStateException(
                            "Output format changed twice");
                }
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }

            if (status < 0) continue;

            ByteBuffer data = codec.getOutputBuffer(status);
            if (data == null) {
                throw new IllegalStateException("Null output buffer");
            }

            if ((bufferInfo.flags
                    & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                bufferInfo.size = 0;
            }

            if (bufferInfo.size > 0) {
                if (!muxerStarted) {
                    throw new IllegalStateException(
                            "Muxer not started");
                }

                data.position(bufferInfo.offset);
                data.limit(bufferInfo.offset + bufferInfo.size);
                muxer.writeSampleData(trackIndex, data, bufferInfo);
            }

            boolean eos = (bufferInfo.flags
                    & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

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
            codec.stop();
            codec.release();
            surface.release();

            if (muxerStarted) muxer.stop();
            muxer.release();
        }
    }
}
0

