package com.operit.audioviz;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import rikka.shizuku.Shizuku;

/** 主界面：监听控制、六种模式、六套配色、界面风格与 Shizuku 一键授权 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 0x11;
    private static final int REQ_MIC = 0x12;
    private static final int REQ_PROJECTION = 0x13;
    private static final int REQ_NOTIF = 0x14;
    private static final int REQ_SHIZUKU = 0x21;

    private VisualizerView preview;
    private TextView stateText;
    private TextView message;
    private TextView statusShizuku;
    private TextView shizukuHint;
    private TextView themeLabel;
    private TextView audioHint;
    private TextView mainHint;
    private TextView diag;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private GradientButton btnToggle;
    private GhostButton btnAuth;
    private GhostButton btnRecord;
    private final VideoRecorder recorder = new VideoRecorder();
    private SeekBar seekSize;
    private SeekBar seekSens;
    private Switch swOverlay;
    private SeekBar seekAlpha;
    private Switch swBare;
    private boolean suppressOverlay = false;
    private Switch swIdle;
    private Switch swBloom;

    private ChipView chipMic, chipSys;
    private ChipView[] styleChips;
    private ChipView[] uiChips;
    private ChipView[] pkChips;
    private ChipView[] hzChips;
    private SwatchView[] swatches;

    private int source;
    private int styleIdx;
    private int paletteIdx;
    private boolean pendingStart = false;

    private final Runnable diagTick = new Runnable() {
        @Override
        public void run() {
            if (!OverlayService.isRunning()) {
                diag.setVisibility(View.GONE);
                return;
            }
            AudioEngine e = AudioEngine.get();
            long frames = e.frames();
            long sil = e.silenceMs();
            String silTxt = sil == Long.MAX_VALUE ? "—"
                    : String.format(Locale.US, "%.1fs", sil / 1000f);
            String txt;
            if (frames == 0L) {
                txt = "诊断：尚未收到任何音频帧（0 帧）——该应用很可能禁止被录制";
            } else {
                txt = String.format(Locale.US,
                        "诊断：%d 帧 · 电平 %.0f dB · 静音 %s", frames, e.lastRmsDb(), silTxt);
            }
            diag.setText(txt);
            diag.setVisibility(View.VISIBLE);
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener shizukuListener =
            (requestCode, grantResult) -> {
                if (requestCode == REQ_SHIZUKU) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        runGrants();
                    } else {
                        showMessage("Shizuku 授权被拒绝");
                    }
                    updateShizukuUi();
                }
            };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (OverlayService.ACTION_ERROR.equals(action)) {
                String msg = intent.getStringExtra(OverlayService.EXTRA_MESSAGE);
                showMessage(msg == null ? "启动失败" : msg);
                updateUi();
            } else if (OverlayService.ACTION_OVERLAY_CHANGED.equals(action)) {
                suppressOverlay = true;
                swOverlay.setChecked(false);
                suppressOverlay = false;
                updateAudioHint();
            } else if (OverlayService.ACTION_STATE.equals(action)) {
                styleIdx = Prefs.style(MainActivity.this);
                paletteIdx = Prefs.palette(MainActivity.this);
                applySelections();
                updateUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        stateText = findViewById(R.id.stateText);
        message = findViewById(R.id.message);
        statusShizuku = findViewById(R.id.statusShizuku);
        shizukuHint = findViewById(R.id.shizukuHint);
        themeLabel = findViewById(R.id.themeLabel);
        audioHint = findViewById(R.id.audioHint);
        mainHint = findViewById(R.id.mainHint);
        diag = findViewById(R.id.diag);
        btnToggle = findViewById(R.id.btnToggle);
        btnAuth = findViewById(R.id.btnAuth);
        btnRecord = findViewById(R.id.btnRecord);
        seekSize = findViewById(R.id.seekSize);
        seekSens = findViewById(R.id.seekSens);
        swOverlay = findViewById(R.id.swOverlay);
        seekAlpha = findViewById(R.id.seekAlpha);
        swBare = findViewById(R.id.swBare);
        swIdle = findViewById(R.id.swIdle);
        swBloom = findViewById(R.id.swBloom);
        swBloom.setChecked(Prefs.bloom(this));
        preview.setBloom(Prefs.bloom(this));
        swBloom.setOnCheckedChangeListener((bv, isChecked) -> {
            Prefs.setBloom(this, isChecked);
            preview.setBloom(isChecked);
            syncOverlay();
        });

        chipMic = findViewById(R.id.chipMic);
        chipSys = findViewById(R.id.chipSys);
        styleChips = new ChipView[]{
                findViewById(R.id.st0), findViewById(R.id.st1), findViewById(R.id.st2),
                findViewById(R.id.st3), findViewById(R.id.st4), findViewById(R.id.st5),
                findViewById(R.id.st6), findViewById(R.id.st7)
        };
        swatches = new SwatchView[]{
                findViewById(R.id.sw0), findViewById(R.id.sw1), findViewById(R.id.sw2),
                findViewById(R.id.sw3), findViewById(R.id.sw4), findViewById(R.id.sw5),
                findViewById(R.id.sw6)
        };
        uiChips = new ChipView[]{
                findViewById(R.id.ui0), findViewById(R.id.ui1), findViewById(R.id.ui2)
        };
        uiChips[0].setLabel("深空");
        uiChips[1].setLabel("纯黑");
        uiChips[2].setLabel("跟随系统");

        pkChips = new ChipView[]{
                findViewById(R.id.pk0), findViewById(R.id.pk1), findViewById(R.id.pk2)
        };
        pkChips[0].setLabel("发光点");
        pkChips[1].setLabel("细线");
        pkChips[2].setLabel("关闭");
        for (int i = 0; i < pkChips.length; i++) {
            final int idx = i;
            pkChips[i].setOnClickListener(v -> {
                Prefs.setPeakGlow(this, idx);
                preview.setPeakMode(idx);
                applySelections();
                syncOverlay();
            });
        }
        preview.setPeakMode(Prefs.peakGlow(this));
        preview.setSmoothTau(Prefs.vizSmoothTau(this));
        // 可视化帧率（只影响重绘频率，不影响导出视频帧率）
        hzChips = new ChipView[]{findViewById(R.id.vz0), findViewById(R.id.vz1),
                findViewById(R.id.vz2), findViewById(R.id.vz3)};
        String[] hzLabels = {"自动", "30", "60", "120"};
        int hzSel = Prefs.vizHz(this);
        Palette hzPal = Palette.byIndex(Prefs.palette(this));
        for (int i = 0; i < hzChips.length; i++) {
            final int hi = i;
            hzChips[i].setPalette(hzPal);
            hzChips[i].setLabel(hzLabels[i]);
            hzChips[i].setChecked(i == hzSel);
            hzChips[i].setOnClickListener(v -> {
                for (int k = 0; k < hzChips.length; k++) hzChips[k].setChecked(k == hi);
                Prefs.setVizHz(this, hi);
                preview.setRenderHz(Prefs.vizHzValue(hi));
            });
        }
        preview.setRenderHz(Prefs.vizHzValue(hzSel));

        styleIdx = Prefs.style(this);
        paletteIdx = Prefs.palette(this);
        source = Prefs.source(this);
        AudioEngine.get().setSensitivity(Prefs.sens(this));

        for (int i = 0; i < styleChips.length; i++) {
            final int idx = i;
            styleChips[i].setLabel(Style.byIndex(i).label);
            styleChips[i].setOnClickListener(v -> {
                styleIdx = idx;
                Prefs.setStyle(this, idx);
                applySelections();
                syncOverlay();
            });
        }
        for (int i = 0; i < swatches.length; i++) {
            final int idx = i;
            swatches[i].setPalette(Palette.byIndex(i));
            swatches[i].setOnClickListener(v -> {
                if (idx == Palette.CUSTOM.ordinal()) {
                    openColorPicker();
                    return;
                }
                paletteIdx = idx;
                Prefs.setPalette(this, idx);
                applySelections();
                syncOverlay();
            });
        }
        final int uiStyle = Prefs.uiStyle(this);
        for (int i = 0; i < uiChips.length; i++) {
            final int idx = i;
            uiChips[i].setOnClickListener(v -> {
                Prefs.setUiStyle(this, idx);
                applyUiStyle();
                applySelections();
            });
        }

        chipMic.setLabel("麦克风");
        chipSys.setLabel("系统音频");
        chipMic.setOnClickListener(v -> {
            source = Prefs.SOURCE_MIC;
            Prefs.setSource(this, source);
            applySelections();
        });
        chipSys.setOnClickListener(v -> {
            source = Prefs.SOURCE_SYSTEM;
            Prefs.setSource(this, source);
            applySelections();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                showMessage("系统音频捕获需要 Android 10 及以上");
            }
        });

        swIdle.setChecked(Prefs.idleAnim(this));
        preview.setIdleAnim(Prefs.idleAnim(this));
        swIdle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setIdleAnim(this, isChecked);
            preview.setIdleAnim(isChecked);
            if (OverlayService.isRunning()) {
                try {
                    startService(new Intent(this, OverlayService.class)
                            .setAction(OverlayService.ACTION_APPLY)
                            .putExtra(OverlayService.EXTRA_SHOW_OVERLAY, swOverlay.isChecked()));
                } catch (Exception ignored) {
                }
            }
        });

        swOverlay.setChecked(Prefs.overlayEnabled(this));
        swOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressOverlay) return;
            Prefs.setOverlayEnabled(this, isChecked);
            updateUi();
            if (OverlayService.isRunning()) {
                Intent i = new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_APPLY)
                        .putExtra(OverlayService.EXTRA_SHOW_OVERLAY, isChecked);
                try {
                    startService(i);
                } catch (Exception ignored) {
                }
            }
        });

        seekSize.setMax(100);
        seekSize.setProgress(sizeToProgress(Prefs.size(this)));
        seekSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Prefs.setSize(MainActivity.this, progressToSize(progress));
            }
        });

        seekAlpha.setMax(100);
        seekAlpha.setProgress(alphaToProgress(Prefs.alpha(this)));
        seekAlpha.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Prefs.setAlpha(MainActivity.this, progressToAlpha(progress));
                syncOverlay();
            }
        });

        swBare.setChecked(Prefs.bare(this));
        swBare.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setBare(MainActivity.this, isChecked);
            syncOverlay();
        });

        seekSens.setMax(100);
        seekSens.setProgress(sensToProgress(Prefs.sens(this)));
        seekSens.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float s = progressToSens(progress);
                Prefs.setSens(MainActivity.this, s);
                AudioEngine.get().setSensitivity(s);
            }
        });

        btnAuth.setOnClickListener(v -> onAuthClick());
        btnRecord.setLabel("导出可视化视频…");
        btnRecord.setOnClickListener(v -> onRecordClick());
        btnToggle.setOnClickListener(v -> onToggle());

        ShizukuHelper.addPermissionListener(shizukuListener);

        applyUiStyle();
        applySelections();
        updateUi();
        updateShizukuUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(OverlayService.ACTION_STATE);
        filter.addAction(OverlayService.ACTION_OVERLAY_CHANGED);
        filter.addAction(OverlayService.ACTION_ERROR);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        styleIdx = Prefs.style(this);
        paletteIdx = Prefs.palette(this);
        applyUiStyle();
        applySelections();
        updateUi();
        updateShizukuUi();
        uiHandler.removeCallbacks(diagTick);
        uiHandler.post(diagTick);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(diagTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            recorder.stop();
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        ShizukuHelper.removePermissionListener(shizukuListener);
        uiHandler.removeCallbacks(diagTick);
        super.onDestroy();
    }

    // ------------------------------------------------------------ 界面风格

    private boolean systemIsNight() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyUiStyle() {
        int ui = Prefs.uiStyle(this);
        boolean light = ui == 2 && !systemIsNight();

        int bg = ui == 1 ? R.drawable.bg_window_black
                : (light ? R.drawable.bg_window_light : R.drawable.bg_window);
        findViewById(R.id.rootBg).setBackgroundResource(bg);
        getWindow().setBackgroundDrawableResource(bg);

        int card = light ? R.drawable.bg_card_light : R.drawable.bg_card;
        findViewById(R.id.previewCard).setBackgroundResource(card);
        findViewById(R.id.advCard).setBackgroundResource(card);

        int primary = light ? 0xFF161922 : 0xFFF2F5FF;
        int label = light ? 0xFF5A6280 : 0xFF8A93B2;
        int hint = light ? 0xFF858DA6 : 0xFF6F7896;
        applyTextColors(findViewById(R.id.rootBg), primary, label, hint);

        for (int i = 0; i < uiChips.length; i++) {
            uiChips[i].setChecked(i == ui);
            uiChips[i].setPalette(Palette.byIndex(paletteIdx));
        }
    }

    private void applyTextColors(View v, int primary, int label, int hint) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Object tag = tv.getTag();
            int id = tv.getId();
            if ("label".equals(tag)) {
                tv.setTextColor(label);
            } else if ("hint".equals(tag)) {
                tv.setTextColor(hint);
            } else if (id != R.id.title && id != R.id.stateText
                    && id != R.id.message && id != R.id.themeLabel
                    && id != R.id.statusShizuku && id != R.id.shizukuHint) {
                tv.setTextColor(primary);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyTextColors(g.getChildAt(i), primary, label, hint);
            }
        }
    }

    private void applyTitleGradient() {
        final TextView title = findViewById(R.id.title);
        title.post(() -> {
            Palette p = Palette.byIndex(paletteIdx);
            float ts = title.getTextSize();
            title.getPaint().setShader(new LinearGradient(0, 0,
                    Math.max(1f, title.getWidth()), ts, p.c0, p.c2, Shader.TileMode.CLAMP));
            title.invalidate();
        });
    }

    private static int alphaToProgress(float a) {
        return Math.round((a - 0.25f) / 0.75f * 100f);
    }

    private static float progressToAlpha(int p) {
        return 0.25f + 0.75f * (p / 100f);
    }

    private void applySelections() {
        Palette p = Palette.byIndex(paletteIdx);
        Style s = Style.byIndex(styleIdx);
        int[] sc = Scheme.colors(this, p);
        int sc0 = sc[0], sc1 = sc[1], sc2 = sc[2];

        preview.setStyle(s);
        preview.setPalette(p);
        preview.setShowModeLabel(true);
        btnToggle.setPalette(p);
        btnAuth.setPalette(p);
        btnRecord.setPalette(p);
        chipMic.setPalette(p);
        chipSys.setPalette(p);
        for (ChipView c : uiChips) c.setPalette(p);
        int peakMode = Prefs.peakGlow(this);
        for (int i = 0; i < pkChips.length; i++) {
            pkChips[i].setPalette(p);
            pkChips[i].setChecked(i == peakMode);
        }
        preview.setPeakMode(peakMode);

        for (int i = 0; i < styleChips.length; i++) {
            styleChips[i].setPalette(p);
            styleChips[i].setChecked(i == styleIdx);
        }
        for (int i = 0; i < swatches.length; i++) {
            swatches[i].setChecked(i == paletteIdx);
        }
        chipMic.setChecked(source == Prefs.SOURCE_MIC);
        chipSys.setChecked(source == Prefs.SOURCE_SYSTEM);

        themeLabel.setText(p.label);
        themeLabel.setTextColor(sc2);

        ColorStateList tint = ColorStateList.valueOf(sc2);
        seekSize.setProgressTintList(tint);
        seekAlpha.setProgressTintList(tint);
        seekAlpha.setThumbTintList(ColorStateList.valueOf(sc1));
        seekSize.setThumbTintList(ColorStateList.valueOf(sc1));
        seekSens.setProgressTintList(tint);
        seekSens.setThumbTintList(ColorStateList.valueOf(sc1));
        swOverlay.setThumbTintList(ColorStateList.valueOf(sc1));
        swOverlay.setTrackTintList(ColorStateList.valueOf(withAlpha(sc2, 0.45f)));
        swIdle.setThumbTintList(ColorStateList.valueOf(sc1));
        swIdle.setTrackTintList(ColorStateList.valueOf(withAlpha(sc2, 0.45f)));

        updateAudioHint();
        applyTitleGradient();
    }

    private static int withAlpha(int color, float alpha) {
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private void updateAudioHint() {
        if (source == Prefs.SOURCE_MIC) {
            audioHint.setText("监听设备麦克风：说话或外放音乐都能被采集");
        } else if (Build.VERSION.SDK_INT >= 34) {
            audioHint.setText("启动后系统会询问监听范围：整个屏幕 / 单个应用。选“单个应用”时只监听该应用的声音，且该应用需允许被录制");
        } else {
            audioHint.setText("系统音频需要授权屏幕录制；若目标应用禁止被录制，将听不到声音，请改用麦克风");
        }
    }

    private void updateUi() {
        boolean running = OverlayService.isRunning();
        boolean wantOverlay = swOverlay.isChecked();
        if (running) {
            String src = source == Prefs.SOURCE_SYSTEM ? "系统音频" : "麦克风";
            stateText.setText("LIVE · " + src);
            stateText.setTextColor(0xFF8CF7E4);
            btnToggle.setLabel("停止监听");
        } else {
            stateText.setText("STANDBY");
            stateText.setTextColor(0xFF9AA5C4);
            btnToggle.setLabel("开始监听");
        }
        mainHint.setText(wantOverlay
                ? "监听中会显示可拖动悬浮窗，随时可关闭"
                : "仅在本应用内显示可视化，不弹悬浮窗");
        btnToggle.invalidate();
    }

    private void updateShizukuUi() {
        boolean avail = ShizukuHelper.isAvailable();
        boolean granted = ShizukuHelper.hasPermission();

        if (!avail) {
            statusShizuku.setText("SHIZUKU 未连接");
            statusShizuku.setTextColor(0xFF8A93B2);
            btnAuth.setLabel("如何授权");
            shizukuHint.setText("Shizuku 未运行：请先启动 Shizuku 服务");
        } else if (!granted) {
            statusShizuku.setText("SHIZUKU 待授权");
            statusShizuku.setTextColor(0xFFFFD166);
            btnAuth.setLabel("请求授权");
            shizukuHint.setText("已连接 Shizuku，需授权后才能自动开权限");
        } else {
            statusShizuku.setText("SHIZUKU 就绪");
            statusShizuku.setTextColor(0xFF8CF7E4);
            btnAuth.setLabel("一键授权");
            shizukuHint.setText("自动开启悬浮窗 / 录音 / 通知权限");
        }
        btnAuth.invalidate();
    }

    private void showMessage(String msg) {
        message.setText(msg);
        message.setVisibility(View.VISIBLE);
    }

    private void clearMessage() {
        message.setVisibility(View.GONE);
    }

    private void syncOverlay() {
        if (!OverlayService.isRunning()) return;
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_APPLY)
                .putExtra(OverlayService.EXTRA_SHOW_OVERLAY, swOverlay.isChecked());
        try {
            startService(i);
        } catch (Exception ignored) {
        }
    }

    private void openColorPicker() {
        Palette cur = Palette.byIndex(paletteIdx);
        int[] init = Scheme.colors(this, cur);
        ColorPickerDialog dlg = new ColorPickerDialog(this, cur,
                new int[]{init[0], init[1], init[2]},
                (c0, c1, c2) -> {
                    Prefs.setCustomColors(this, c0, c1, c2);
                    paletteIdx = Palette.CUSTOM.ordinal();
                    Prefs.setPalette(this, paletteIdx);
                    for (int i = 0; i < swatches.length; i++) {
                        swatches[i].setPalette(Palette.byIndex(i));
                    }
                    applySelections();
                    syncOverlay();
                });
        dlg.show();
    }

    // ------------------------------------------------------------ Shizuku

    private void onRecordClick() {
        startActivity(new Intent(this, ExportActivity.class));
    }

    private void onRecordClickLegacy() {
        if (android.os.Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
            Toast.makeText(this, "先允许存储权限，再点一次导出", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recorder.isRecording()) {
            recorder.stop();
            btnRecord.setLabel("正在收尾…");
            return;
        }
        btnRecord.setLabel("录制中… " + VideoRecorder.SECONDS + "s");
        recorder.start(this, new VideoRecorder.Callback() {
            @Override
            public void onProgress(int secondsLeft) {
                btnRecord.setLabel("录制中… " + Math.max(0, secondsLeft) + "s");
            }

            @Override
            public void onDone(String path, long bytes) {
                btnRecord.setLabel("导出可视化视频…");
                String size = String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
                Toast.makeText(MainActivity.this,
                        "已保存 " + size + "\n" + path, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                btnRecord.setLabel("导出可视化视频…");
                Toast.makeText(MainActivity.this, "录制失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onAuthClick() {
        if (!ShizukuHelper.isAvailable()) {
            showMessage("未检测到 Shizuku。安装并启动 Shizuku 后即可一键授权；"
                    + "也可手动开启悬浮窗权限，或用 adb：adb shell appops set "
                    + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            return;
        }
        if (!ShizukuHelper.hasPermission()) {
            ShizukuHelper.requestPermission(REQ_SHIZUKU);
            return;
        }
        runGrants();
    }

    private void runGrants() {
        Toast.makeText(this, "正在通过 Shizuku 授权…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String log = ShizukuHelper.grantAll(this);
            runOnUiThread(() -> {
                boolean overlayOk = Settings.canDrawOverlays(this);
                String tip = overlayOk ? "授权完成：悬浮窗、录音、通知已就绪"
                        : "已执行授权命令，若悬浮窗仍不可用请手动开启";
                if (log.contains("ERR")) tip += "（部分命令失败：" + log.replace('\n', ' ') + ")";
                showMessage(tip);
                updateShizukuUi();
                updateUi();
            });
        }, "shizuku-grant").start();
    }

    // ------------------------------------------------------------ 监听控制

    private void onToggle() {
        clearMessage();
        if (OverlayService.isRunning()) {
            Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP);
            startService(i);
            updateUi();
            return;
        }
        if (swOverlay.isChecked() && !Settings.canDrawOverlays(this)) {
            showMessage("已开启悬浮窗，需要「显示在其他应用上层」权限。"
                    + "可用 Shizuku 一键授权，或在此跳转手动开启（也可先关闭悬浮窗开关）");
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        if (source == Prefs.SOURCE_SYSTEM && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showMessage("系统音频捕获需要 Android 10 及以上，当前设备仅支持麦克风模式");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        continueStart();
    }

    private void continueStart() {
        if (source == Prefs.SOURCE_SYSTEM) {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                showMessage("设备不支持屏幕 / 音频捕获");
                return;
            }
            Intent intent;
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+：由系统询问「整个屏幕」或「单个应用」
                intent = mpm.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForUserChoice());
            } else {
                intent = mpm.createScreenCaptureIntent();
            }
            startActivityForResult(intent, REQ_PROJECTION);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startOverlay(null, 0);
    }

    private void startOverlay(Intent resultData, int resultCode) {
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START)
                .putExtra(OverlayService.EXTRA_SOURCE, source)
                .putExtra(OverlayService.EXTRA_SHOW_OVERLAY, swOverlay.isChecked());
        if (resultData != null) {
            i.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(OverlayService.EXTRA_RESULT_DATA, resultData);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            showMessage("启动失败：" + e.getMessage());
        }
        btnToggle.postDelayed(this::updateUi, 500);
    }

    // ------------------------------------------------------------ 回调

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                clearMessage();
                onToggle();
            } else {
                showMessage("未授予悬浮窗权限，可先关闭悬浮窗开关仅在应用内监听");
            }
            return;
        }
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Prefs.setSource(this, Prefs.SOURCE_SYSTEM);
                startOverlay(data, resultCode);
            } else {
                showMessage("已取消屏幕录制授权，无法捕获系统音频");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC) {
            if (granted && pendingStart) {
                pendingStart = false;
                startOverlay(null, 0);
            } else if (!granted) {
                pendingStart = false;
                showMessage("需要录音权限才能采集麦克风音频");
            }
        } else if (requestCode == REQ_NOTIF) {
            if (pendingStart) {
                pendingStart = false;
                continueStart();
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private static int sizeToProgress(float size) {
        float p = (size - 0.55f) / 1.05f * 100f;
        return (int) Math.max(0, Math.min(100, p));
    }

    private static float progressToSize(int progress) {
        return 0.55f + Math.max(0, Math.min(100, progress)) / 100f * 1.05f;
    }

    private static int sensToProgress(float sens) {
        float p = (sens - 0.4f) / 2.2f * 100f;
        return (int) Math.max(0, Math.min(100, p));
    }

    private static float progressToSens(int progress) {
        return 0.4f + Math.max(0, Math.min(100, progress)) / 100f * 2.2f;
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}