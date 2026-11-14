package com.operit.audioviz;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 导入本地音频文件：解码 → 播放 → 把 PCM 喂给 AudioEngine 做频谱分析。
 * 播放走 AudioTrack 的阻塞写，天然按实时速度推进（分析也跟随实时）。
 */
public class FileAudioPlayer {
    public interface Listener {
        void onFinished();
    }

    private static final int FFT = 2048;

    private MediaExtractor extractor;
    private MediaCodec codec;
    private AudioTrack track;
    private Thread worker;

    private volatile boolean playing = false;
    private volatile long positionSamples = 0;
    private volatile int sampleRate = 44100;
    private volatile int channels = 2;
    private long durationUs = 0;
    private int trackIndex = 0;

    private final short[] cum = new short[FFT];
    private int cumLen = 0;

    public boolean isPlaying() {
        return playing;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public long durationMs() {
        return durationUs / 1000L;
    }

    public long positionMs() {
        return positionSamples * 1000L / Math.max(1, sampleRate);
    }

    /** 读取音频轨道信息（不解码），失败返回 false */
    public boolean open(Context ctx, Uri uri) {
        release();
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(ctx, uri, null);
            int idx = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    idx = i;
                    fmt = f;
                    break;
                }
            }
            if (idx < 0 || fmt == null) return false;
            trackIndex = idx;
            extractor.selectTrack(idx);
            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = fmt.getLong(MediaFormat.KEY_DURATION);
            }
            return true;
        } catch (Throwable t) {
            release();
            return false;
        }
    }

    /** 从头开始播放（并实时输出频谱数据） */
    public void play(Context ctx, boolean mute, Listener listener) {
        if (playing) return;
        try {
            if (extractor == null) return;
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            final MediaFormat fmt = extractor.getTrackFormat(trackIndex);
            String mime = fmt.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            if (!mute) {
                int chMask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO
                        : AudioFormat.CHANNEL_OUT_MONO;
                int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask,
                        AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(chMask)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuf * 4, 1 << 15))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
            }

            cumLen = 0;
            positionSamples = 0;
            playing = true;
            AudioEngine.get().enterFileMode();

            final boolean muted = mute;
            worker = new Thread(() -> loop(muted, listener), "aurora-file-audio");
            worker.start();
        } catch (Throwable t) {
            stop();
        }
    }

    private void loop(boolean mute, Listener listener) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            boolean eosIn = false;
            while (playing) {
                if (!eosIn) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int size = ib == null ? -1 : extractor.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosIn = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        short[] pcm = new short[info.size / 2];
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                        handlePcm(pcm, mute);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 采样率可能在此被修正
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            playing = false;
            AudioEngine.get().exitFileMode();
            if (listener != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(listener::onFinished);
            }
        }
    }

    /** 播放 + 分块喂分析（内部转单声道、按需累积到 FFT 窗口） */
    private void handlePcm(short[] pcm, boolean mute) {
        int frames = pcm.length / Math.max(1, channels);
        if (frames <= 0) return;

        if (!mute && track != null) {
            track.write(pcm, 0, pcm.length);   // 阻塞式写入 → 实时节流
        } else {
            // 静音模式：自己按时间节流，避免瞬间跑完
            long ms = frames * 1000L / Math.max(1, sampleRate);
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) {
                }
            }
        }
        positionSamples += frames;

        // 单声道化后累积
        for (int i = 0; i < frames; i++) {
            short v;
            if (channels >= 2) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int idx = i * channels + c;
                    if (idx < pcm.length) sum += pcm[idx];
                }
                v = (short) (sum / channels);
            } else {
                v = pcm[i];
            }
            cum[cumLen++] = v;
            if (cumLen == FFT) {
                AudioEngine.get().feedExternal(cum, FFT, sampleRate);
                cumLen = 0;
            }
        }
    }

    /** 停止播放并释放 */
    public void stop() {
        playing = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        release();
        AudioEngine.get().exitFileMode();
    }

    private void release() {
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Throwable ignored) {
        }
        codec = null;
        try {
            if (extractor != null) extractor.release();
        } catch (Throwable ignored) {
        }
        extractor = null;
        try {
            if (track != null) {
                track.pause();
                track.flush();
                track.release();
            }
        } catch (Throwable ignored) {
        }
        track = null;
    }
}