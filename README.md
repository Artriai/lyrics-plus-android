# Lyrics Plus Android

一个基于 Android 的歌词同步显示原型应用。本项目受 PC 端 Spicetify 的 **lyrics-plus** 自定义应用启发，通过监听 Spotify 的本地媒体广播，在安卓设备上实现高颜值、丝滑过渡的歌词同步滚动与特效显示。

---

## ✨ 核心特性

- 🎵 **多源歌词切换**：
- 🇯🇵  **日语假名标注**：
- 🎨 **极佳的视觉特效**：
- ⏱️ **高精度本地同步**：

---

## 📋 更新日志

### 横屏布局重构 & 视觉增强

- **横屏右区歌词 33% 锚点滚动**：active 行固定在右区 33% 位置，上方保留 2 行已唱歌词（带模糊效果），超出隐藏
- **逐级模糊效果**：已过行 1→2 逐级模糊/降低透明度/缩放，未来行 1→8+ 逐级加深效果
- **逐字遮罩跟唱**：实时追踪每个音节的播放进度（progress 0→100%），唱完的音节保持白色不暗淡
- **减弱模糊强度**，放大左侧专辑图片至 300dp
- **横屏左侧专辑封面和播放按钮放大**：封面 150dp → 200dp，按钮外框 44dp → 56dp

### 聚焦模式交互升级

- **歌词区域手动滑动浏览**：touchstart/touchmove/touchend 实现手势滑动浏览，带惯性动画（easeOutQuint）和卡死自动恢复
- **滑动限位 + 更丝滑的 fling 动画**：硬边界夹紧 + 橡胶带回弹，惯性动画时长随速度自适应

### UI 模式切换（手机 / Pad）

- 新增 `deviceUiMode` 状态（0 = 手机 UI，1 = Pad UI），持久化到 SharedPreferences
- 设置菜单添加 UI 模式切换开关
- **手机模式竖屏**：注入 CSS 将 lyrics top 设为 7vh，选中歌词位于上部 2/5；header 压缩字体和内边距，减少歌词区域被下推
- **手机模式横屏**：缩小专辑封面（160dp）、左列权重（0.38）、字体和间距
- **Pad 模式**：保持原有布局，切换时自动恢复
- 手机模式竖屏 header 大小与 Pad 保持一致
- 修复横竖屏切换后手机模式歌词位置未正确更新（LaunchedEffect 添加 `isMultiPane` 依赖，resize 事件重置 `lyricsViewportEl`）

### 性能优化

- **暂停时大幅降低刷新率**：
  - WebView renderer：rAF 改为 start/stop 模式，暂停时停止 rAF 和 VRR keepalive hack
  - FloatingLyricsService：滴答循环从固定 8ms 改为播放 120Hz / 暂停 1Hz
  - SuperIslandLyricsService：滴答循环从固定 250ms 改为播放 250ms / 暂停 2000ms
  - AlbumArtPalette：添加 LruCache 缓存调色板提取，避免重复解析

### 问题修复

- 修复聚焦模式横向歌词布局时，未来行 blur 不刷新的 bug（扩大 patchFocusedMode 循环范围到 ±8）
- 修复换行时旧行颜色不恢复的问题（recacheActiveSyllables 准确查找旧 active 音节）
- 修复未来行 blur/opacity/scale 不更新的 bug
- 横屏 EmptyOverlay 分左右布局（左 45% 欢迎态，右 55% 搜索状态）
- 修复 resize 事件中 lyricsViewportEl 未重置导致容器尺寸计算错误

---

## ⚙️ 手机端配置指南

为了让 App 能够完美接收 Spotify 的播放状态与歌词进度，请在手机上进行以下设置：

### 1. 开启 Spotify 设备广播
在 Spotify Android 客户端中，依次点击：
`设置与隐私 -> 播放 -> 开启“设备广播状态” (Device Broadcast Status)`。
*(注：这是 Spotify 系统的安全机制，必须手动开启后，其他应用才能监听到当前的歌曲切换)*

### 2. 授予通知栏监听权限
首次打开 App 时，点击通知栏访问权（Notification Access）进行授权。
或者手动设置，在系统的特殊权限设置中：
`读取、回复和控制通知 -> 允许“Lyrics Plus”读取通知`。
* **为什么需要这个权限？**：
  * Spotify 本身的本地广播只在歌曲切换或播放状态改变时触发，不会频繁广播微秒级的当前进度。
  * 授权后，App 可以读取 Spotify 活跃媒体会话（Media Session）的播放位置。**即使您刚打开 App、刷新应用或手机息屏锁屏后返回，歌词进度也能瞬间对齐，无需等待下一首歌。**

---

## 🛠️ 编译与安装

1. **直接安装**：
   * 在本仓库的 **Releases** 页面中，直接下载打包好的最新版本正式包 [app-release.apk](app/build/outputs/apk/release/app-release.apk) 在您的 Android 手机上安装。
2. **自行编译**：
   * 用 **Android Studio** 打开项目
   * 直接运行 `app` 配置，或者在终端运行以下命令：
     ```bash
     # 编译 Debug 包
     ./gradlew.bat assembleDebug
     
     # 编译 Release 发行包（默认读取 scripts/official-build.env 中的公开统计端点）
     ./gradlew.bat assembleRelease
     ```
   * 官方发布构建（包含匿名统计公开端点）：
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\scripts\assemble-official-release.ps1
     ```

---

## 📊 匿名统计

Debug 构建默认不会发送统计事件，因为统计端点为空。Release 构建默认读取
`scripts/official-build.env` 中的公开端点：`https://lyrics.artria.dpdns.org/v1/events`。
因此本地 `assembleRelease` / `installRelease` 与 GitHub Actions tag 触发的 Release APK 使用同一个统计端点。

统计只包含匿名安装 ID、App 版本、Android SDK、系统语言、功能开关与歌词源成功率等摘要信息，不收集歌曲名、歌手名、Spotify 账号或设备唯一标识。详情见 [docs/telemetry.md](docs/telemetry.md)。

---

## ⚖️ 开源协议

本项目采用 **GNU General Public License v3.0 (GPL-3.0)** 协议开源。

### 协议选择依据：
由于本项目在设计理念与功能逻辑上深度参考了 PC 端著名的 **Spicetify lyrics-plus** 自定义应用（其所属的 Spicetify 整个生态采用 **GPL-3.0** 协议），根据开源协议的传染性与合规性要求，本项目同样采用 **GPL-3.0** 开源，以促进开源社区的健康发展与二次创作。
