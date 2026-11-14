package com.operit.audioviz;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
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
 * 流程：独立 VisualizerView → Bitmap(Canvas) → GlBlitter(OpenGL) → MediaCodec input Surface
 * → MediaMuxer。使用独立 View 实例，避免与界面上的实例并发绘制。
 */
public class VideoRecorder {
    public interface Callback {
        void onProgress(int secondsLeft);

        void onDone(String path, long bytes);

        void onError(String message);
    }

    public static final int WIDTH = 960;
    public static final int HEIGHT = 540;
    private static final int FPS = 30;
    private static final int BITRATE = 6_000_000;
    private static final int IFRAME_INTERVAL = 1;
    public static final int SECONDS = 15;

    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private Thread thread;

    public boolean isRecording() {
        return running;
    }

    public void start(final Context ctx, final Callback cb) {
        if (running) return;
        running = true;
        stopRequested = false;
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
        view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
        final Bitmap bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        thread = new Thread(() -> record(app, cb, view, bmp), "AuroraRecorder");
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    public void stop() {
        stopRequested = true;
    }

    private void record(Context ctx, Callback cb, final VisualizerView view, Bitmap bitmap) {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
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
                cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/AuroraViz");
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

            // ---- 离屏渲染目标（View/Bitmap 由 start() 在主线程创建）----
            Canvas canvas = new Canvas(bitmap);

            // ---- 编码器 ----
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface input = codec.createInputSurface();
            codec.start();

            blitter = new GlBlitter();
            if (!blitter.init(input, WIDTH, HEIGHT)) {
                throw new IllegalStateException("OpenGL 初始化失败，无法录制");
            }

            muxer = new MediaMuxer(tmpFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int videoTrack = -1;
            boolean muxStarted = false;

            final int total = SECONDS * FPS;
            final long frameNs = 1_000_000_000L / FPS;
            final long baseNs = System.nanoTime();
            int frame = 0;
            int lastReported = SECONDS;

            while (!stopRequested && frame < total) {
                long target = baseNs + frame * frameNs;
                long wait = target - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    } catch (InterruptedException ignored) {
                    }
                }
                // 绘制到 bitmap
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                view.draw(canvas);
                blitter.draw(bitmap, frame * frameNs);

                // 排空编码器
                while (true) {
                    int idx = codec.dequeueOutputBuffer(info, 0);
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxStarted = true;
                        continue;
                    }
                    if (idx < 0) continue;
                    ByteBuffer buf = codec.getOutputBuffer(idx);
                    if (buf != null && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && info.size > 0 && muxStarted) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, buf, info);
                    }
                    codec.releaseOutputBuffer(idx, false);
                }

                frame++;
                int left = SECONDS - frame / FPS;
                if (left != lastReported) {
                    lastReported = left;
                    final int l = left;
                    post(() -> cb.onProgress(l));
                }
            }

            // ---- 收尾 ----
            codec.signalEndOfInputStream();
            long deadline = System.currentTimeMillis() + 1500;
            boolean eos = false;
            while (!eos) {
                int idx = codec.dequeueOutputBuffer(info, 200_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        videoTrack = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxStarted = true;
                    }
                    continue;
                }
                if (idx < 0) {
                    if (System.currentTimeMillis() > deadline) break;
                    continue;
                }
                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0 && muxStarted
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    muxer.writeSampleData(videoTrack, buf, info);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                codec.releaseOutputBuffer(idx, false);
            }

            long bytes = tmpFile.length();
            if (publishUri != null) {
                // 拷贝到媒体库并解除 pending，时间戳让相册立即刷新
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
            try {
                if (blitter != null) blitter.release();
            } catch (Throwable ignored) {
            }
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
            // 失败时清掉临时文件与媒体库里未完成的条目
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
            running = false;
        }
    }

    private void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}