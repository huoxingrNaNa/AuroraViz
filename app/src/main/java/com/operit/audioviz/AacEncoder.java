package com.operit.audioviz;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PCM(16bit) → AAC-LC 编码器。
 * 供 VideoRecorder 混流使用：输入是解码出来的 PCM，输出直接回调给 muxer。
 */
public class AacEncoder {
    public interface Sink {
        /** 首次编码后回调一次，携带 csd-0（ESDS），必须在 muxer.start 之前 addTrack */
        void onFormat(MediaFormat format);

        /** 一包 AAC 数据 */
        void onData(ByteBuffer data, MediaCodec.BufferInfo info);
    }

    private static final int FRAME_SAMPLES = 1024;   // AAC 每帧每声道样本数
    private static final int PEND_CAPACITY = 8192;   // 交错样本缓冲

    private final Sink sink;
    private final int srcChannels;
    private final int trackChannels;
    private final int sampleRate;
    private final short[] pend = new short[PEND_CAPACITY];
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private MediaCodec codec;
    private int pendLen = 0;
    private long samplesFed = 0;      // 每声道累计样本
    private boolean formatSent = false;
    private boolean eosQueued = false;

    public AacEncoder(Sink sink, int sampleRate, int srcChannels) {
        this.sink = sink;
        this.sampleRate = sampleRate > 0 ? sampleRate : 44100;
        this.srcChannels = Math.max(1, srcChannels);
        this.trackChannels = this.srcChannels >= 2 ? 2 : 1;
    }

    public boolean start() {
        try {
            MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, trackChannels);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, trackChannels >= 2 ? 192000 : 128000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            return true;
        } catch (IOException | RuntimeException e) {
            release();
            return false;
        }
    }

    public boolean isReady() {
        return codec != null;
    }

    /** 喂入 PCM16 交错数据（len 为 short 个数） */
    public void feed(short[] pcm, int len) {
        if (codec == null || pcm == null) return;
        final int ch = srcChannels;
        final int frames = len / ch;
        for (int i = 0; i < frames; i++) {
            short l = pcm[i * ch];
            short r = ch >= 2 ? pcm[i * ch + 1] : l;
            if (trackChannels == 2) {
                if (pendLen + 2 > PEND_CAPACITY) return;   // 缓冲满则丢弃，避免堆积
                pend[pendLen++] = l;
                pend[pendLen++] = r;
            } else {
                if (pendLen + 1 > PEND_CAPACITY) return;
                pend[pendLen++] = (short) ((l + r) / 2);
            }
        }
        final int framePcm = FRAME_SAMPLES * trackChannels;
        while (pendLen >= framePcm) {
            if (!encodeFrame(framePcm)) break;
        }
    }

    private boolean encodeFrame(int framePcm) {
        int inIdx;
        try {
            inIdx = codec.dequeueInputBuffer(0);
        } catch (RuntimeException e) {
            return false;
        }
        if (inIdx < 0) return false;
        ByteBuffer ib = codec.getInputBuffer(inIdx);
        if (ib == null) return false;
        ib.clear();
        ib.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < framePcm; i++) ib.putShort(pend[i]);
        long ptsUs = samplesFed * 1_000_000L / sampleRate;
        codec.queueInputBuffer(inIdx, 0, ib.position(), ptsUs, 0);
        samplesFed += framePcm / trackChannels;
        System.arraycopy(pend, framePcm, pend, 0, pendLen - framePcm);
        pendLen -= framePcm;
        drain(false);
        return true;
    }

    /** 发送 EOS 并排空剩余数据 */
    public void finish() {
        if (codec == null || eosQueued) {
            drain(true);
            return;
        }
        try {
            int inIdx = codec.dequeueInputBuffer(20_000);
            if (inIdx >= 0) {
                long ptsUs = samplesFed * 1_000_000L / sampleRate;
                codec.queueInputBuffer(inIdx, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                eosQueued = true;
            }
        } catch (RuntimeException ignored) {
        }
        // 先把剩余的完整帧编完
        final int framePcm = FRAME_SAMPLES * trackChannels;
        while (pendLen >= framePcm) {
            if (!encodeFrame(framePcm)) break;
        }
        drain(true);
    }

    private void drain(boolean untilEos) {
        if (codec == null) return;
        int guard = 0;
        while (guard++ < 400) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
            } catch (RuntimeException e) {
                return;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!formatSent) {
                    formatSent = true;
                    sink.onFormat(codec.getOutputFormat());
                }
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer ob = codec.getOutputBuffer(idx);
            if (ob != null && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                sink.onData(ob, info);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(idx, false);
            if (eos) return;
        }
    }

    public void release() {
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Throwable ignored) {
        }
        codec = null;
    }
}