# Aurora Viz

Android 音频可视化应用：**悬浮窗 + 应用内双通道显示**，纯 Java 原生 Canvas 绘制，无第三方图形库。

> 版本：v8.8 (versionCode 158) · minSdk 26 / targetSdk 36 · 单 APK 约 141 KB

<img src="docs/screenshot.jpg" width="340" alt="截图">

---

## 功能

### 可视化

| 项目 | 说明 |
|---|---|
| 6 种模式 | 柱状（带 dB 纵轴）、示波（零轴网格）、环形 96 柱、三层径向波形、64 点粒子云、56 瓣极坐标圆盘 |
| 7 套配色 | 霓虹 / 银色 / 彩虹 / 海洋 / 日落 / 极简 / 自定义 |
| 自定义调色 | HSV 色域板 + 色相条 + 三节点渐变（起始/中间/结束），实时预览 |
| 峰值弹道 | 参考 `audioMotion-analyzer` 默认值：hold 500 ms + fade 750 ms，纯时间驱动，与帧率/灵敏度解耦 |
| 峰值样式 | 发光粒子 / 细线 / 关闭，三态可选 |
| 待机动画 | 四层正弦合成 + 呼吸动画，可开关 |
| 纵轴 | 0 / -16 / -32 / -48 dB 刻度与网格 |

### 音频

| 项目 | 说明 |
|---|---|
| 麦克风 | `AudioRecord` + Hann 窗 FFT（2048 点），48 对数频带 40–16000 Hz，192 点波形 |
| 系统音频 | `AudioPlaybackCapture`（Android 10+），支持 Android 14 的「整个屏幕 / 单个应用」选择 |
| 平滑 | 快攻慢释（0.75 / 0.87），线程私有缓冲，每帧仅加锁一次 |
| 节拍检测 | 低频能量超过滑动均值 1.25 倍且间隔 > 180 ms |

### 悬浮窗

- 拖动移动、**位置记忆**（下次启动直接回到原位，带屏幕边界 clamp）
- 双击切换模式
- **双指捏合缩放**浮窗尺寸（160–660 dp，松手落盘）
- **长按隐藏**浮窗（不打断监听）
- **透明度** 25%–100% 实时可调
- **透明背景**：不画卡片底色，只留波形悬浮在桌面
- 右上角 ✕ **只隐藏浮窗、监听继续**
- 独立于监听的开关：可以只在应用内看可视化
- 实时诊断行：`N 帧 · 电平 X dB · 静音 Y s`，用于区分「0 帧（应用禁止被录制）」与「有帧但静音」

### 导出页面

主界面「导出可视化视频…」进入独立页面（`ExportActivity`）：

- **导入本地音频**（mp3 / wav / flac / m4a / ogg）：`MediaExtractor` + `MediaCodec` 解码 → `AudioTrack` 播放 → PCM 实时喂给 `AudioEngine` 做频谱分析
- 预览区实时显示当前模式与配色
- 时长可选 **15 / 30 / 60 秒 / 整首**
- 「导出时静音播放」：不发声，只渲染画面
- 不选文件时也可以直接录当前监听画面

输出到公共目录：

```
/sdcard/Movies/AuroraViz/aurora_yyyyMMdd_HHmmss.mp4
```

- 规格：960×540 · 30 fps · H.264 High · 约 700 kb/s（Android 10+ 通过 MediaStore 写入，相册可见）
- 录制中按钮显示剩余秒数，再次点击可提前结束（已录部分仍是完整可播文件）
- 管线：独立 `VisualizerView` → `Bitmap`(Canvas) → `GlBlitter`(OpenGL ES) → `MediaCodec` 输入 Surface → `MediaMuxer`
- 说明：MediaCodec 的输入 Surface **不能 `lockCanvas`**，必须走 GL；画面与界面上的实例分离，互不干扰

---

## 构建

环境要求：JDK 17、Gradle 8.9、Android SDK Platform 34、Build-Tools 34.0.0。

```bash
# 若 Gradle 无法自启 aapt2 daemon，需要显式指定 SDK 内的二进制
echo "android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2" >> gradle.properties

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/av.apk
adb shell pm install -r /data/local/tmp/av.apk
```

> 直接 `adb install` 到 `/sdcard` 路径在部分设备上会因 FUSE 上下文报错，建议先 push 到 `/data/local/tmp`。

---

## 权限

| 权限 | 用途 |
|---|---|
| `RECORD_AUDIO` | 麦克风拾音 / 音频回放捕获 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 |
| `FOREGROUND_SERVICE` + `MICROPHONE` / `MEDIA_PROJECTION` | 前台服务持续监听 |
| `POST_NOTIFICATIONS` | 常驻通知（Android 13+） |
| `WAKE_LOCK` | 息屏时保持采集 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku 一键授权（可选） |

「一键授权（Shizuku）」会执行：

```sh
appops set <pkg> SYSTEM_ALERT_WINDOW allow
pm grant <pkg> android.permission.RECORD_AUDIO
pm grant <pkg> android.permission.POST_NOTIFICATIONS
# 通知使用权（上岛依赖）：先解绑再绑定，顺带修掉"设置里已授权但服务没连上"的僵尸绑定
cmd notification disallow_listener <pkg>/<pkg>.MediaWatcherService
cmd notification allow_listener    <pkg>/<pkg>.MediaWatcherService
```

授权后 App 会回查系统设置确认结果；失败时提示区会说明该去哪里手动开。

---

## 已知限制

**系统音频捕获无法录制「禁止被录音」的应用。**

若目标应用在 manifest 中声明 `android:allowAudioPlaybackCapture="false"`（国内主流音乐 App 普遍如此），系统层不会输出任何音频帧，UI 会显示 `0 帧` 并给出提示。这是 Android 平台的 DRM 级限制，Shizuku / adb / 重装都无法绕过，需要 root + `REMOTE_SUBMIX`。

区分方法看诊断行：

| 诊断读数 | 含义 |
|---|---|
| `0 帧 · 电平 -∞ dB` | 目标应用禁止被录制（系统级拒绝） |
| `669 帧 · 电平 -15 dB · 静音 0.0s` | 捕获正常 |

**导出的视频目前不含音轨**（v3.1）。录制只走视频编码，避免与系统音频捕获争用 `MediaProjection`；后续版本会加 PCM tee + AAC 混流。

---

## 系统与兼容性

| 项 | 要求 |
|---|---|
| 最低系统 | Android 8.0（API 26） |
| 编译 / 目标 | compileSdk 36 · targetSdk 36 |
| 状态栏实时卡片（「上岛」） | **Android 16（API 36）及以上，且厂商实现了实时通知** |
| 实测环境 | 一加 PLQ110 · ColorOS V16.0.0（Android 16 / SDK 36） |

### 「上岛」到底是什么条件

App 只是发起请求，是否显示成胶囊/卡片由系统决定。代码侧满足的条件：

- 声明并持有 `POST_PROMOTED_NOTIFICATIONS`（targetSdk 36 时普通权限，安装即有）
- 常驻通知带 `extras.putBoolean("android.requestPromotedOngoing", true)`
- `setOngoing(true)` + `setOnlyAlertOnce(true)` + `setShowWhen(false)`
- 只允许的样式（本项目用 `BigTextStyle`），且**不能** `setColorized(true)`
- 胶囊上的短文本用 `setShortCriticalText()`，实际只显示 7 个字符以内

系统侧还必须开着「流体云 / 实时活动」总开关（ColorOS：设置 › 通知与状态栏）。**这是用户级开关，App 无权也无法代开**，只能在检测到未开时提示。

### 已测 / 未测

| 环境 | 表现 |
|---|---|
| ColorOS 16（Android 16，一加 PLQ110） | ✅ 已实测上岛：胶囊 + 展开卡片均正常，`PROMOTED_ONGOING` 标志稳定 |
| 其他厂商 Android 16（HyperOS / OriginOS / MagicOS / One UI 等） | ⚠️ **未实测**。只要厂商遵循同一套 API，理论上同样生效 |
| Android 15 及以下 | ❌ 无该 API。「状态栏实时卡片」开关不起作用，通知仍照常留在通知栏，前台服务与悬浮窗不受影响 |
| 系统关闭流体云总开关 | ❌ 上岛失败，App 在提示区给出具体原因 |

> 一点经验：通知使用权"设置里显示已授权"不等监听服务真的连上。这类僵尸绑定要用 `cmd notification disallow_listener` + `allow_listener` 解绑重绑才能恢复。

---

## 待实测清单

以下项目**尚未在真机上验证过**，都属"能用但没测全"，欢迎反馈：

| 分类 | 待测项 |
|---|---|
| 上岛 | 非 ColorOS 的 Android 16 机型（HyperOS / OriginOS / MagicOS / One UI）是否上岛；各厂商对 `setShortCriticalText` 的截断规则 |
| 上岛 | 总开关从关到开的过程中，自检提示能否正确消失 |
| 场景 | 分屏 / 小窗 / 游戏空间下卡片是否继续显示；息屏后是否仍在更新 |
| 场景 | 蓝牙耳机切换、来电打断、多个播放器抢音频焦点时的卡片与读数表现 |
| 场景 | 应用分身（多用户）下的隐藏判定：目前只在 B 站分身上验证过，其他 App 分身未测 |
| 稳定性 | 连续数小时监听的内存与耗电；被系统回收后前台服务与通知能否自动恢复 |
| 采集 | 「禁止被录音」应用的完整清单：目前只确认国内主流音乐 App 全部拒绝 |
| 授权 | Shizuku 服务重启／授权被撤销后，一键授权与自检提示能否正确引导 |
| 导出 | 各分辨率 / 帧率组合的音视频同步；超长音频（> 30 min）导出稳定性 |
| 兼容 | Android 8–15 上各界面与权限流程（尤其 13 以下的 `POST_NOTIFICATIONS` 缺失场景） |

---

## 代码结构

```
app/src/main/java/com/operit/audioviz/
├── MainActivity.java        设置界面：模式/配色/调色器/灵敏度/尺寸/开关
├── OverlayService.java      前台服务：悬浮窗 + AudioEngine 生命周期
├── MediaWatcher.java        读当前播放器（媒体会话 / 分身通知 / Shizuku 读前台应用）
├── MediaWatcherService.java NotificationListenerService：通知使用权入口，驱动 MediaWatcher
├── TextCard.java            状态栏实时卡片（Android 16 promoted ongoing notification）
├── ShizukuHelper.java       Shizuku 一键授权（悬浮窗 / 录音 / 通知 / 通知使用权）
├── AudioAnalysis.java       响度 / 峰值 / BPM / 音高 / 削波判定
├── AudioEngine.java         录音、FFT、频带/波形输出（单例）
├── VisualizerView.java      全部绘制：6 种模式 + 纵轴 + 峰值弹道 + 待机动画
├── Prefs.java               偏好读写
├── Scheme.java              调色板 → 实际颜色（自定义配色解析）
├── Palette.java / Style.java 配色与模式枚举
├── ChipView.java            胶囊选择按钮（渐变填充 + 纯色描边，双 Paint 分离）
├── GradientButton.java      主按钮（渐变 + 顶部高光 + 外发光）
├── GhostButton.java         次级按钮
├── SwatchView.java          圆形主题色块
├── SatValView.java          HSV 色域板
├── HueBar.java              色相条
├── ColorPickerDialog.java   三节点自定义调色面板
├── ExportActivity.java      导出页面：导入音频 / 时长选择 / 预览 / 导出
├── FileAudioPlayer.java     本地音频解码 + 播放 + PCM 实时喂给 AudioEngine
├── GlBlitter.java           OpenGL ES 桥：Bitmap → MediaCodec 输入 Surface
└── VideoRecorder.java       录制导出：独立 View → Bitmap → GL → H.264 → MP4（公共 Movies 目录）
```

### 一个值得记录的坑

`Paint.setColor()` 会同时设置 Paint 自身的 alpha，而 `setShader()` **不会重置** alpha，两者是**相乘**关系：

```java
paint.setColor(0x14FFFFFF);          // alpha = 20/255 ≈ 7.8%
paint.setShader(gradient);           // 渐变亮度被乘上 7.8% → 看起来是「发黑」
```

表现为「首帧正常、点一下就变黑」。项目内所有 `setShader(...)` 之前都强制 `setAlpha(255)`。

---

## 版本记录

| 版本 | 内容 |
|---|---|
| 1.0 | 首版：麦克风 + 系统音频捕获、4 种环形样式、悬浮窗 |
| 1.1 | 按参考项目重做 UI；移植环形/径向/粒子/圆盘 |
| 1.2 | 悬浮窗与应用内解耦；界面风格；银色/彩虹主题；纵向柱状 + 纵轴 |
| 1.3 | 修复柱状崩溃（待机合成值为负 → `pow(负数)` = NaN → 渐变坐标 NaN） |
| 1.5–1.7 | 峰值标记三态可选；改用像素间隙判据，修复低灵敏度闪烁 |
| 1.8 | 修复峰值样式标签与内部枚举错位（点「发光点」出现细线） |
| 1.9 | 峰值弹道改为时间驱动（hold 500 ms / fade 750 ms） |
| 2.0 | 待机不产生峰值；待机动画相位错开；布局重叠修复 |
| 2.1 | 自定义 HSV 调色（色域板 + 色相条 + 三节点渐变） |
| 2.2 | 实时诊断行（帧数 / 电平 / 静音时长） |
| 2.3 | 待机幅度提升、主题色块单行 7 个、按压反馈、待机动画开关 |
| 2.6 | **修复 Paint alpha 与 shader 相乘导致的「按钮变黑」**（13 处加固） |
| 2.7 | 峰值与柱共用 `pow(v,0.8)` 映射，光晕不跨柱（实测 179 点 0 异常） |
| 2.8 | **浮窗 ✕ 只隐藏不停止监听**，开关状态双向同步 |
| 3.0 | 浮窗增强：透明度、透明背景、位置记忆、双指缩放、长按隐藏；新增 MP4 录制导出 |
| 3.1 | 导出目录改为公共 `Movies/AuroraViz`（MediaStore，相册可见），不再写进 `Android/data` |
| 3.2 | 独立导出页面：导入本地音频渲染、时长 15/30/60/整首自选、静音渲染开关 |
| 3.6–3.8 | 两条导出路径都带音轨；立体声采集 + AAC 独立编码线程；720p / 12Mbps |
| 3.9 | VBR + High@4.1 + 50fps，画面更顺 |
| 4.0 | 导出参数可调（分辨率 / 帧率 / 码率）+ 监听状态提示 |
| 4.1 | 可视化按时间插值，修高帧率「抽帧」；画面平滑三档 |
| 4.2 | 修低频柱等高（中心频率插值）；可视化帧率四档 |
| 4.3 | 可视化帧率控件移出水平行，修布局被撑高 |
| 5.0–5.2 | 尝试瀑布频谱 / 表盘 / Bloom 辉光，效果不达预期后撤销 |
| 5.3 | 监听中可直接热切换音源（麦克风 / 系统音频） |
| 5.4 | 修复「正在切换音源」提示残留 |
| 5.5 | 新增「关于」页：版本、开源许可、免责、隐私、仓库地址 |
| 5.6 | 关于页与 README 补全许可证义务、第三方组件与免责条款 |
| 5.7 | 关于页新增「项目主页」入口；仓库 About 补上官网地址 |
| 5.8 | 高级设置新增「实时诊断」开关；诊断可区分「没在播放」与「应用禁止被录制」 |
| 5.9 | 修复实时诊断开关插入位置错误导致的布局空白 |
| 6.0 | 双声道可视化：左右声道各自 FFT，柱状/示波支持上下/左右/叠加三种布局 |
| 6.1 | 修复叠加模式递归绘制导致的 StackOverflowError（环形四样式） |
| 6.2 | 修复双声道布局 chip 选中态不亮（漏登记 applySelections） |
| 6.3 | 峰值重做：左右各自轨迹、光晕与下落按时间驱动 |
| 6.4 | 双声道整块仅在柱状/示波下出现，其余样式强制单声道 |
| 6.5 | 峰值自检：修抖动不断续期 hold、峰值未随节拍缩放两个问题 |
| 6.6–6.7 | 峰值参数回调；回退到 v6.2 的原始弹道（hold 500 ms + 750 ms 线性追平） |
| 6.8–6.9 | 加声道诊断日志（左右电平与差值），定位双声道差异过小 |
| 7.0–7.1 | 声道差异视觉增益 x2.5（以左右均值为中点），修复单声道下环形样式空白 |
| 7.2 | 自动灵敏度（AGC）+ 诊断行显示实时柱高百分比 |
| 7.3 | 悬浮窗三档预设尺寸（小/中/大）+ 松手吸附屏幕边缘 |
| 7.4–7.6 | 柱状底部频率轴带：标出 60 / 250 / 1k / 4k / 16k，与模式标签自动避让 |
| 7.7 | 声道诊断日志改为可开关（默认关闭）；频率轴带定稿 |
| 8.0–8.1 | 样式名尝试沿右边缘竖排（观感不佳，已撤销） |
| 8.2 | 样式名改为顶部居中横排；新增「悬浮窗样式标签」开关 |
| 8.3 | 浮窗标签上移到绘图框线之外 |
| 8.4 | 标签位置按宿主分开：应用内在右上（状态行下一行）、浮窗在顶部居中 |
| 8.5 | 修复关闭频率标注后浮窗标签落回右下、压住柱身与下层文字 |
| 8.6 | 新增响度表（RMS / 峰值 / 短时 LUFS）、节拍 BPM（带半速/倍速备选值）、音高音名与音分；修复 BPM 倍频误判、音高乱跳与边界伪峰、削波误报、停播后读数残留；诊断日志改为常开并支持一键导出分享 |
| 8.7 | 状态栏实时卡片接入 Android 16 的 promoted ongoing notification（ColorOS 流体云上岛）；刷视频自动隐藏改为三路判定（媒体会话 / 分身通知 / Shizuku 读前台应用）+ 15 秒宽限 + 即时撤卡，修掉分身场景下"空会话分支直接 return"导致判定全失效的根因；开关恢复系统原生外观并按状态着色（修 10 个开关关闭态不变色）；清理全部调试残留；修 `MediaWatcher.detach()` 静态单例泄漏；削波判定收紧到真实满刻度（连续 32 采样）；换用独立发布密钥签名 |
| 8.8 | Shizuku 一键授权新增「通知使用权」（先解绑再重绑，顺带修僵尸绑定），执行后回查系统设置确认结果，并让 `shell()` 捕获 stderr 以免失败被静默吞掉；新增上岛自检：通知使用权 / 监听是否真连上 / 通知权限 / `canPostPromotedNotifications()` 四项，只在有阻碍时提示原因（修掉读到设置旧缓存导致"授权成功却说没开"的误报） |

---

## 许可与声明

### 许可证

本项目以 **MIT License** 发布，全文见 [LICENSE](LICENSE)。

```
MIT License
Copyright (c) 2026 huoxingrNaNa
```

你可以自由使用、复制、修改、合并、发布、再分发、再授权，甚至商业销售，只需在所有副本或实质性部分中保留上述版权声明与本许可声明。本软件按“原样”提供，不附带任何明示或默示担保，包括但不限于适销性与特定用途适用性；作者不对任何索赔、损害或其他责任负责。

### 第三方组件

| 组件 | 许可 | 需要履行的义务 |
|---|---|---|
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | Apache-2.0 | 保留其版权与许可声明；不得使用其作者或项目名义为本项目背书 |
| AudioPlaybackCapture / MediaProjection | Android 系统接口 | 无 |

本项目不含 GPL / AGPL 代码，因此不承担整体开源传染义务；如需引入此类组件，请先评估许可证兼容性。

### 免责声明

音频采集与录制仅用于可视化欣赏与个人创作。请遵守当地法律法规与平台规则，不要用于录制或传播你无权录音、录屏的内容（例如受版权保护的音乐、受保密约束的通话与会议）。因使用本项目造成的任何直接或间接损失，包括录制失败、文件损坏、设备发热或异常，作者不承担任何责任。

### 隐私

应用不申请网络权限，不上传任何数据，不含广告与统计 SDK。音频分析与画面渲染全部在设备本地完成；导出文件写入相册目录 `Movies/AuroraViz`。
