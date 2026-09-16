# Axon Input 2.7 更新报告

- **versionName:** `2.7`
- **versionCode:** `17`
- **发布日期:** 2026-09-16

## 本次重点

### 1. 配置页新增应用内“使用教程”

原配置页底部的“加入 KOOK 频道”入口已替换为 **“使用教程”**。

点击后打开 Axon Input 内置的独立长文档页面，正文随 APK 一起打包，不依赖 KOOK、GitHub、浏览器或其他外部站点，因此在中国大陆网络环境下也可以离线阅读。

教程覆盖：

- 首次启动与权限
- Shizuku / Root / 无障碍
- 键盘、鼠标、CPS
- 手柄、D-Pad、摇杆、肩键与扳机
- 触屏按显
- 超级自定义按显
- 配置 V4 / 超级自定义 V9 跨设备规则
- 同步点击与 Force Hold
- 字体、灵敏度、全局 HTML
- BongoCat / Mver / Live2D 导入与兼容
- 模型左右翻转
- 手部贴图残影
- 混合 1×/2×/3× 画布资源
- 云端配置中心与预览图
- 中国大陆网络环境下的云端策略容灾
- 启动闪退、CPS、模型模糊、错位等故障排查
- 性能、备份、迁移与 VMP 建议

内置教程正文约 30 KB、近 1000 行，可长距离滚动并支持文本选择。

## 近期稳定性修复汇总

### 配置系统

- 常规配置升级到 V4 语义字段。
- 超级自定义升级到 V9。
- 渐变色序列可以完整录入与恢复。
- 坐标使用 viewport ratio，宽高使用 dp，字号使用 sp。
- 跨设备不再复制源设备 raw scanCode / evdev。
- 目标设备重新生成派生输入数据。
- 导入配置不再误清除目标设备本地渲染/模型状态。

### BongoCat / Live2D

- 引入自动投影兼容系统：`source` / `source-authored` / `legacy-canvas`。
- 修复部分旧模型在新版投影下的裁切、缩放和偏移问题。
- 修复 LT/LB、RT/RB / L1/R1、L2/R2 语义错位。
- 修复混合 keyboard/gamepad 模型方向键事件被提前过滤的问题。
- “全局反转”重定义为 **纯视觉左右翻转模型**，不再交换输入语义。
- 修复部分模型快速切换按键时的手部贴图残影。
- Mver 支持同一逻辑画布的 1×/2×/3× 混合像素密度资源。
- Live2D 模式恢复 face/emoticon effect layer。
- 动态手部、face、effect 图层统一使用 latest-request-only，旧异步回调不能覆盖新状态。

### 常规按显 / 手柄 CPS

- 修复手柄模式下 Space CPS 无法读取。
- 修复手柄模式下 LMB / RMB CPS 无法读取。
- CPS 统计改为独立物理映射按钮上升沿，不依赖临时显示 mask。
- Overlay 重建或旋转后减少漏计与重复计数。

### 云端与中国大陆网络兼容

- 启动策略不再只依赖 `raw.githubusercontent.com`。
- 支持 Supabase Edge、jsDelivr、GitHub Raw 多源容灾。
- 所有源均无法取得有效策略时仍保持 fail-closed。
- 云端预览图传输层与 VMP 保护边界分离。
- 构建脚本会检查 APK versionName/versionCode 与当前 cloud-control 策略的一致性。

## 2.7 云端策略

2.7 使用以下启动必需文件：

```text
cloud-control/versions/2.7.json
cloud-control/notices/2.7.json
cloud-control/disable/2.7.json
cloud-control/runtime/2.7.json
cloud-control/ui/2.7.json
cloud-control/local/2.7.json
```

当前版本契约：

```text
versionName = 2.7
versionCode = 17
latestVersionName = 2.7
latestVersionCode = 17
```

`minSupportedVersionCode` 保持为 `16`，因此云端策略本身不会强制淘汰 2.2/16；是否允许旧版本继续运行仍可通过对应旧版本策略单独控制。

## 升级建议

升级前建议导出：

1. 常规配置。
2. 超级自定义配置。
3. 自定义 BongoCat / Mver / Live2D 原始 ZIP。

升级后重点检查：

- 手柄映射
- Space / LMB / RMB CPS
- BongoCat 模型左右翻转
- Mver 手部 / face 图层
- 渐变色配置
- 超级自定义跨设备位置

## 开发与构建说明

APK 版本与云端策略必须保持一致。当前构建脚本会在打包前进行 cloud policy contract 检查；如果 manifest 已经是 `2.7 / 17`，但 `cloud-control/2.7` 仍是其他版本号，构建会直接失败，而不是等用户安装后启动闪退。
