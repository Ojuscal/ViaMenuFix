# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-10-09

### 新增
- 首个版本：Via 浏览器长按菜单改为**恒定从手指向下展开**。
- 下方空间不足时整张菜单自动上移，贴着可视区下沿，保证每一项都能点到。
- `CENTER_ON_FINGER` 开关：可切换为「菜单垂直居中于手指」。
- 仅作用于手指定位的菜单；工具栏/标签栏按钮的控件锚定菜单保持原行为。

### 修复
- 修正 `h0` / `i0` 标记 hook 未生效的问题（`findAndHookMethod` 的 callback 参数位置错误）。
- 去掉「布局后自校正」：入场动画期间与系统窗口夹取时 `getLocationOnScreen()` 不可信，会带偏位置。

### 说明
- 在 OnePlus 13 (Android 16) + KernelSU/Zygisk Next/LSPosed 上实测通过，Via 7.3.4 (versionCode 20260920)。
- 本项目由 **DeepSeek** 辅助完成（反编译定位、hook 逻辑、工程化）；DeepSeek 与本项目无隶属或背书关系。
