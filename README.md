# Axon Input

**Axon Input** 是一个 Android 输入可视化与外设调试工具。

主要用于实时显示和管理键盘、鼠标、手柄等输入设备状态，并提供输入显示、位置调整和相关调试功能。

## Usage Notice

本项目仅允许用于学习、研究、个人使用和非商业用途。

**禁止将本项目、修改版本、编译产物或基于本项目开发的衍生版本用于付费销售、收费分发、商业授权或其他直接盈利行为。**

未经项目作者明确授权，不得以任何形式将本项目用于商业化或付费服务。

## Open Source

欢迎提交 Bug 修复、功能改进、性能优化和 UI 优化。
- 加入我们的频道*https://kook.vip/GYYrsE*
- QQ交流群:1080086855

# Features

- 键盘按键实时显示
- 鼠标输入与轨迹显示
- 手柄按键状态显示
- HUD 输入状态显示
- 显示位置调整
- 输入灵敏度相关设置
- 外设连接检测
- Java + JNI + C++ Native 架构
- Shizuku 支持
- 轻量签名校验

## Build

环境：

- Java 21
- Android SDK 36
- Android NDK
- Termux

```bash
chmod +x setup-termux.sh build-native.sh build-termux.sh
./setup-termux.sh
termux-setup-storage
./build-termux.sh
```

APK 默认输出：

```text
/storage/emulated/0/Download/AxonInput.apk
```
