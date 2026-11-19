package com.operit.audioviz;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
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
    private static final String TAG = "AuroraViz";
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
    private Switch swLiveCard;
    private TextView tvLiveMode;
    private TextView tvNameStyle;
    private Switch swHideVideo;
    private TextView analysisText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 延迟做一次"上岛自检"，等监听服务重连/系统状态落定后再判断 */
    private final Runnable liveCardCheck = this::checkLiveCardDiag;
    private GradientButton btnToggle;
    private GhostButton btnAuth;
    private GhostButton btnRecord;
    private final VideoRecorder recorder = new VideoRecorder();
    private SeekBar seekSize;
    private ChipView[] sizeChips;
    /** 悬浮窗三档预设：宽 300dp × 该倍数 */
    private static final float[] SIZE_PRESETS = {0.5333f, 1.0f, 1.7333f};
    private SeekBar seekSens;
    private Switch swOverlay;
    private SeekBar seekAlpha;
    private Switch swBare;
    private Switch swAutoSens;
    private Switch swFreqAxis;
    private Switch swModeLabel;
    private boolean suppressOverlay = false;
    private Switch swIdle;
    private Switch swDiag;
    private GhostButton btnDiagExport;
    private Switch swStereo;
    private android.view.View stereoTitleRow;
    private android.widget.LinearLayout stereoRow;
    private ChipView[] stereoChips;

    private ChipView chipMic, chipSys;
    private View pageViz, pageAbout;
    private ChipView tabViz, tabAbout;
    /** 正在监听时切换音源（拿完授权/权限后原地重启采集） */
    private boolean switchInPlace = false;
    private ChipView[] styleChips;
    private ChipView[] uiChips;
    private ChipView[] pkChips;
    private ChipView[] hzChips;
    private SwatchView[] swatches;

    private int source;
    private int styleIdx;
    private int paletteIdx;
    private boolean pendingStart = false;
    // 削波判定交给 AudioAnalysis.clipWarn()：连续满刻度采样才报

    private final Runnable diagTick = new Runnable() {
        @Override
        public void run() {
            if (!OverlayService.isRunning() || !Prefs.diag(MainActivity.this)) {
                diag.setVisibility(View.GONE);
                if (analysisText != null) analysisText.setVisibility(View.GONE);
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
            } else if (e.lastRmsDb() <= -119f && sil > 10000L) {
                boolean music = false;
                try {
                    AudioManager am = (AudioManager) MainActivity.this.getSystemService(Context.AUDIO_SERVICE);
                    music = am != null && am.isMusicActive();
                } catch (Exception ignored) {
                }
                if (source == Prefs.SOURCE_SYSTEM) {
                    txt = music
                            ? "诊断：全程静音，但系统正在播放声音——该应用禁止被录制，请改用麦克风模式"
                            : "诊断：全程静音 · 当前没有声音在播放（放首歌再看电平）";
                } else {
                    txt = "诊断：麦克风没有拾到声音——检查是否被其他应用占用或系统静音";
                }
            } else {
                int pct = preview != null ? preview.lastBarPct() : 0;
                txt = String.format(Locale.US,
                        "诊断：%d 帧 · 电平 %.0f dB · 柱高 %d%% · 静音 %s",
                        frames, e.lastRmsDb(), pct, silTxt);
            }
            String anaLine = analysisLine();
            AudioAnalysis aa = AudioEngine.get().analysis();
            boolean hot = aa.clipWarn();
            diag.setText(txt);
            diag.setVisibility(View.VISIBLE);
            if (analysisText != null) {
                analysisText.setText(hot ? anaLine + "\u3000⚠ 削波" : anaLine);
                analysisText.setTextColor(hot ? 0xFFFF7A7A : 0xFF7E8AAE);
                analysisText.setVisibility(View.VISIBLE);
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };

    /** 响度 / 节拍 / 音高一行摘要 */
    private String analysisLine() {
        AudioAnalysis a = AudioEngine.get().analysis();
        String bpmTxt = a.bpm() > 0f ? String.format(Locale.US, "%.0f", a.bpm()) : "—";
        if (a.bpm() > 0f && a.bpmAlt() > 0f) {
            // 半速/倍速歧义：把另一层拍放在括号里，避免只给一个可能错的值
            bpmTxt = String.format(Locale.US, "%.0f (%.0f)", a.bpm(), a.bpmAlt());
        }
        String pitchTxt;
        if (a.hasPitch() && a.noteName().length() > 0) {
            int c = a.noteCents();
            pitchTxt = String.format(Locale.US, "%s %s%d¢ · %.1f Hz",
                    a.noteName(), c >= 0 ? "+" : "-", Math.abs(c), a.pitchHz());
        } else {
            pitchTxt = "—";
        }
        return String.format(Locale.US,
                "电平 %.1f dBFS · 峰值 %.1f · 短时 %.1f LUFS\n节拍 %s BPM · 音高 %s",
                a.shortDb(), a.peakDb(), a.lufsApprox(), bpmTxt, pitchTxt);
    }

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
        // 若通知使用权是"假连接"（重装后常见），主动请求系统重绑
        try {
            MediaWatcher.get().ensureConnected(this);
        } catch (Throwable ignored) {
        }
        // targetSdk 36 起系统强制 edge-to-edge：窗口会延伸到系统栏下方，
        // 底部 Tab 栏必须自己避让手势条，否则会被压住半截
        View tabBar = findViewById(R.id.tabBar);
        if (tabBar != null) {
            final int padBottom = tabBar.getPaddingBottom();
            tabBar.setOnApplyWindowInsetsListener((v, ins) -> {
                int b = ins.getSystemWindowInsetBottom();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        padBottom + Math.max(b, 0));
                return ins;
            });
            tabBar.requestApplyInsets();
        }
        // 状态栏实时卡片：总开关 + 胶囊内容模式
        swLiveCard = findViewById(R.id.swLiveCard);
        tvLiveMode = findViewById(R.id.tvLiveMode);
        if (swLiveCard != null) {
            swLiveCard.setChecked(Prefs.liveCard(this));
            swLiveCard.setOnCheckedChangeListener((btn, checked) -> {
                Prefs.setLiveCard(MainActivity.this, checked);
                try {
                    if (checked) {
                        TextCard.get().start(MainActivity.this);
                    } else {
                        // 只让通知退出胶囊，不停止任何监听
                        TextCard.get().refresh();
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        if (tvLiveMode != null) {
            tvLiveMode.setText(Prefs.liveModeName(Prefs.liveMode(this)));
            tvLiveMode.setOnClickListener(v -> {
                int next = (Prefs.liveMode(MainActivity.this) + 1) % 4;
                Prefs.setLiveMode(MainActivity.this, next);
                tvLiveMode.setText(Prefs.liveModeName(next));
            });
        }
        tvNameStyle = findViewById(R.id.tvNameStyle);
        if (tvNameStyle != null) {
            tvNameStyle.setText(Prefs.nameStyleName(Prefs.nameFull(this)));
            tvNameStyle.setOnClickListener(v -> {
                boolean next = !Prefs.nameFull(MainActivity.this);
                Prefs.setNameFull(MainActivity.this, next);
                tvNameStyle.setText(Prefs.nameStyleName(next));
                try {
                    TextCard.get().refresh();   // 立即生效，不用等下一秒
                } catch (Throwable ignored) {
                }
            });
        }
        swHideVideo = findViewById(R.id.swHideVideo);
        if (swHideVideo != null) {
            swHideVideo.setChecked(Prefs.hideOnVideo(this));
            swHideVideo.setOnCheckedChangeListener((btn, checked) -> {
                Prefs.setHideOnVideo(MainActivity.this, checked);
                try {
                    TextCard.get().refresh();
                } catch (Throwable ignored) {
                }
            });
        }
        // 开关打开时，进入应用即开始显示实时卡片
        if (Prefs.liveCard(this)) {
            uiHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        TextCard.get().start(MainActivity.this);
                    } catch (Throwable ignored) {
                    }
                }
            }, 2500);
        }

        preview = findViewById(R.id.preview);
        preview.setModeLabelAnchor(1);  // 应用内：右上（LIVE 状态行下一行）
        preview.setLabelTopDp(38f);
        preview.setLabelRightDp(14f);
        stateText = findViewById(R.id.stateText);
        analysisText = findViewById(R.id.analysisText);
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
        sizeChips = new ChipView[]{findViewById(R.id.ovl0), findViewById(R.id.ovl1),
                findViewById(R.id.ovl2)};
        seekSens = findViewById(R.id.seekSens);
        swOverlay = findViewById(R.id.swOverlay);
        seekAlpha = findViewById(R.id.seekAlpha);
        swBare = findViewById(R.id.swBare);
        swAutoSens = findViewById(R.id.swAutoSens);
        swFreqAxis = findViewById(R.id.swFreqAxis);
        swModeLabel = findViewById(R.id.swModeLabel);
        swIdle = findViewById(R.id.swIdle);
        swDiag = findViewById(R.id.swDiag);
        btnDiagExport = findViewById(R.id.btnDiagExport);
        btnDiagExport.setLabel("导出诊断日志");
        btnDiagExport.setOnClickListener(v -> exportDiagLog());
        swStereo = findViewById(R.id.swStereo);
        stereoRow = findViewById(R.id.stereoRow);
        stereoTitleRow = findViewById(R.id.stereoTitleRow);

        chipMic = findViewById(R.id.chipMic);
        chipSys = findViewById(R.id.chipSys);
        styleChips = new ChipView[]{
                findViewById(R.id.st0), findViewById(R.id.st1), findViewById(R.id.st2),
                findViewById(R.id.st3), findViewById(R.id.st4), findViewById(R.id.st5)
        };
        stereoChips = new ChipView[]{
                findViewById(R.id.sm0), findViewById(R.id.sm1), findViewById(R.id.sm2)
        };
        stereoChips[0].setLabel("上下");
        stereoChips[1].setLabel("左右");
        stereoChips[2].setLabel("叠加");
        for (int i = 0; i < stereoChips.length; i++) {
            final int idx = i;
            stereoChips[i].setOnClickListener(v -> {
                Prefs.setStereoMode(this, idx);
                preview.setStereo(curStereoMode());
                applySelections();
                syncOverlay();
            });
        }
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
        preview.setStereo(curStereoMode());
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

        // ---- 底部页签：可视化 / 关于 ----
        pageViz = findViewById(R.id.pageViz);
        pageAbout = findViewById(R.id.pageAbout);
        tabViz = findViewById(R.id.tabViz);
        tabAbout = findViewById(R.id.tabAbout);
        tabViz.setLabel("可视化");
        tabAbout.setLabel("关于");
        tabViz.setChecked(true);
        tabViz.setOnClickListener(v -> showPage(0));
        tabAbout.setOnClickListener(v -> showPage(1));
        TextView aboutVer = findViewById(R.id.vAboutVer);
        if (aboutVer != null) {
            try {
                String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                aboutVer.setText("版本 " + vn + " · MIT License");
            } catch (Exception ignored) {
            }
        }
        GhostButton btnSite = findViewById(R.id.btnSite);
        if (btnSite != null) {
            btnSite.setLabel("打开项目主页");
            btnSite.setOnClickListener(v -> openUrl("https://huoxingrnana.github.io/AuroraViz/"));
        }
        TextView siteUrl = findViewById(R.id.txtSiteUrl);
        if (siteUrl != null) {
            siteUrl.setOnClickListener(v -> openUrl("https://huoxingrnana.github.io/AuroraViz/"));
        }
        GhostButton btnRepo = findViewById(R.id.btnRepo);
        if (btnRepo != null) {
            btnRepo.setLabel("打开 GitHub 仓库");
            btnRepo.setOnClickListener(v -> openUrl("https://github.com/huoxingrNaNa/AuroraViz"));
        }
        GhostButton btnLicense = findViewById(R.id.btnLicense);
        if (btnLicense != null) {
            btnLicense.setLabel("查看 MIT 许可证");
            btnLicense.setOnClickListener(v -> openUrl("https://github.com/huoxingrNaNa/AuroraViz/blob/main/LICENSE"));
        }
        TextView repoUrl = findViewById(R.id.txtRepoUrl);
        if (repoUrl != null) {
            repoUrl.setOnClickListener(v -> openUrl("https://github.com/huoxingrNaNa/AuroraViz"));
        }

        chipMic.setLabel("麦克风");
        chipSys.setLabel("系统音频");
        chipMic.setOnClickListener(v -> {
            boolean wasRunning = OverlayService.isRunning();
            source = Prefs.SOURCE_MIC;
            Prefs.setSource(this, source);
            applySelections();
            if (wasRunning) switchSourceInPlace();
        });
        chipSys.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                showMessage("系统音频捕获需要 Android 10 及以上");
                return;
            }
            boolean wasRunning = OverlayService.isRunning();
            source = Prefs.SOURCE_SYSTEM;
            Prefs.setSource(this, source);
            applySelections();
            if (wasRunning) switchSourceInPlace();
        });

        swDiag.setChecked(Prefs.diag(this));
        swDiag.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setDiag(this, checked);
            // 日志常开（对用户不可见，不设开关）；这个开关只管界面那行文字
            applyDiagVisibility();
        });

        swStereo.setChecked(Prefs.stereoOn(this));
        swStereo.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setStereoOn(this, checked);
            updateStereoRow();
            preview.setStereo(curStereoMode());
            applySelections();
            syncOverlay();
        });
        updateStereoRow();
        swIdle.setChecked(Prefs.idleAnim(this));
        applyDiagVisibility();
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

        String[] sizeLabels = {"小", "中", "大"};
        for (int i = 0; i < sizeChips.length; i++) {
            final int idx = i;
            sizeChips[i].setLabel(sizeLabels[i]);
            sizeChips[i].setOnClickListener(v -> {
                Prefs.setSize(MainActivity.this, SIZE_PRESETS[idx]);
                seekSize.setProgress(sizeToProgress(SIZE_PRESETS[idx]));
                syncSizeChips();
                syncOverlay();
            });
        }
        seekSize.setMax(100);
        seekSize.setProgress(sizeToProgress(Prefs.size(this)));
        seekSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Prefs.setSize(MainActivity.this, progressToSize(progress));
                syncSizeChips();
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

        swModeLabel.setChecked(Prefs.modeLabel(this));
        swModeLabel.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setModeLabel(MainActivity.this, checked);
            syncOverlay();
        });
        swFreqAxis.setChecked(Prefs.freqAxis(this));
        preview.setFreqAxis(Prefs.freqAxis(this));
        swFreqAxis.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setFreqAxis(MainActivity.this, checked);
            preview.setFreqAxis(checked);
            syncOverlay();
        });
        swAutoSens.setChecked(Prefs.autoSens(this));
        AudioEngine.get().setAutoSens(Prefs.autoSens(this));
        swAutoSens.setOnCheckedChangeListener((bv, checked) -> {
            Prefs.setAutoSens(MainActivity.this, checked);
            AudioEngine.get().setAutoSens(checked);
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
        // 卡片上的"关闭卡片"会改这个设置，回到界面时同步开关状态
        if (swLiveCard != null) {
            boolean on = Prefs.liveCard(this);
            if (swLiveCard.isChecked() != on) {
                swLiveCard.setChecked(on);
            }
        }
        // 上岛受阻时给一句能照做的说明（提示区本来就空才写，不覆盖其他消息）
        if (message.getVisibility() != View.VISIBLE) {
            uiHandler.removeCallbacks(liveCardCheck);
            uiHandler.postDelayed(liveCardCheck, 1500);
        }
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

    /** 尺寸滑块靠近某个预设值时点亮对应档位，手拖后三档都不亮 */
    private void syncSizeChips() {
        if (sizeChips == null) return;
        float cur = Prefs.size(this);
        for (int i = 0; i < sizeChips.length; i++) {
            sizeChips[i].setChecked(Math.abs(cur - SIZE_PRESETS[i]) < 0.01f);
        }
    }

    private void applySelections() {
        Palette p = Palette.byIndex(paletteIdx);
        Style s = Style.byIndex(styleIdx);
        int[] sc = Scheme.colors(this, p);
        int sc0 = sc[0], sc1 = sc[1], sc2 = sc[2];

        if (sizeChips != null) {
            int[] scChip = Scheme.colors(this, p);
            for (ChipView c : sizeChips) {
                c.setPalette(p);
            }
            syncSizeChips();
        }
        preview.setStyle(s);
        preview.setPalette(p);
        preview.setShowModeLabel(true);
        btnToggle.setPalette(p);
        btnAuth.setPalette(p);
        btnRecord.setPalette(p);
        btnDiagExport.setPalette(p);
        chipMic.setPalette(p);
        tabViz.setPalette(p);
        tabAbout.setPalette(p);
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
        if (stereoChips != null) {
            int sm = Prefs.stereoMode(this);
            for (int i = 0; i < stereoChips.length; i++) {
                stereoChips[i].setPalette(p);
                stereoChips[i].setChecked(i == sm);
            }
        }
        updateStereoRow();
        preview.setStereo(curStereoMode());
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
        // 10 个开关统一按状态着色：选中=主题色，未选中=灰（避免"关闭和开启同色"）
        ColorStateList thumbCl = stateTint(sc1, 0xFF6E7484);
        ColorStateList trackCl = stateTint(withAlpha(sc2, 0.55f), 0xFF3C414E);
        Switch[] sws = new Switch[]{swLiveCard, swHideVideo, swBare, swAutoSens,
                swFreqAxis, swModeLabel, swOverlay, swIdle, swDiag, swStereo};
        for (Switch swX : sws) {
            if (swX != null) {
                swX.setThumbTintList(thumbCl);
                swX.setTrackTintList(trackCl);
            }
        }
        updateAudioHint();
        applyTitleGradient();
    }

    /**
     * 开关的按状态着色：选中用主题色，未选中用灰。
     * 之前这里传的是 ColorStateList.valueOf(单色)，导致关闭状态和开启状态同色。
     */
    private static ColorStateList stateTint(int on, int off) {
        return new ColorStateList(
                new int[][]{
                        {android.R.attr.state_checked},
                        {-android.R.attr.state_enabled},
                        {}
                },
                new int[]{on, 0xFF2A2F3A, off});
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
            shizukuHint.setText("自动开启悬浮窗 / 录音 / 通知 / 通知使用权");
        }
        btnAuth.invalidate();
    }

    private void applyDiagVisibility() {
        if (diag == null) return;
        uiHandler.removeCallbacks(diagTick);
        if (Prefs.diag(this)) {
            uiHandler.post(diagTick);
        } else {
            diag.setVisibility(View.GONE);
        }
    }

    private void showPage(int idx) {
        if (pageViz == null || pageAbout == null) return;
        pageViz.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabViz.setChecked(idx == 0);
        tabAbout.setChecked(idx == 1);
        tabViz.invalidate();
        tabAbout.invalidate();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showMessage("没有可用的浏览器");
        }
    }

    /** 把环形缓冲写成文本，走系统分享（微信/文件管理器/邮件都行） */
    private void exportDiagLog() {
        java.io.File f = DiagLog.writeReport(this);
        if (f == null) {
            showMessage("导出失败");
            return;
        }
        try {
            Uri uri = DiagProvider.uriFor(f.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "AuroraViz 诊断日志");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "导出诊断日志"));
        } catch (Throwable t) {
            showMessage("分享失败：" + t.getClass().getSimpleName());
        }
    }

    private void showMessage(String msg) {
        message.setText(msg);
        message.setVisibility(View.VISIBLE);
    }

    private void clearMessage() {
        message.setVisibility(View.GONE);
    }

    /** 双声道只对柱状、示波两种线性布局有意义 */
    private boolean stereoSupported() {
        return styleIdx == Style.COLUMN.ordinal() || styleIdx == Style.SCOPE.ordinal();
    }

    /** 当前应该交给可视化视图的双声道模式：0=关闭 */
    private int curStereoMode() {
        if (!stereoSupported() || !Prefs.stereoOn(this)) return 0;
        return Prefs.stereoMode(this) + 1;
    }

    /** 双声道整行的可见性 + 布局选择的可见性 */
    private void updateStereoRow() {
        if (swStereo == null) return;
        boolean supported = stereoSupported();
        if (stereoTitleRow != null) {
            stereoTitleRow.setVisibility(supported ? View.VISIBLE : View.GONE);
        }
        if (stereoRow != null) {
            stereoRow.setVisibility(supported && swStereo.isChecked() ? View.VISIBLE : View.GONE);
        }
        int mode = Prefs.stereoMode(this);
        if (stereoChips != null) {
            for (int i = 0; i < stereoChips.length; i++) {
                stereoChips[i].setChecked(i == mode);
            }
        }
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
                String tip;
                if (!overlayOk) {
                    tip = "已执行授权命令，若悬浮窗仍不可用请手动开启";
                } else if (log.contains("NLS_FAIL")) {
                    tip = "悬浮窗 / 录音 / 通知已就绪，但通知使用权没开成 ——"
                            + "请在系统设置 › 通知使用权 里手动勾选本应用";
                } else {
                    tip = "授权完成：悬浮窗、录音、通知、通知使用权已就绪";
                }
                if (log.contains("ERR")) tip += "（部分命令失败：" + log.replace('\n', ' ') + ")";
                Log.i(TAG, "grant " + log.replace('\n', ' '));
                showMessage(tip);
                updateShizukuUi();
                updateUi();
                // 监听服务重新绑定需要几秒，延迟回查真实状态再说"是否就绪"
                uiHandler.removeCallbacks(liveCardCheck);
                uiHandler.postDelayed(liveCardCheck, 2500);
            });
        }, "shizuku-grant").start();
    }

    /**
     * 上岛（流体云实时卡片）自检。
     * 返回空串表示没有发现阻碍；否则返回一句能直接照做的说明。
     * 注意：流体云总开关是系统侧设置，App 无法代开，只能检测并提示。
     */
    private String liveCardDiag() {
        if (!Prefs.liveCard(this)) return "";
        // 先看最硬的证据：监听服务真的连上了，就说明授权一定是通的
        //（避免读到 Settings 的旧缓存，误报"未开启"）
        if (!MediaWatcher.listenerConnected()) {
            if (!ShizukuHelper.hasNotificationListener(this)) {
                return "上岛自检：通知使用权未开启 ——点「一键授权」可自动开启";
            }
            if (OverlayService.isRunning()) {
                return "上岛自检：通知使用权已开，但监听服务未连上 ——重启本应用或重新点「一键授权」";
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return "上岛自检：通知权限被拒 ——在系统设置里允许本应用发通知";
        }
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && !nm.canPostPromotedNotifications()) {
                    return "上岛自检：系统未允许提升通知 ——"
                            + "请到系统设置 › 通知与状态栏 › 流体云/实时活动 打开总开关";
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /** 自检有阻碍时把原因写到提示区（同时留一行日志备查） */
    private void checkLiveCardDiag() {
        if (message.getVisibility() == View.VISIBLE) return;
        String diag = liveCardDiag();
        if (!diag.isEmpty()) {
            Log.i(TAG, "livecard " + diag);
            showMessage(diag);
        }
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

    /** 运行中切换音源：走与“开始监听”相同的授权流程，服务端做原地重启 */
    private void switchSourceInPlace() {
        switchInPlace = true;
        showMessage("正在切换音源…");
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
        switchInPlace = false;
        clearMessage();   // 切换/启动已发出，去掉“正在切换”之类的过程提示
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
                switchInPlace = false;
                startOverlay(data, resultCode);
            } else {
                switchInPlace = false;
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
            if (granted && (pendingStart || switchInPlace)) {
                pendingStart = false;
                switchInPlace = false;
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