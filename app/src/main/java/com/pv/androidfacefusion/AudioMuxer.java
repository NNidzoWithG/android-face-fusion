package com.pv.androidfacefusion;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import java.io.File;
import java.nio.ByteBuffer;

public class AudioMuxer {

    public static void merge(Context context, File video,
                             Uri original, File output) throws Exception {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;

        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(context, original, null);

            int vt = findTrack(ve, "video/");
            int at = findTrack(ae, "audio/");

            if (vt < 0)
                throw new Exception("Video track missing");

            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int videoTrack = muxer.addTrack(ve.getTrackFormat(vt));
            int audioTrack = -1;

            if (at >= 0) {
                try {
                    audioTrack = muxer.addTrack(ae.getTrackFormat(at));
                } catch (IllegalArgumentException e) {
                    android.util.Log.w("GSwap",
                            "Unsupported audio format", e);
                }
            }

            muxer.start();
            started = true;

            copy(ve, vt, muxer, videoTrack);

            if (audioTrack >= 0)
                copy(ae, at, muxer, audioTrack);

        } finally {
            try {
                if (muxer != null) {
                    try {
                        if (started) muxer.stop();
                    } finally {
                        muxer.release();
                    }
                }
            } finally {
                ve.release();
                ae.release();
            }
        }
    }

    private static int findTrack(MediaExtractor extractor,
                                 String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix))
                return i;
        }
        return -1;
    }

    private static void copy(MediaExtractor extractor, int source,
                             MediaMuxer muxer, int destination) {
        extractor.selectTrack(source);

        MediaFormat format = extractor.getTrackFormat(source);
        int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                : 1048576;

        ByteBuffer buffer = ByteBuffer.allocateDirect(
                Math.max(1048576, size));

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            buffer.clear();
            int length = extractor.readSampleData(buffer, 0);
            if (length < 0) break;

            info.offset = 0;
            info.size = length;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags()
                    & MediaCodec.BUFFER_FLAG_KEY_FRAME;

            muxer.writeSampleData(destination, buffer, info);
            extractor.advance();
        }

        extractor.unselectTrack(source);
    }
}
