# Axon Input Cloud Control

此目录是新版本 Axon Input 的 GitHub 远程控制面。根目录旧 `version.json` 与 `notice.json` 专门留给旧版客户端，禁止覆盖或删除。

## 必需文件

当前 APK 启动时必须同时成功读取：

- `security.json`
- `versions/<versionName>.json`
- `notices/<versionName>.json`

任意文件不存在、JSON 无效、版本不匹配或 GitHub 无法访问时，新架构会 fail-closed 终止启动。

## security.json

- `enabled`: 全局开关。
- `deniedVersionCodes` / `deniedVersionNames`: 快速禁用指定版本。
- `password`: 入口密码摘要。支持 `sha256` 与 `pbkdf2-sha256`。

修改密码摘要后，本地此前已经通过的入口授权会自动失效，下次启动重新要求密码。

PBKDF2 建议字段：

```json
{
  "algorithm": "pbkdf2-sha256",
  "salt": "十六进制随机盐",
  "iterations": 210000,
  "hash": "64位十六进制摘要"
}
```

## versions/<versionName>.json

每个 APK 版本独立维护，不影响其他版本。

- `enabled`: 单独禁用该版本。
- `maintenanceMode`: 临时维护停用。
- `forceUpdate`: 有新版本时是否强制更新。
- `minSupportedVersionCode`: 低于此版本码时强制更新。
- `latestVersionCode` / `latestVersionName`: 当前最新版本。
- `downloadUrl`: 更新跳转地址。
- `updateTitle` / `updateMessage` / `changelog`: 更新 UI 文案。

## notices/<versionName>.json

每个版本有自己的独立公告。不同版本可以同时显示不同公告，不再共用根目录旧 `notice.json`。

- `enabled`: 是否显示。
- `id`: 公告 ID；变更 ID 可让 `showOnce=true` 的公告再次展示。
- `showOnce`: 是否同一公告只显示一次。
- `title` / `message`: 公告内容。
- `joinUrl` / `joinText` / `confirmText`: 按钮配置。
- `waitSeconds`: 确认按钮等待秒数，范围 0–30。
