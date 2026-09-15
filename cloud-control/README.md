# Axon Input Cloud Control

根目录旧 `version.json` / `notice.json` 只服务旧版客户端，禁止覆盖或删除。

## 2.2Test 启动必需文件

- `security.json`
- `versions/2.2Test.json`
- `notices/2.2Test.json`
- `disable/2.2Test.json`
- `runtime/2.2Test.json`
- `ui/2.2Test.json`
- `local/2.2Test.json`

任一文件缺失、网络无法取得、JSON 无效或 versionCode/versionName 不匹配，2.2Test 会 fail-closed 退出。

## disable/<version>.json

可使用稳定 Feature ID 控制所有顶层功能：

- `hiddenFeatureIds`: 从主 UI 和搜索中隐藏。
- `disabledFeatureIds`: 禁止使用并强制关闭。
- `forcedOffFeatureIds`: 强制关闭且禁止用户重新开启。
- `forcedOnFeatureIds`: 强制开启且禁止用户关闭；依赖资源缺失时不会伪造资源。
- `readOnlyFeatureIds`: 保持当前状态，但 UI 与快捷键不能切换。
- `shortcutBlockedFeatureIds`: 单独禁用指定功能快捷键。

2.2Test 默认对 `auto_hide` 同时执行 hidden + disabled + forcedOff。

## runtime/<version>.json

控制入口密码、顶层功能快捷键、云工坊、配置导入、配置导出、外链，以及是否每次 Activity resume 重新执行云策略。

## ui/<version>.json

`hiddenUiIds` 当前支持隐藏 `configuration_section`、`local_config`、`cloud_config_center`、`html_guide`、`kook_join`。
`textOverrides` 为后续云端文案扩展保留稳定字段。

## local/<version>.json

`preferenceOverrides` 可持续覆盖 Portable Config 白名单内的本地设置；`removePreferenceKeys` 可删除白名单内字段。
不会接受设备授权、session、Secret 等非 portable key，也不允许任意代码执行。

## security.json

全局开关、禁止版本和入口密码摘要。入口密码支持 `sha256` 与 `pbkdf2-sha256`。

## versions / notices

每个版本独立维护更新策略与独立公告。
