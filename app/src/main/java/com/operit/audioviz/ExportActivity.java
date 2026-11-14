package com.operit.audioviz;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 导出页面：导入音频文件 → 实时渲染预览 → 按时长导出 MP4。
 * 也可以直接录当前监听画面（不选文件时按 15/30/60 秒录制）。
 */
public class ExportActivity extends Activity {
    private static final int REQ_PICK = 0x51;

    private VisualizerView preview;
    private GhostButton btnPick;
    private GradientButton btnStart;
    private TextView txtFile, txtInfo, txtStatus;
    private Switch swMute;
    private ChipView[] lenChips;

    private final FileAudioPlayer player = new FileAudioPlayer();
    private final VideoRecorder recorder = new VideoRecorder();

    private Uri uri;
    private int lengthIndex = 0;   // 0=15s 1=30s 2=60s 3=整首
    private int seconds = 15;
    private Palette palette = Palette.NEON;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        preview = findViewById(R.id.preview);
        btnPick = findViewById(R.id.btnPick);
        btnStart = findViewById(R.id.btnStart);
        txtFile = findViewById(R.id.txtFile);
        txtInfo = findViewById(R.id.txtInfo);
        txtStatus = findViewById(R.id.txtStatus);
        swMute = findViewById(R.id.swMute);
        lenChips = new ChipView[]{findViewById(R.id.len0), findViewById(R.id.len1),
                findViewById(R.id.len2), findViewById(R.id.len3)};

        palette = Palette.byIndex(Prefs.palette(this));

        preview.setDrawBackground(true);
        preview.setShowModeLabel(true);
        preview.setStyle(Style.byIndex(Prefs.style(this)));
        preview.setPalette(palette);
        preview.setPeakMode(Prefs.peakGlow(this));
        preview.setIdleAnim(Prefs.idleAnim(this));

        btnPick.setPalette(palette);
        btnStart.setPalette(palette);
        btnPick.setLabel("选择音频文件");
        btnPick.setOnClickListener(v -> pickAudio());

        String[] labels = {"15 秒", "30 秒", "60 秒", "整首"};
        for (int i = 0; i < lenChips.length; i++) {
            final int idx = i;
            lenChips[i].setPalette(palette);
            lenChips[i].setLabel(labels[i]);
            lenChips[i].setOnClickListener(v -> selectLength(idx));
        }
        selectLength(0);

        btnStart.setOnClickListener(v -> toggleExport());
        txtStatus.setText("选择音频可导入渲染；不选则直接录制当前监听画面");
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri u = data.getData();
        if (u == null) return;
        if (!player.open(this, u)) {
            Toast.makeText(this, "无法读取该音频文件", Toast.LENGTH_LONG).show();
            return;
        }
        uri = u;
        txtFile.setText(displayName(u));
        long ms = player.durationMs();
        txtInfo.setText(String.format(Locale.US, "%s · %d Hz · %d 声道%s",
                ms > 0 ? formatTime(ms) : "时长未知",
                player.sampleRate(), player.channels(), ""));
        txtStatus.setText("已就绪，点“开始导出”边播放边渲染");
        updateStartLabel();
    }

    private void selectLength(int idx) {
        lengthIndex = idx;
        seconds = idx == 0 ? 15 : idx == 1 ? 30 : idx == 2 ? 60 : 0;
        for (int i = 0; i < lenChips.length; i++) {
            lenChips[i].setChecked(i == idx);
        }
        updateStartLabel();
    }

    private void updateStartLabel() {
        if (recorder.isRecording()) return;
        String verb = uri == null ? "录制当前画面" : "开始导出";
        if (seconds > 0) {
            btnStart.setLabel(verb + "（" + seconds + " 秒）");
        } else {
            long ms = player.durationMs();
            btnStart.setLabel(ms > 0
                    ? verb + "（整首 " + (ms / 1000) + " 秒）"
                    : verb + "（60 秒）");
        }
    }

    private void toggleExport() {
        if (recorder.isRecording()) {
            recorder.stop();
            player.stop();
            btnStart.setLabel("正在收尾…");
            return;
        }
        int sec = seconds;
        if (sec <= 0) {
            long d = player.durationMs();
            sec = d > 0 ? (int) Math.max(1, Math.min(600, d / 1000)) : 60;
        }
        final int total = sec;

        txtStatus.setText(uri == null ? "准备录制当前画面…" : "准备导出…");
        btnStart.setLabel("停止并保存");

        if (uri != null) {
            player.play(this, swMute.isChecked(), () -> {
                if (!recorder.isRecording()) txtStatus.setText("音频播放结束");
            });
        }
        recorder.start(this, total, new VideoRecorder.Callback() {
            @Override
            public void onProgress(int secondsLeft) {
                txtStatus.setText("导出中… 剩余 " + Math.max(0, secondsLeft) + " 秒");
            }

            @Override
            public void onDone(String path, long bytes) {
                player.stop();
                btnStart.setLabel("开始导出");
                updateStartLabel();
                String size = String.format(Locale.US, "%.1f MB", bytes / 1048576f);
                txtStatus.setText("已保存 " + size + "\n" + path);
                Toast.makeText(ExportActivity.this,
                        "导出完成 " + size + "\n" + path, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                player.stop();
                btnStart.setLabel("开始导出");
                updateStartLabel();
                txtStatus.setText("导出失败：" + message);
                Toast.makeText(ExportActivity.this, "导出失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "已选音频" : s;
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDestroy() {
        try {
            recorder.stop();
        } catch (Throwable ignored) {
        }
        try {
            player.stop();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}