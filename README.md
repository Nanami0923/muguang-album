# 暮光相册 · 家庭离线初版

[![Android checks](https://github.com/Nanami0923/muguang-album/actions/workflows/android.yml/badge.svg)](https://github.com/Nanami0923/muguang-album/actions/workflows/android.yml)

面向华为 M5 / HarmonyOS 2.0.0 的本地电子相册。Android 原生 APK，深色 Material 3 界面；最低系统声明为 Android 5.0 / API 21，图片和配乐均从用户授权的文件夹读取。

## 安装与使用

初版安装包可在 [v0.1.0 Release](https://github.com/Nanami0923/muguang-album/releases/tag/v0.1.0) 下载，本机交付目录为 `dist/暮光相册-v0.1.0.apk`。将 APK 复制到平板，使用系统文件管理打开安装；遇到安装来源提示时按系统正常流程授权。

1. 打开“暮光相册”，点击“选择图片文件夹”。建议照片放在 `Pictures/电子相册` 等独立目录。
2. 扫描完成后点击“开始播放”。可在“播放设置”调整间隔、排序、随机、循环、完整显示／裁切和转场。
3. 点击音乐图标，选择 `Music/相册配乐` 等音乐目录；开启背景音乐，选择顺序循环或随机循环并调整音量。
4. 播放时轻触画面显示控制条，可切换照片、暂停、控制音乐或设置停止倒计时。
5. 在“定时”创建每日／每周播放计划，再点击“进入展示模式”。计划外可低亮度待机或允许系统熄屏。

原图和音乐只读；移除来源、相册或测试数据只删除应用自己的索引。应用没有联网权限、云端功能、账号、人脸识别或视频功能。

## 已实现功能

| 模块 | 功能 |
| --- | --- |
| 相册 | 新建／切换／删除相册，多图片目录、子目录开关、刷新、来源状态与重新授权 |
| 浏览 | 缩略图网格、大图预览、自然文件名排序及修改时间排序 |
| 幻灯片 | 自定义 1～3600 秒、顺序／随机、循环、完整显示／裁切、淡入淡出、手势和按键切换 |
| 背景音乐 | 指定独立目录、顺序循环／每轮随机、上一首／下一首、独立暂停、音量、音频焦点与耳机断开处理 |
| 定时 | 15／30／60／120 分钟或自定义停止倒计时，每日／每周窗口、跨午夜与冲突拒绝 |
| 展示 | 常驻低亮度待机、省电模式、时钟；手动停止当前计划后本窗口不重复开播 |
| 提醒 | 可选系统非精确提醒、通知点击进入展示、重启／时间变化后重建提醒 |
| 外观 | 深色 Material 3、支持时使用系统动态色、宽窗口导航轨与双栏、窄窗口底部导航 |
| 持久化 | Room 相册／目录／索引／计划，DataStore 偏好；目录 URI 按来源保留 |

## 播放规则

- 配乐跟随前台幻灯片。切照片不断歌；暂停幻灯片同时暂停音乐；用户单独暂停音乐后不会因切图被恢复。
- 倒计时按真实经过时间计算，暂停幻灯片不延长倒计时；到时图片和音乐一并停止。
- 计划为左闭右开时间窗；例如周五 `22:00—次日07:00` 的星期属于周五。
- 手动播放优先，无关计划的开始／结束不打断手动会话的图片和配乐。
- 切后台、锁屏、退出和计划待机时停止输出声音；这不是独立的后台音乐播放器。
- 音乐文件无效时静音继续看图；所有图片均不可读时停止整个会话并提示检查来源。
- 冷启动和设备重启不自动出声，需要用户主动播放或进入展示模式。

## 平板适配与当前边界

- 华为 M5 / HarmonyOS 2.0.0 是主要目标设备，实际 Android API 应以平板报告的 `SDK_INT` 为准；本项目尚未在用户的 M5 实机验收。
- Android 5.0 的安装兼容声明与低版本接口静态检查已纳入构建；本机模拟器缺少加速支持，API 21 的运行测试暂未完成。
- Android 12 以下使用固定深色配色；不依赖 Google Play 服务或 HMS 账号。
- 普通应用不能保证在锁屏后准点点亮并越过锁屏。常驻展示需应用留在前台，待机画面并非真正熄屏。
- 后台提醒受系统调度影响，不能用来保证无人值守自动开播。
- 首版需手动刷新目录以纳入新增照片／歌曲；每次启动会检查已保存来源是否仍可访问。
- 图片按显示尺寸采样，默认关闭高成本模糊背景。音乐优先使用 MP3、标准 AAC-LC 或 PCM WAV；其他格式取决于设备解码能力。
- 相册删除会同时删除其关联计划，这是初版的明确行为。将来如需保留计划，可改为停用并重新绑定相册。
- 当前以文件名和修改时间排序；拍摄时间排序、文件监控、照片收藏及更复杂转场未纳入这个可安装初版。

## 开发与构建

环境：JDK 17、Android SDK Platform 36、Build Tools 36、Gradle 8.14.3。依赖锁定到仍兼容 API 21 的版本，不能直接全部升级到最新 AndroidX。

在本机 PowerShell 可运行：

```powershell
.\scripts\build.ps1
```

或使用标准 Gradle 命令：

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

首次构建需要下载开发依赖；这不影响安装后的离线运行。`local.properties` 只保存本机 SDK 路径，不应提交给其他设备。

Windows 中文路径下，JVM 测试输出通过 `gradle/ascii-test-classpath.gradle.kts` 暂存到 Gradle 用户目录的英文路径，以避免 Java 参数文件编码问题；源码与项目位置不变。

设备测试需要安装额外测试 APK；仅包含合成图片与静音 WAV，不读取私人图库：

```powershell
./gradlew.bat :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.muguang.album.test/androidx.test.runner.AndroidJUnitRunner
```

## 工程结构

```text
app/src/main/java/com/muguang/album/
  core/          共享模型与仓库接口
  data/          Room、DataStore、SAF 扫描和只读来源管理
  playback/      时间窗、自然排序和本地音乐引擎
  ui/            深色主题、相册、定时和设置页面
  presentation/  全屏照片、音乐控制、倒计时和待机画面
  reminder/      系统提醒与恢复接收器
  AlbumViewModel.kt    图片状态机、会话优先级与联动
  MainActivity.kt      文件夹授权、窗口与生命周期
```

测试记录见 `初版开发与测试说明.md`；详细设计见 `安卓平板电子相册完整方案.md`。

初版使用本机 Android 调试签名生成 APK。保存该签名以便后续覆盖安装；长期发布时再固定正式签名，密钥不放入项目源码。

## 版本维护

- [CHANGELOG.md](CHANGELOG.md)：每个版本的已完成改动，`v0.1.0` 为初版基线。
- [CONTRIBUTING.md](CONTRIBUTING.md)：后续分支、构建、升级签名及发布流程。
- [ROADMAP.md](ROADMAP.md)：M5 验收及后续候选更新。

提交到 `main` 或向其发起 PR 时，GitHub Actions 自动构建、运行单元测试和 Lint。安装包随 Release 发布，缓存、本机配置及密钥不入库。“智能照片轮播增量更新方案”是后续讨论稿，不属于当前版本已实现功能。
