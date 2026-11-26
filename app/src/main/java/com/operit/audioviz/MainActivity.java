package com.operit.audioviz;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
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
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
    private TextView tvLiveHint;
    private TextView tvNameStyle;
    private Switch swHideVideo;
    private TextView analysisText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 延迟做一次"上岛自检"，等监听服务重连/系统状态落定后再判断 */
    private final Runnable liveCardCheck = this::checkLiveCardDiag;
    /** 自检自己写进提示区的文案：用于「连上之后自动撤销」，不误删别的提示 */
    private String liveCardAutoMsg = "";
    /** 自检剩余复检次数（连不上时每 5 秒复查一次，最多 6 次） */
    private int liveCardRecheck = 0;
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

    // ----全屏预览 /屏幕常亮 /预设 ----
    /** 全屏预览层（盖住整个界面，只有一块画布） */
    private View fsLayer;
    private View fsEntry;
    private View fsTip;
    private VisualizerView fsPreview;
    private boolean fsActive = false;
    /** 本次全屏是否替用户把浮窗收起来了（退出时由它决定要不要还原） */
    private boolean fsOverlayHidden = false;
    private Switch swKeepOn;
    private Switch swFsBars;
    private Switch swFsLand;
    private android.widget.LinearLayout presetRow;
    private View presetScroll;
    private TextView presetHint;
    /** 与预设一一对应的胶囊，用来高亮"当前就是这套" */
    private ChipView[] presetChips = new ChipView[0];
    private java.util.List<Presets.Item> presetItems = new java.util.ArrayList<>();

    /**
     * 全屏期间每秒记一条"实测帧率 + 屏幕当前刷新率"。
     * 用途：区分两种掉帧原因——①GPU/绘制跟不上（fps < 屏幕 Hz）
     * ②系统把这块屏降到了 60Hz（fps ≈ 60 且屏幕也是 60）。
     */
    private long fsLastDraw = -1L;
    /** 本次全屏已重新申请帧率的次数（最多2次，防止 120/60 锯齿） */
    private int fsReasserts = 0;
    private long fsLastAt = 0L;
    private final Runnable fsTick = new Runnable() {
        @Override
        public void run() {
            if (!fsActive || fsPreview == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            long n = fsPreview.drawCount();
            if (fsLastAt > 0L) {
                long dn = n - fsLastDraw;
                float dt = (now - fsLastAt) / 1000f;
                if (dt > 0.2f) {
                    float fps = dn / dt;
                    float hz = displayHz();
                    String line = String.format(Locale.US,
                            "全屏帧率 %.1f fps · 屏幕 %.1f Hz · 画布 %dx%d",
                            fps, hz, fsPreview.getWidth(), fsPreview.getHeight());
                    Log.i(TAG, line);
                    DiagLog.add(line);
                    // 开了"实时诊断"就在全屏里直接显示，不用去翻日志
                    if (fsTip instanceof TextView && Prefs.diag(MainActivity.this)) {
                        ((TextView) fsTip).setText(String.format(Locale.US,
                                "%.0f fps · 屏幕 %.0f Hz", fps, hz));
                    }
                    // 自愈：实测明显低于目标就重新表态一次，但最多试 2 次。
                    // 有的 ROM 只给几秒高刷然后自己降档，反复申请只会变成 120/60 锯齿
                    // （实测 121→89.9→60→121），比稳定 60 更难看，所以认命后就不再折腾。
                    int want = Prefs.vizHzValue(Prefs.vizHz(MainActivity.this));
                    float target = want > 0 ? want : hz;
                    if (target > 0f && fps < target * 0.85f) {
                        if (fsReasserts < 2) {
                            fsReasserts++;
                            fsPreview.setRenderHz(want);
                            Refresh.apply(MainActivity.this);
                            Log.i(TAG, "帧率掉档，重新申请 " + fsReasserts + "/2 want=" + want);
                        } else if (fsReasserts == 2) {
                            fsReasserts++;
                            Log.i(TAG, "系统把帧率按在 " + (int) hz
                                    + "Hz，停止重复申请（避免 120/60 锯齿）");
                        }
                    }
                }
            }
            fsLastDraw = n;
            fsLastAt = now;
            uiHandler.postDelayed(this, 1000L);
        }
    };

    /** 屏幕当前实际刷新率（不是我们请求的值） */
    private float displayHz() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            return d == null ? 0f : d.getMode().getRefreshRate();
        } catch (Throwable t) {
            return 0f;
        }
    }

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
        tvLiveHint = findViewById(R.id.tvLiveHint);
        updateLiveHint();
        if (swLiveCard != null) {
            swLiveCard.setChecked(Prefs.liveCard(this));
            swLiveCard.setOnCheckedChangeListener((btn, checked) -> {
                Prefs.setLiveCard(MainActivity.this, checked);
                try {
                    if (checked) {
                        TextCard.get().start(MainActivity.this);
                        // 第一次打开时讲清楚"卡片"和"上岛"的区别，之后靠常驻小字
                        if (!Prefs.liveTipShown(MainActivity.this)) {
                            Prefs.setLiveTipShown(MainActivity.this, true);
                            showLiveTip();
                        }
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
                // 档位不只是限自己的重绘节奏，还要向系统申请窗口刷新率，
                // 否则在把普通应用按 60Hz 喂的 ROM 上，"自动/120"都还是 60
                Refresh.apply(this);
            });
        }
        preview.setRenderHz(Prefs.vizHzValue(hzSel));
        Refresh.apply(this);

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

        // ----屏幕常亮 ----
        swKeepOn = findViewById(R.id.swKeepOn);
        if (swKeepOn != null) {
            swKeepOn.setChecked(Prefs.keepOn(this));
            swKeepOn.setOnCheckedChangeListener((bv2, checked) -> {
                Prefs.setKeepOn(this, checked);
                applyKeepOn();
            });
        }
        applyKeepOn();

        // ----全屏是否隐藏系统栏（个别 ROM 在沉浸态会锁 60Hz，留个开关）----
        swFsBars = findViewById(R.id.swFsBars);
        if (swFsBars != null) {
            swFsBars.setChecked(Prefs.fsHideBars(this));
            swFsBars.setOnCheckedChangeListener((bv3, checked) -> {
                Prefs.setFsHideBars(this, checked);
                if (fsActive) applyImmersive(checked);
            });
        }

        // ----全屏是否允许横屏（关掉可对比"转屏请求是否触发降档"）----
        swFsLand = findViewById(R.id.swFsLand);
        if (swFsLand != null) {
            swFsLand.setChecked(Prefs.fsAllowLandscape(this));
            swFsLand.setOnCheckedChangeListener((bv4, checked) -> {
                Prefs.setFsAllowLandscape(this, checked);
                if (fsActive) {
                    try {
                        setRequestedOrientation(checked
                                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR
                                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        // ----全屏预览：点预览区或左上角「全屏」进，点画面 /返回键出 ----
        fsLayer = findViewById(R.id.fsLayer);
        fsPreview = findViewById(R.id.fsPreview);
        fsTip = findViewById(R.id.fsTip);
        fsEntry = findViewById(R.id.fsEntry);
        View previewCard = findViewById(R.id.previewCard);
        if (previewCard != null) previewCard.setOnClickListener(v -> enterFullscreen());
        if (fsEntry != null) fsEntry.setOnClickListener(v -> enterFullscreen());
        if (fsLayer != null) fsLayer.setOnClickListener(v -> exitFullscreen());

        // ----预设 ----
        presetRow = findViewById(R.id.presetRow);
        presetScroll = findViewById(R.id.presetScroll);
        presetHint = findViewById(R.id.presetHint);
        TextView btnPresetSave = findViewById(R.id.btnPresetSave);
        if (btnPresetSave != null) {
            btnPresetSave.setOnClickListener(v -> {
                String saved = Presets.save(this, Presets.capture(this));
                if (saved == null) {
                    showMessage("预设最多 " + Presets.MAX + " 条，长按列表里旧的删掉再存");
                    return;
                }
                rebuildPresetRow();
                Toast.makeText(this, "已保存预设：" + saved, Toast.LENGTH_SHORT).show();
            });
        }
        rebuildPresetRow();

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
        // 回到前台时重新对齐窗口标志（别的界面可能会改动它）
        applyKeepOn();
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(diagTick);
        // 切到后台时退出全屏，避免回来看到"卡在全屏"的状态
        if (fsActive) exitFullscreen();
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

    @Override
    public void onBackPressed() {
        // 全屏时返回键先退出全屏，不要直接退出应用
        if (fsActive) {
            exitFullscreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 下拉通知栏、切回来时系统会把栏放出来，这里补一次隐藏
        if (hasFocus && fsActive) applyImmersive(Prefs.fsHideBars(this));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖切换后系统栏会重新出现，补一次隐藏；全屏层本身是 match_parent，会自己重排
        if (fsActive) applyImmersive(Prefs.fsHideBars(this));
        // 转屏后重新申请一次刷新率（有的 ROM 会在换模式时把窗口的偏好值丢掉）
        Refresh.apply(this);
        Log.i(TAG, String.format(Locale.US, "转屏 %s displayHz=%.1f wantHz=%d",
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ? "横屏" : "竖屏",
                displayHz(), Refresh.targetHz(this)));
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
                swFreqAxis, swModeLabel, swOverlay, swIdle, swDiag, swStereo, swKeepOn,
                swFsBars, swFsLand};
        for (Switch swX : sws) {
            if (swX != null) {
                swX.setThumbTintList(thumbCl);
                swX.setTrackTintList(trackCl);
            }
        }
        updateAudioHint();
        applyTitleGradient();
        // 设置一变就重算"当前高亮的是哪条预设"
        syncPresetSelection();
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
        // 状态变化（开始/停止监听等）后立刻重判一次自检：已畅通就撤掉残留提示
        if (message != null && Prefs.liveCard(this)) checkLiveCardDiag();
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
     * 「上岛」（状态栏胶囊 / 实况窗）对系统的要求，和「卡片」（常驻通知）不是一回事：
     * 卡片任何系统都能显示；上岛需要 Android 16+ 且厂商实现了实时通知。
     * 这里给出本机的实际情况，避免用户在旧系统上开了开关以为坏了。
     */
    private String liveHintText() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < 36) {
            return "本机 Android " + sdk + "。状态栏胶囊 / 实况窗通常需要 Android 16 及以上、"
                    + "且厂商实现了实时通知；本开关仍会显示常驻通知卡片（歌名 + 实时数据）。"
                    + "部分国产 ROM 自带实况窗可能可用，以实际显示为准。";
        }
        boolean can = true;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            can = nm == null || nm.canPostPromotedNotifications();
        } catch (Throwable ignored) {
        }
        if (!can) {
            return "系统未允许提升通知：到 设置 › 通知与状态栏 › 流体云 / 实时活动 打开总开关；"
                    + "若找不到该开关，说明本机未实现实时通知。";
        }
        return "";
    }

    private void updateLiveHint() {
        if (tvLiveHint == null) return;
        String s = liveHintText();
        if (s == null || s.isEmpty()) {
            tvLiveHint.setVisibility(View.GONE);
        } else {
            tvLiveHint.setText(s);
            tvLiveHint.setVisibility(View.VISIBLE);
        }
    }

    /** 首次打开「状态栏实时卡片」时的一次性说明 */
    private void showLiveTip() {
        String extra = liveHintText();
        String msg = "「卡片」和「上岛」是两件事：\n\n"
                + "• 常驻通知卡片：任何系统都能显示歌名与实时数据。\n"
                + "• 上岛（状态栏胶囊 / 实况窗）：需要 Android 16 及以上，"
                + "且厂商实现了实时通知，同时系统侧总开关要打开。\n\n"
                + (extra.isEmpty()
                ? "本机已满足上岛条件；若状态栏没出现胶囊，点「一键授权」或检查系统总开关。"
                : extra);
        try {
            new ConfirmDialog(this, Palette.byIndex(paletteIdx),
                    "关于「上岛」", msg, "知道了", null, false).show();
        } catch (Throwable t) {
            Log.i(TAG, "live tip fail " + t);
        }
    }

    private String liveCardDiag() {
        if (!Prefs.liveCard(this)) return "";
        // 先看最硬的证据：监听服务真的连上了，就说明授权一定是通的
        //（避免读到 Settings 的旧缓存，误报"未开启"）
        if (!MediaWatcher.listenerConnected()) {
            if (!ShizukuHelper.hasNotificationListener(this)) {
                return "上岛自检：通知使用权未开启 ——点「一键授权」可自动开启";
            }
            if (OverlayService.isRunning()) {
                return "上岛自检：监听服务未连上 ——点「一键授权」重绑（需 Shizuku）；"
                        + "否则到系统设置 › 通知使用权 里关掉再打开本应用";
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

    /**
     * 自检：有阻碍时把原因写到提示区，并每 5 秒复检一次（最多 6 次 / 30 秒）。
     * 一旦阻碍消失就撤掉这条提示——否则"启动瞬间还没连上"会被永久挂在界面上。
     * 只撤自己写的文案，别的提示（授权结果、错误等）不动。
     */
    private void checkLiveCardDiag() {
        String diag = liveCardDiag();
        String shown = message.getVisibility() == View.VISIBLE ? String.valueOf(message.getText()) : "";
        boolean ours = !liveCardAutoMsg.isEmpty() && shown.equals(liveCardAutoMsg);

        if (diag.isEmpty()) {
            if (ours) {
                clearMessage();
                Log.i(TAG, "livecard ok, cleared");
            }
            liveCardAutoMsg = "";
            liveCardRecheck = 0;
            return;
        }

        // 提示区被别的提示占用（且不是上次自检写的），让位，只留日志
        if (!shown.isEmpty() && !ours) {
            Log.i(TAG, "livecard busy: " + diag);
            return;
        }

        if (!diag.equals(liveCardAutoMsg)) {
            liveCardAutoMsg = diag;
            liveCardRecheck = 0;
        }
        Log.i(TAG, "livecard " + diag);
        showMessage(diag);

        if (liveCardRecheck < 6) {
            liveCardRecheck++;
            uiHandler.removeCallbacks(liveCardCheck);
            uiHandler.postDelayed(liveCardCheck, 5000);
        }
    }

    // ------------------------------------------------------------ 全屏预览 /常亮

    /** 把主预览的可视化参数复制给全屏视图（两者读同一份引擎数据，只是画布不同） */
    private void copyVisualSettings(VisualizerView from, VisualizerView to) {
        if (from == null || to == null) return;
        to.setStyle(Style.byIndex(styleIdx));
        to.setPalette(Palette.byIndex(paletteIdx));
        to.setStereo(curStereoMode());
        to.setPeakMode(Prefs.peakGlow(this));
        to.setFreqAxis(Prefs.freqAxis(this));
        to.setIdleAnim(Prefs.idleAnim(this));
        to.setSmoothTau(Prefs.vizSmoothTau(this));
        to.setRenderHz(Prefs.vizHzValue(Prefs.vizHz(this)));
    }

    private void enterFullscreen() {
        if (fsActive || fsLayer == null || fsPreview == null) return;
        copyVisualSettings(preview, fsPreview);
        // 全屏时模式标签走"顶部居中"锚点，底部整条留给画面
        fsPreview.setShowModeLabel(true);
        fsPreview.setModeLabelAnchor(2);
        fsPreview.setLabelTopDp(24f);
        fsActive = true;
        fsLayer.setAlpha(0f);
        fsLayer.setVisibility(View.VISIBLE);
        fsLayer.animate().alpha(1f).setDuration(160).start();
        if (fsTip != null) {
            fsTip.animate().cancel();
            if (Prefs.diag(this)) {
                // 开"实时诊断"时这行留着当帧率表，由 fsTick 每秒刷新
                ((TextView) fsTip).setText("…");
                fsTip.setAlpha(0.8f);
            } else {
                ((TextView) fsTip).setText("轻触画面退出全屏");
                fsTip.setAlpha(1f);
                // 提示只留两秒，之后让画面自己说话
                fsTip.animate().alpha(0f).setStartDelay(2200).setDuration(600).start();
            }
        }
        // 浮窗会盖在画面上面，全屏期间先替用户收起来（不改设置，退出时还原）
        requestTempOverlayHide(true);
        // 主预览这会儿被全屏层盖住，停掉它的自驱重绘省一半绘制量；
        // 但保持 VISIBLE——不可见的视图会从窗口的帧率投票里退出，实测会让系统直接降到 60
        if (preview != null) {
            preview.setSelfDrive(false);
        }
        // 全屏允许横屏（可在设置里关掉对比）；关掉时维持竖屏锁定
        if (Prefs.fsAllowLandscape(this)) {
            try {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            } catch (Throwable ignored) {
            }
        }
        // 旋转 / 换窗口后重新申请一次刷新率，并记下系统真正在跑的 Hz
        Refresh.apply(this);
        Log.i(TAG, String.format(Locale.US, "全屏进入 displayHz=%.1f wantHz=%d",
                displayHz(), Refresh.targetHz(this)));
        uiHandler.removeCallbacks(fsTick);
        fsLastDraw = -1L;
        fsLastAt = 0L;
        fsReasserts = 0;
        uiHandler.postDelayed(fsTick, 1200L);
        // 视图到这一步才真正可见，帧率申请在可见之后重申一次
        //（对 GONE 视图调 setRequestedFrameRate 有可能不生效）
        fsPreview.setRenderHz(Prefs.vizHzValue(Prefs.vizHz(this)));
        Refresh.apply(this);
        applyImmersive(Prefs.fsHideBars(this));
        applyKeepOn();
    }

    private void exitFullscreen() {
        if (!fsActive) return;
        fsActive = false;
        uiHandler.removeCallbacks(fsTick);
        // 把主预览的绘制恢复回来（全屏期间它被挂起了）
        if (preview != null) {
            preview.setVisibility(View.VISIBLE);
            preview.setSelfDrive(true);
            preview.setRenderHz(Prefs.vizHzValue(Prefs.vizHz(this)));
            preview.invalidate();
        }
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        // 先把浮窗还回去，再处理画面与系统栏
        requestTempOverlayHide(false);
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Throwable ignored) {
        }
        if (fsLayer != null) fsLayer.animate().cancel();
        if (landscape && fsLayer != null) {
            // 横屏退出：先留住画面，等转回竖屏再撤层 + 放系统栏，
            // 否则会闪一下"横版主界面 + 系统栏压在画面上"
            uiHandler.postDelayed(() -> {
                if (fsActive) return;
                fsLayer.setVisibility(View.GONE);
                applyImmersive(false);
            }, 380);
        } else {
            if (fsLayer != null) fsLayer.setVisibility(View.GONE);
            applyImmersive(false);
        }
        applyKeepOn();
    }

    /**
     * 全屏期间临时收起 / 还原浮窗。
     * 只发 ACTION_TEMP_OVERLAY，不写 Prefs.overlayEnabled——
     * 用户"显示悬浮窗"的开关以及悬浮窗位置都不会被动到。
     */
    private void requestTempOverlayHide(boolean hide) {
        if (!OverlayService.isRunning()) {
            fsOverlayHidden = false;
            return;
        }
        if (hide) {
            // 用户本来就没让浮窗显示（或已经用 ✕ 收起了），那就不用管
            if (!Prefs.overlayEnabled(this)) {
                fsOverlayHidden = false;
                return;
            }
            fsOverlayHidden = true;
        } else if (!fsOverlayHidden) {
            return;
        }
        try {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_TEMP_OVERLAY)
                    .putExtra(OverlayService.EXTRA_TEMP_HIDE, hide));
        } catch (Exception ignored) {
        }
        if (!hide) fsOverlayHidden = false;
    }

    /** 隐藏 / 恢复系统栏：API 30 起用 WindowInsetsController，26~29 退回旧的 systemUiVisibility */
    private void applyImmersive(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                // 上滑可临时唤出系统栏，几秒后自动再隐藏
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            } else {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (on) {
                flags |= View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** 常亮 = 开关打开 或 正在全屏预览（全屏时一定不熄屏） */
    private void applyKeepOn() {
        boolean on = fsActive || Prefs.keepOn(this);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------ 预设

    /** 依当前存储重建预设横滑条 */
    private void rebuildPresetRow() {
        if (presetRow == null) return;
        java.util.List<Presets.Item> items = Presets.list(this);
        presetRow.removeAllViews();
        presetItems = items;
        presetChips = new ChipView[items.size()];
        if (presetScroll != null) {
            presetScroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (presetHint != null) {
            presetHint.setText(items.isEmpty()
                    ? "把当前模式、配色、灵敏度、平滑一起存下来，之后一键切回"
                    : "点一下套用，长按删除（最多 " + Presets.MAX + " 条）");
        }
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < items.size(); i++) {
            final Presets.Item it = items.get(i);
            ChipView chip = new ChipView(this, null);
            chip.setLabel(it.name);
            chip.setPalette(Palette.byIndex(it.palette));
            chip.setChecked(false);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            if (presetRow.getChildCount() > 0) {
                lp.leftMargin = (int) (8 * d);
            }
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> applyPreset(it));
            chip.setOnLongClickListener(v -> {
                confirmDeletePreset(it);
                return true;
            });
            presetRow.addView(chip);
            presetChips[i] = chip;
        }
        syncPresetSelection();
    }

    /** 把"当前设置就是这套"的预设高亮出来（其余保持描边态） */
    private void syncPresetSelection() {
        if (presetChips == null || presetChips.length == 0) return;
        if (presetItems == null || presetItems.size() != presetChips.length) return;
        Presets.Item cur = Presets.capture(this);
        for (int i = 0; i < presetChips.length; i++) {
            if (presetChips[i] == null) continue;
            presetChips[i].setChecked(sameSettings(presetItems.get(i), cur));
        }
    }

    private static boolean sameSettings(Presets.Item a, Presets.Item b) {
        if (a == null || b == null) return false;
        return a.style == b.style
                && a.palette == b.palette
                && a.smooth == b.smooth
                && a.peak == b.peak
                && a.stereoOn == b.stereoOn
                && a.stereoMode == b.stereoMode
                && a.autoSens == b.autoSens
                && a.freqAxis == b.freqAxis
                && a.idle == b.idle
                && a.vizHz == b.vizHz
                && Math.abs(a.sens - b.sens) < 0.02f
                && (a.palette != Palette.CUSTOM.ordinal()
                || (a.c0 == b.c0 && a.c1 == b.c1 && a.c2 == b.c2));
    }

    /** 套用一条预设：先写 Prefs，再把视图与控件同步过去 */
    private void applyPreset(Presets.Item it) {
        Presets.apply(this, it);
        styleIdx = it.style;
        paletteIdx = it.palette;

        preview.setSmoothTau(Prefs.smoothTauOf(it.smooth));
        preview.setFreqAxis(it.freqAxis);
        preview.setIdleAnim(it.idle);
        preview.setRenderHz(Prefs.vizHzValue(it.vizHz));
        AudioEngine.get().setSensitivity(it.sens);
        AudioEngine.get().setAutoSens(it.autoSens);
        // 自定义配色的色板要跟着重取，否则色块还停在旧颜色上
        for (int i = 0; i < swatches.length; i++) {
            swatches[i].setPalette(Palette.byIndex(i));
        }
        // 这些监听器都是幂等的：值没变就什么都不做，变了就当用户手动改了一次
        if (swStereo != null) swStereo.setChecked(it.stereoOn);
        if (swIdle != null) swIdle.setChecked(it.idle);
        if (swAutoSens != null) swAutoSens.setChecked(it.autoSens);
        if (swFreqAxis != null) swFreqAxis.setChecked(it.freqAxis);
        if (seekSens != null) seekSens.setProgress(sensToProgress(it.sens));
        if (hzChips != null) {
            for (int i = 0; i < hzChips.length; i++) {
                hzChips[i].setChecked(i == it.vizHz);
            }
        }
        Refresh.apply(this);
        applySelections();
        syncOverlay();
        Toast.makeText(this, "已套用 " + it.name, Toast.LENGTH_SHORT).show();
    }

    private void confirmDeletePreset(Presets.Item it) {
        // 用项目自己的深色确认框，跟界面同一套配色（系统 AlertDialog 是白底的）
        new ConfirmDialog(this, Palette.byIndex(paletteIdx),
                "删除预设",
                "确定删除「" + it.name + "」？删除后要重新调一遍才能存回来。",
                "删除", () -> {
            Presets.delete(this, it.name);
            rebuildPresetRow();
        }).show();
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