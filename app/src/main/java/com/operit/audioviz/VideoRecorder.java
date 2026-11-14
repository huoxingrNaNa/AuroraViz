package com.operit.audioviz;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把 VisualizerView 的画面录制为 MP4（H.264）。
 *
 * 音频：调用方（导出页面）通过 {@link #setAudioEnabled(boolean)} 声明要带音轨，
 * 并在播放音频文件时把解码出的 PCM 交给 {@link #feedPcm}，
 * 内部用 {@link AacEncoder} 编成 AAC 一起混流。
 *
 * 管线：独立 VisualizerView → Bitmap(Canvas) → GlBlitter(GL) → MediaCodec
 *      → MediaMuxer（视频轨 + 可选音频轨）→ 公共 Movies 目录
 */
public class VideoRecorder {

    /** 一段待编码的 PCM（含采样率/声道，随块传递） */
    private static final class PcmBlock {
        final short[] data;
        final int sampleRate;
        final int channels;

        PcmBlock(short[] data, int sampleRate, int channels) {
            this.data = data;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }
    public interface Callback {
        void onProgress(int secondsLeft);

        void onDone(String path, long bytes);

        void onError(String message);
    }

    // ---- 输出参数（可在导出页调整）----
    private int width = 1280;
    private int height = 720;
    private int fps = 50;
    private int bitrate = 12_000_000;
    private static final int IFRAME_INTERVAL = 2;
    public static final int SECONDS = 15;

    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private Thread thread;

    private int seconds = SECONDS;

    // ---- 音频（由音频播放线程调用 feedPcm）----
    private volatile boolean audioEnabled = false;
    private volatile boolean audioFailed = false;
    private volatile AacEncoder aac;
    /** 待编码 PCM 队列：采集线程只入队，编码在独立线程完成，避免拖慢采集 */
    private final java.util.ArrayDeque<PcmBlock> aQ = new java.util.ArrayDeque<>();
    private Thread aThread;
    private volatile boolean aThreadStop = false;
    private static final int AQ_MAX = 96;
    private int aSampleRate = 44100;
    private int aChannels = 2;
    private long aMaxFrameSamples = 0;   // 每声道总样本上限（= 时长 * 采样率）
    private long aFramesFed = 0;

    // ---- mux 共享状态（视频线程 & 音频线程都会碰）----
    private final Object muxLock = new Object();
    private MediaMuxer muxer;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxStarted = false;
    private volatile boolean audioTrackWritten = false;

    public boolean isRecording() {
        return running;
    }

    /** 本次录制是否真的写入了音轨（录制结束后有效） */
    public boolean hasAudioTrack() {
        return audioTrackWritten;
    }

    /** 是否把音频混进 MP4（需配合 feedPcm 使用） */
    public void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
    }

    public void start(final Context ctx, final Callback cb) {
        start(ctx, SECONDS, cb);
    }

    public void start(final Context ctx, final int seconds, final Callback cb) {
        if (running) return;
        running = true;
        stopRequested = false;
        this.seconds = Math.max(1, seconds);
        audioFailed = false;
        aac = null;
        aThreadStop = false;
        synchronized (aQ) {
            aQ.clear();
            aThread = null;
        }
        aFramesFed = 0;
        audioFormat = null;
        audioTrack = -1;
        videoFormat = null;
        videoTrack = -1;
        muxStarted = false;
        muxer = null;

        final Context app = ctx.getApplicationContext();
        // 在主线程创建离屏 View（View 构造依赖 Looper 相关设施）
        final VisualizerView view = new VisualizerView(app);
        view.setDrawBackground(true);
        view.setShowModeLabel(true);
        view.setStyle(Style.byIndex(Prefs.style(app)));
        view.setPalette(Palette.byIndex(Prefs.palette(app)));
        view.setPeakMode(Prefs.peakGlow(app));
        view.setIdleAnim(Prefs.idleAnim(app));
        view.setSelfDrive(false);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        final Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        thread = new Thread(() -> record(app, cb, view, bmp, this.seconds), "AuroraRecorder");
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    /** 逐次导出前设置输出参数；传 0 表示保持当前值 */
    public void setConfig(int w, int h, int f, int b) {
        if (w > 0 && h > 0) {
            width = (w / 2) * 2;
            height = (h / 2) * 2;
        }
        if (f > 0) fps = f;
        if (b > 0) bitrate = b;
    }

    public void stop() {
        stopRequested = true;
    }

    /**
     * 由音频播放线程调用：喂入解码出的 PCM16（交错）。
     * 首次调用时才创建 AAC 编码器（用实际采样率/声道）。
     */
    public void feedPcm(short[] pcm, int len, int sampleRate, int channels) {
        if (!running || !audioEnabled || audioFailed || pcm == null || len <= 0) return;
        if (stopRequested) return;
        short[] copy = new short[len];
        System.arraycopy(pcm, 0, copy, 0, len);
        synchronized (aQ) {
            aQ.addLast(new PcmBlock(copy, sampleRate, channels));
            while (aQ.size() > AQ_MAX) aQ.removeFirst();
            if (aThread == null) {
                aThread = new Thread(this::audioLoop, "aurora-aac");
                aThread.start();
            }
            aQ.notifyAll();
        }
    }

    /** 音频编码线程：串行消费队列，不占用采集线程 */
    private void audioLoop() {
        while (true) {
            PcmBlock block;
            synchronized (aQ) {
                while (aQ.isEmpty() && !aThreadStop && running && !stopRequested) {
                    try {
                        aQ.wait(120);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (aQ.isEmpty() && (aThreadStop || !running || stopRequested)) return;
                block = aQ.pollFirst();
            }
            if (block == null) continue;
            try {
                encodeBlock(block.data, block.data.length, block.sampleRate, block.channels);
            } catch (Throwable ignored) {
            }
        }
    }

    private void encodeBlock(short[] pcm, int len, int sampleRate, int channels) {
        if (!running && !aThreadStop) return;
        if (audioFailed || pcm == null || len <= 0) return;
        try {
            if (aac == null) {
                aSampleRate = sampleRate > 0 ? sampleRate : 44100;
                aChannels = Math.max(1, channels);
                aMaxFrameSamples = (long) seconds * aSampleRate;
                final AacEncoder enc = new AacEncoder(new AacEncoder.Sink() {
                    @Override
                    public void onFormat(MediaFormat format) {
                        synchronized (muxLock) {
                            audioFormat = format;
                            maybeStartLocked();
                            muxLock.notifyAll();
                        }
                    }

                    @Override
                    public void onData(ByteBuffer data, MediaCodec.BufferInfo info) {
                        synchronized (muxLock) {
                            if (!muxStarted || audioTrack < 0 || muxer == null) return;
                            try {
                                data.position(info.offset);
                                data.limit(info.offset + info.size);
                                muxer.writeSampleData(audioTrack, data, info);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }, aSampleRate, aChannels);
                if (!enc.start()) {
                    audioFailed = true;
                    android.util.Log.w("AuroraRec", "AAC encoder start failed");
                    return;
                }
                android.util.Log.i("AuroraRec", "AAC encoder started: " + aSampleRate + "Hz "
                        + aChannels + "ch");
                aac = enc;
            }
            long remain = aMaxFrameSamples - aFramesFed;
            if (remain <= 0) return;
            int frames = len / aChannels;
            if (frames > remain) frames = (int) remain;
            if (frames <= 0) return;
            aac.feed(pcm, frames * aChannels);
            aFramesFed += frames;
        } catch (Throwable t) {
            audioFailed = true;
        }
    }

    // ---------------------------------------------------------------- 录制主流程

    private void record(Context ctx, Callback cb, final VisualizerView view, Bitmap bitmap, int seconds) {
        MediaCodec codec = null;
        GlBlitter blitter = null;
        String outPath = null;
        File tmpFile = null;
        Uri publishUri = null;
        try {
            String name = "aurora_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            // 先写临时文件（MediaMuxer 只能写文件路径），录完再发布到公共 Movies 目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tmpFile = new File(ctx.getCacheDir(), "rec_" + System.currentTimeMillis() + ".mp4");
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AuroraViz");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                publishUri = ctx.getContentResolver()
                        .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (publishUri == null) {
                    throw new IllegalStateException("无法在媒体库创建视频条目");
                }
                outPath = Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + Environment.DIRECTORY_MOVIES + "/AuroraViz/" + name;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES), "AuroraViz");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建输出目录：" + dir);
                }
                tmpFile = new File(dir, name);
                outPath = tmpFile.getAbsolutePath();
            }

            Canvas canvas = new Canvas(bitmap);

            // ---- 视频编码器 ----
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Throwable ignored) {
            }
            try {
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel41);
            } catch (Throwable ignored) {
            }
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = codec.createInputSurface();
            codec.start();

            blitter = new GlBlitter();
            if (!blitter.init(input, width, height)) {
                throw new IllegalStateException("OpenGL 初始化失败，无法录制");
            }

            muxer = new MediaMuxer(tmpFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // 若启用音轨，稍等首块 PCM 与 AAC 格式就绪（最多 1.2s），避免音轨缺失
            if (audioEnabled) {
                long deadline = System.currentTimeMillis() + 600;
                while (aac == null && !audioFailed && !stopRequested
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (aac == null) {
                    audioFailed = true;
                } else {
                    deadline = System.currentTimeMillis() + 600;
                    synchronized (muxLock) {
                        while (audioFormat == null && !audioFailed && !stopRequested) {
                            long left = deadline - System.currentTimeMillis();
                            if (left <= 0) break;
                            try {
                                muxLock.wait(Math.min(50, left));
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    if (audioFormat == null) {
                        audioFailed = true;
                        android.util.Log.w("AuroraRec", "no AAC format in time, fallback to video-only");
                    }
                }
            }

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final int total = seconds * fps;
            final long frameNs = 1_000_000_000L / fps;
            final long baseNs = System.nanoTime();
            int frame = 0;
            int lastReported = seconds;

            while (!stopRequested && frame < total) {
                long target = baseNs + frame * frameNs;
                long wait = target - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    } catch (InterruptedException ignored) {
                    }
                }
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                view.draw(canvas);
                blitter.draw(bitmap, frame * frameNs);

                drainVideo(codec, info);

                // 迟迟拿不到音频格式就降级为纯视频，避免无限等待
                if (audioEnabled && !audioFailed && videoTrack < 0 && frame > fps) {
                    audioFailed = true;
                    synchronized (muxLock) {
                        maybeStartLocked();
                    }
                }

                frame++;
                int left = seconds - frame / fps;
                if (left != lastReported) {
                    lastReported = left;
                    final int l = left;
                    post(() -> cb.onProgress(l));
                }
            }

            // ---- 收尾：先音轨、再视频 EOS、最后写 moov ----
            android.util.Log.i("AuroraRec", "record stop: frames=" + frame
                    + " audioEnabled=" + audioEnabled + " audioFailed=" + audioFailed
                    + " muxStarted=" + muxStarted + " audioTrack=" + audioTrack);
            // 先让音频线程把队列消化完，再发 EOS，避免丢失最后一段声音
            synchronized (aQ) {
                aThreadStop = true;
                aQ.notifyAll();
            }
            Thread at = aThread;
            if (at != null) {
                try {
                    at.join(900);
                } catch (InterruptedException ignored) {
                }
            }
            AacEncoder enc = aac;
            if (enc != null && !audioFailed) {
                try {
                    enc.finish();
                } catch (Throwable ignored) {
                }
            }

            codec.signalEndOfInputStream();
            long deadline = System.currentTimeMillis() + 1500;
            boolean eos = false;
            while (!eos) {
                int idx = codec.dequeueOutputBuffer(info, 200_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (muxLock) {
                        videoFormat = codec.getOutputFormat();
                        maybeStartLocked();
                        muxLock.notifyAll();
                    }
                    continue;
                }
                if (idx < 0) {
                    if (System.currentTimeMillis() > deadline) break;
                    continue;
                }
                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    synchronized (muxLock) {
                        if (muxStarted && videoTrack >= 0 && muxer != null) {
                            try {
                                buf.position(info.offset);
                                buf.limit(info.offset + info.size);
                                muxer.writeSampleData(videoTrack, buf, info);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                codec.releaseOutputBuffer(idx, false);
            }

            // MediaMuxer 的 moov（索引）在 stop() 时才写盘：
            // 必须先 stop/release 把 moov 落到临时文件，再拷贝到媒体库。
            boolean muxOk = true;
            try {
                muxer.stop();
            } catch (Throwable t) {
                muxOk = false;
            }
            try {
                muxer.release();
            } catch (Throwable ignored) {
            }
            muxer = null;
            try {
                codec.stop();
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;

            if (!muxOk) {
                throw new IllegalStateException("封装失败（moov 未写入），请重试");
            }

            long bytes = tmpFile.length();
            if (publishUri != null) {
                InputStream in = null;
                OutputStream os = null;
                try {
                    in = new FileInputStream(tmpFile);
                    os = ctx.getContentResolver().openOutputStream(publishUri);
                    if (os == null) throw new IllegalStateException("无法写入媒体库");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                } finally {
                    try {
                        if (in != null) in.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (os != null) os.close();
                    } catch (Throwable ignored) {
                    }
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(publishUri, done, null, null);
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
                tmpFile = null;
                publishUri = null;
            }
            final String p = outPath;
            final long b = bytes;
            post(() -> cb.onDone(p, b));
        } catch (Throwable t) {
            final String msg = t.getClass().getSimpleName() + ": "
                    + (t.getMessage() == null ? "录制失败" : t.getMessage());
            post(() -> cb.onError(msg));
        } finally {
            AacEncoder enc = aac;
            aac = null;
            if (enc != null) {
                try {
                    enc.release();
                } catch (Throwable ignored) {
                }
            }
            if (blitter != null) {
                try {
                    blitter.release();
                } catch (Throwable ignored) {
                }
            }
            if (codec != null) {
                try {
                    codec.stop();
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            MediaMuxer m = muxer;
            muxer = null;
            if (m != null) {
                try {
                    m.stop();
                } catch (Throwable ignored) {
                }
                try {
                    m.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                if (tmpFile != null) tmpFile.delete();
            } catch (Throwable ignored) {
            }
            try {
                if (publishUri != null) {
                    ctx.getContentResolver().delete(publishUri, null, null);
                }
            } catch (Throwable ignored) {
            }
            if (bitmap != null) bitmap.recycle();
            muxStarted = false;
            running = false;
        }
    }

    /** 视频轨 drain（非阻塞），格式就绪后尝试开 muxer */
    private void drainVideo(MediaCodec codec, MediaCodec.BufferInfo info) {
        int guard = 0;
        while (guard++ < 60) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 0);
            } catch (RuntimeException e) {
                return;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxLock) {
                    videoFormat = codec.getOutputFormat();
                    maybeStartLocked();
                    muxLock.notifyAll();
                }
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer buf = codec.getOutputBuffer(idx);
            if (buf != null && info.size > 0
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                synchronized (muxLock) {
                    if (muxStarted && videoTrack >= 0 && muxer != null) {
                        try {
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            muxer.writeSampleData(videoTrack, buf, info);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            codec.releaseOutputBuffer(idx, false);
        }
    }

    /** 两条轨的格式都齐了才 start；调用方必须持有 muxLock */
    private void maybeStartLocked() {
        if (muxStarted || muxer == null || videoFormat == null) return;
        boolean wantAudio = audioEnabled && !audioFailed && aac != null;
        if (wantAudio && audioFormat == null) return;
        if (videoTrack < 0) {
            try {
                videoTrack = muxer.addTrack(videoFormat);
            } catch (Throwable ignored) {
                return;
            }
        }
        if (audioFormat != null && audioTrack < 0) {
            try {
                audioTrack = muxer.addTrack(audioFormat);
            } catch (Throwable ignored) {
                audioTrack = -1;
            }
        }
        try {
            muxer.start();
            muxStarted = true;
            audioTrackWritten = audioTrack >= 0;
            android.util.Log.i("AuroraRec",
                    "muxer started: videoTrack=" + videoTrack + " audioTrack=" + audioTrack
                            + " audioEnabled=" + audioEnabled + " audioFailed=" + audioFailed);
        } catch (Throwable ignored) {
        }
    }

    private void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}