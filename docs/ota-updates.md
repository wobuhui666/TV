# 应用更新与发布

普通版本不在启动时弹出更新对话框。用户在设置中点检查更新，查看版本说明后再确认下载。云控只用于值得主动提醒的重要版本：提示仍可取消，不会自动下载或强制安装。

## 发布来源与校验

客户端只读取本项目 `wobuhui666/TV` 的发布，TV 清单入口为：

```text
https://github.com/wobuhui666/TV/releases/latest/download/leanback.json
```

下载按当前应用的 mode、ABI 选择安装包。清单包含 APK 的大小和 SHA-256，每个 APK URL 固定到 `build-<run_id>-<run_attempt>`，避免检查完成后又下载到另一个版本。重新运行 workflow 使用新的 attempt tag，不覆盖已发布安装包。GitHub 镜像失败、超时或下载内容校验失败时，继续尝试其他候选，最后回退到 GitHub 官方地址。界面显示当前线路序号及该线路的下载进度，下载完成后显示正在校验。若 APK 已通过清单的 SHA-256 校验，但包名、版本、签名或 ABI 不兼容，则立即结束并提示具体原因；更换镜像仍会取得相同文件，不再重复下载。文件保存失败同样结束本次下载。验证通过后才交给 Android 安装器；取消下载不得触发安装。

清单示例（哈希及大小仅为字段说明）：

```json
{
  "schema": 1,
  "code": 214537600,
  "name": "5.5.5+20261007.120000",
  "desc": "完善遥控操作和更新下载",
  "packageName": "com.fongmi.android.tv",
  "mode": "leanback",
  "assets": {
    "arm64_v8a": {
      "url": "https://github.com/wobuhui666/TV/releases/download/build-37500000000-1/leanback-arm64_v8a.apk",
      "size": 123456,
      "sha256": "<实际文件的 64 位十六进制 SHA-256>"
    },
    "armeabi_v7a": {
      "url": "https://github.com/wobuhui666/TV/releases/download/build-37500000000-1/leanback-armeabi_v7a.apk",
      "size": 123456,
      "sha256": "<实际文件的 64 位十六进制 SHA-256>"
    }
  }
}
```

两个 release workflow 使用同一个按 Git ref 分组的 concurrency，避免自动和手动发布同时覆盖 latest。只有 `ui/apple-tv-redesign` 分支发布为正式 latest；其他分支发布 prerelease，不进入正式 OTA。修改云控文件不会触发自动构建。手动运行 workflow 可填写 `release_notes` 作为应用内更新说明；留空或由 push 触发时，使用当前提交标题。说明经环境变量及带引号的命令参数传入，保留换行和字面内容。

每次发布 job 开始时，`tools/ota-release.py version` 一次性生成两种 ABI 共用的版本：

- `TV_VERSION_CODE`：当前 UTC 秒数减去 2020-01-01 UTC 秒数，再加 `1000000`，限制在 Android 支持的整数范围内。
- `TV_VERSION_NAME`：`5.8.5+YYYYMMDD.HHMMSS`，时间为 UTC。
- 本地未设置上述环境变量时使用与 FongMi 5.8.5 同步的 `585` / `5.8.5`，方便已有开发构建；正式 OTA 生成器会拒绝此默认版本码。

构建后生成器从实际 `output-metadata.json` 读取版本与包名，要求 ARM64、ARMv7 两个 APK 都存在且版本一致，验证 ZIP、二进制 Manifest、DEX 和 native ABI，再计算文件大小和 SHA-256。清单与 APK 先上传到同一个 draft release，全部上传成功后才发布并按分支设置 latest；清单生成或上传失败则停止发布。

目前 release workflow 只构建 leanback；未来若发布 mobile，应单独生成并上传 `mobile.json`，不能复用 TV 的清单。

## 重要版本提醒

配置文件是默认分支上的 [`ota/policy.json`](../ota/policy.json)，客户端入口为：

```text
https://raw.githubusercontent.com/wobuhui666/TV/ui/apple-tv-redesign/ota/policy.json
```

默认配置关闭提示。启用前先发布目标版本并确认 latest 清单可获取，再将 `popup.enabled` 设为 `true`、`popup.code` 设为该清单的实际版本码，同时指定允许提醒的已安装版本范围及有效期：

```json
{
  "schema": 1,
  "popup": {
    "enabled": true,
    "code": 214537600,
    "minCode": 555,
    "maxCode": 214537599,
    "expiresAt": 1791547200
  }
}
```

`expiresAt` 是必填的 UTC Unix 秒时间戳，应设置为未来的短期截止时间。上例仅演示结构，请按实际发布时间调整。只有目标版本与最新清单匹配、设备版本在范围内且策略未过期时才允许提示，`minCode` 或 `maxCode` 为 `0` 时表示该侧不限制。每个目标版本只提示一次，取消不会影响未来版本的提醒。后台自动检查最多每 24 小时一次，手动检查每次重新请求。关闭 `enabled`、策略缺失、格式错误、联网失败或过期都按不提示处理。手动检查不依赖云控提醒开关。

## 本地检查发布工具

```bash
python3 -m unittest discover -s tools/tests -p 'test_ota_release.py'
python3 tools/ota-release.py version --started-at 2026-10-07T12:00:00Z
python3 tools/ota-release.py manifest \
  --apk-root app/build/outputs/apk \
  --repository wobuhui666/TV \
  --tag build-37500000000-1 \
  --mode leanback \
  --output app/build/outputs/ota/leanback.json
```
