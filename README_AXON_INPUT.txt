Axon Input 版本：1.1

Axon Input

本版重点：
0. 所有悬浮显示均增加独立透明度调试（0–100%）；“应用退到后台时隐藏悬浮显示”简化为“隐藏后台”。
1. 普通配置在当前应用任务内保留；切到后台或重新进入不会清除，只有退出/移除后台任务后才清理。配置1仍可手动保存/加载/导出/导入。
2. 已验证密码后，每次进入应用自动尝试开启/连接无障碍服务。
3. 修复手柄 X/Y 语义反转：ABXY 优先使用 Android KeyEvent 语义，轴数据仍走原始输入。
4. HTML API v9：新增完整 settings、viewport、palette、runtime、recent-key prompt、自适应 CSS 变量和辅助 API。
5. 高频 HTML pointer/gamepad 更新改为局部状态补丁，不再每帧构建完整 JSON。
6. 代码职责重新整理并补充中文注释和 CODE_STRUCTURE_CN.txt。

构建：Java 21 + C++20 + Android SDK 36 + Termux，无 Gradle。
构建成功后 APK 自动复制到 Download/AxonInput.apk。

Axon Input 品牌迁移：
- 应用包名：com.axon.input
- APK 输出：Download/AxonInput.apk
- 应用图标：白底 + 黑色节点连接标记
- 启动时执行一次 APK 签名证书 SHA-256 校验，不进入输入与绘制热路径
- 签名密钥默认保存在 ~/.axon-input/axon-input.keystore，请保留该文件以便后续版本直接覆盖安装

版本检测
--------
应用启动并通过访问验证后，会在后台请求仓库根目录的 version.json。
只有远端 versionCode 大于当前 APK 的 versionCode 时才弹出更新提示；检测失败会静默跳过，不影响应用启动。
发布新版本时只需同步更新仓库根目录 version.json，例如：
{
  "versionCode": 3,
  "versionName": "1.2",
  "changelog": "本次更新内容"
}
versionCode 必须递增，versionName 仅用于显示。
检测到新版后点击“立即更新”固定跳转到：https://github.com/keepBacon/Axon-Input
