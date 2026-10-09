# Via Long-Press Menu Fix · Via 长按菜单修正

**An LSPosed module that makes Via browser's long-press menu always expand downwards from your finger.**

**一个 LSPosed 模块：让 Via 浏览器的长按菜单恒定从手指向下展开，单手操作也点得到。**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
![Xposed API 82](https://img.shields.io/badge/Xposed%20API-82-lightgrey.svg)
![Via 7.3.4](https://img.shields.io/badge/Via-7.3.4%20(20260920)-green.svg)
![Platform](https://img.shields.io/badge/Android-8.1%2B-brightgreen.svg)
![Powered by DeepSeek](https://img.shields.io/badge/Powered%20by-DeepSeek-4D6BFE.svg)

---

## 问题 / The problem

Via 的长按菜单会在**点击点**弹出，并按点击点所在半屏决定展开方向：

| 长按位置 | 原行为 | 结果 |
|---|---|---|
| 上半屏 | `Gravity.TOP`，菜单从手指**向下**展开 | 正常 |
| 下半屏 | `Gravity.BOTTOM`，菜单从手指**向上**展开 | 整张菜单跑到手指上方，单手（拇指）够不到 |

按长按点所在半屏决定展开方向本身是合理的自适应策略，但对单手操作很不友好：菜单跑到手指上方后，越靠近屏幕顶部的条目越难点。

## 效果 / What it does

- **恒定向下**：菜单顶边始终落在手指下方（留 8dp 间距，免得指尖盖住第一项）。
- **永远完整可见**：下方空间不够时，整张菜单自动上移，贴着可视区下沿。
- **不误伤**：只改「手指定位」的菜单；工具栏 / 标签栏按钮那种「锚在控件上」的长按菜单保持原样。
- 可选「以手指为垂直中心弹出」（改一行常量重新构建，见 [配置](#配置--configuration)）。

## 原理 / How it works

反编译 Via 7.3.4 后，菜单定位只集中在一个方法 `w5.k.g0(int x, int y)`（`x, y` 是长按点的屏幕坐标）。
它的方向判定逻辑等价于：

```text
z = (y > 可视高度 / 2)                 // 长按点是否在下半屏
if (z)  { y = 可视高度 - y;  gravity = Gravity.BOTTOM }   // 向上展开
else    {                    gravity = Gravity.TOP    }   // 向下展开
window.gravity = gravity | Gravity.LEFT
window.x = clamp(x); window.y = y
```

模块 hook 这个方法，在它执行完、窗口**首次绘制之前**把窗口参数改写为：

```text
gravity = Gravity.TOP | Gravity.LEFT
targetTop = clamp(手指Y + 8dp, 可视区顶部 + 4dp, 可视区底部 - 菜单高 - 4dp)
window.y  = targetTop - 框架原点
```

即「恒定向下展开，放不下就贴着下沿」。菜单高度取 `DecorView.getMeasuredHeight()`
（Via 在设置窗口参数之前已经 `measure()` 过，此刻拿得到）。

### 两个反过来的坑 / Two gotchas

1. **坐标基准不是内容视图顶部。** `WindowManager.LayoutParams.y` 的原点是**窗口框架**顶部。
   在 Via 7.3.4 上，Via 自己记录的内容区顶部是 `0`，而对话框真实框架是 `[141, 2800]`
   （141px = 状态栏高度，1440×3168 / 密度 3.5）。直接用 `0` 会让整张菜单低一个状态栏。
2. **别在动画期间测位置。** 菜单入场动画 240ms，期间以及系统做窗口边缘夹取时，
   `getLocationOnScreen()` 读到的都不是最终位置，用它做「自校正」只会把位置带偏。

> 现状说明：本模块用 `getWindowVisibleDisplayFrame()` 取框架，在本机它返回 `[0, 2659]`（高度对、top 为 0），
> 因此落点仍比目标低 141px。经实测这个偏移**无害**：底部夹取 `2659 - 菜单高` 加回 141 正好等于真框架底部 2800，
> 菜单依旧完整贴底；手指一侧则变成「菜单顶边在手指下方约 150px」，与 Via 原本上半屏向下展开时的手感一致。
> 想让它紧贴手指，把 `frameTop` 换成状态栏 inset 即可。

## 兼容性 / Compatibility

| 项目 | 值 |
|---|---|
| Via 版本 | **7.3.4 (versionCode 20260920)** —— 其它版本混淆名会变，需重新适配 |
| 包名 | `mark.via` |
| Xposed API | 82（老版 API，LSPosed / EdXposed 通用） |
| 系统 | 实测 OnePlus 13 / Android 16 / KernelSU + Zygisk Next + LSPosed |
| 模块权限 | **无**（不申请任何权限，不联网，不收集数据） |

## 安装 / Install

1. 安装 APK：
   ```bash
   adb install -r ViaMenuFix-1.0.0.apk
   ```
2. 打开 LSPosed 管理器 → **模块** → 启用「Via 长按菜单：固定向下展开」
3. 进它的**作用域**，确认勾选 **Via**（APK 里已预设为 `mark.via`）
4. 完全退出 Via（或 `adb shell am force-stop mark.via`），重新打开

调试日志（LSPosed 把 `XposedBridge.log` 转发到 logcat，标签为 `LSPosedFramework`）：

```bash
adb logcat -d | grep ViaMenuFix
```

正常工作时每次长按打印两行：

```
[ViaMenuFix] fingerY=2294 menuH=791 frame=[0,2659](visibleFrame) gravity 83->51 y=1854
[ViaMenuFix] 实际 top=1995 期望=1854 偏差=141
```

`gravity 83->51` 表示「原本会向上展开（83 = BOTTOM|LEFT），已改成向下（51 = TOP|LEFT）」。

## 构建 / Build

本仓库**不依赖 Gradle**，直接用 Android SDK 自带的 `aapt2 / d8 / zipalign / apksigner` 手工出包，
依赖只有 JDK 17 + `build-tools;34.0.0` + `platforms;android-34`。

```bash
# Linux / macOS / CI
export ANDROID_HOME=/path/to/android-sdk
export VIAMENUFIX_KEYSTORE=/path/to/viamenufix.jks   # 可选，不设则在 ./keystore/ 生成自签密钥
bash build.sh
```

```powershell
# Windows（脚本会自动把工程镜像到纯 ASCII 临时目录——aapt2 在 Windows 上不认中文路径）
$env:ANDROID_HOME = 'E:\Android\sdk'
powershell -ExecutionPolicy Bypass -File build.ps1
```

产物在 `out/ViaMenuFix-<版本>.apk`。

### 自动发版 / CI

打 tag 即自动构建并发布 Release（`.github/workflows/release.yml`）：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

需要在仓库 Secrets 里配置 `KEYSTORE_BASE64`（签名密钥的 base64）：

```bash
base64 -w0 keystore/viamenufix.jks   # 把输出粘进 GitHub Secrets
```

> ⚠️ **签名密钥必须固定并备份**：换了密钥就无法覆盖安装已有版本（还得先卸载，丢数据）。
> 密钥文件已在 `.gitignore` 里——**绝对不要提交进仓库**。

## 配置 / Configuration

`src/io/github/ojuscal/viamenufix/ViaMenuModule.java` 顶部：

```java
/** false = 始终向下展开（默认，单手推荐）；true = 菜单垂直居中于手指 */
private static final boolean CENTER_ON_FINGER = false;
```

间距与留白在 `computeTargetTop()`：`gap = 8dp`（菜单顶边与手指的距离）、`margin = 4dp`（与可视区边缘的留白）。
改完重新构建即可。

## 排错 / Troubleshooting

| 现象 | 原因与处理 |
|---|---|
| 完全没日志 | 模块没启用 / 作用域没勾 Via / 没重启 Via 进程 |
| 日志出现「挂钩失败（Via 版本可能已变化）」 | Via 升级了，混淆名变了 → 见下方「适配新版」 |
| 菜单还是往上弹 | 确认日志里 `gravity 83->51`；若是 `51->51` 说明你长按在上半屏（本来就向下） |
| 工具栏按钮长按菜单也变了 | 不该发生，请带日志提 issue |

### 适配新版 Via / Re-locating the hook

1. 用 [jadx](https://github.com/skylot/jadx) 反编译新版 Via.apk；
2. 全局搜索 `Gravity.BOTTOM`（或反编译后数值 `80`）与 `setAttributes`，找那个做上/下判定的方法
   （7.3.4 里是 `w5.k.g0(II)V`，判定表达式形如 `y > 高度 / 2`）；
3. **注意 jadx 的 `/* JADX INFO: renamed from: x */` 注释**：那里的 `x` 才是真实 dex 名，
   界面上显示的 `f13248a` 之类只是 jadx 为避免与包名冲突起的别名；
4. 用 `dexdump`（build-tools 自带）复核字段/方法签名：
   ```bash
   dexdump classes.dex | less   # 搜 Lw5/k; 的 Instance fields / methods
   ```
5. 改 `MENU_BUILDER_CLASS / SHOW_AT_METHOD / F_DIALOG / F_CONTENT_TOP / F_CONTENT_H` 即可。

## 已知限制 / Known limitations

- 与 Via 的具体版本强绑定（R8 混淆名逐版本变化），升级后需要重新适配。
- 只处理**手指定位**的菜单；控件锚定的菜单（工具栏按钮等）故意不改。
- 未适配页面里的其它弹层（如地址栏长按的复制/粘贴小菜单，走的是另一个类）。
- 落点存在 141px（一个状态栏）的系统性偏移，原因见上文「两个反过来的坑」，实测无碍。

## FAQ

**Q：没有 root 能用吗？**
A：不能直接用 LSPosed。可以考虑 [LSPatch](https://github.com/JingMatrix/LSPatch) 之类免 root 方案把模块嵌进 Via 的 APK，
但那样要给 Via 换签名，必须先卸载官方版（书签/历史会丢）。

**Q：模块会联网 / 收集数据吗？**
A：不会。模块没有申请任何权限，没有网络代码，只在本机进程内改窗口参数。

**Q：为什么只支持 7.3.4？**
A：hook 目标是被 R8 混淆过的符号，每个版本都会重新分配名字。适配工作见上文。

## Credits

- **Powered by [DeepSeek](https://www.deepseek.com/)** —— 本模块的反编译定位、hook 逻辑与工程化由 DeepSeek 辅助完成
  （DeepSeek 与本项目无隶属或背书关系，此处仅为出处说明）
- [LSPosed](https://github.com/LSPosed/LSPosed) —— 模块运行的框架
- [XposedBridge / Xposed API](https://github.com/rovo89/XposedBridge) by rovo89 —— 编译期 API 桩（`lib/api-82.jar`）
- 本仓库**不包含** Via 的任何代码或资源，也不包含其反编译产物；使用者需自行拥有 Via 的合法副本

## License

[GPL-3.0](LICENSE) —— 与 LSPosed 主体一致，衍生作品需同样开源。

## Disclaimer

本项目仅供学习与个人使用，按「现状」提供、不含任何担保。修改第三方应用行为可能违反其用户协议，
请自行评估风险（例如应用更新后可能失效、极端情况下可能崩溃）。与 Via 浏览器及其开发者无任何关联。
"Via" 是其各自权利人的商标。

---

<div align="center">

**Powered by <a href="https://www.deepseek.com/">DeepSeek</a>**

</div>
