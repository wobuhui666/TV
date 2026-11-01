# AISkip 历史实现归档

归档日期：2026-11-01。来源为移除前的项目版本 `529d3fffc94dd83cbf5d9241db1b45d4a7e7e39f`。

旧方案在 App 端创建静音音频探针，将首尾 PCM 样本编码为 WAV 后上传至 Cloudflare Worker，再查询分析任务和反馈结果。返回值是从零计算的片头长度、从结束位置倒推的片尾长度。

本次按用户要求移出应用运行代码和设置入口，片头片尾改由公共时间段数据服务提供。本目录保存历史源码、测试和配置参考，不属于 Android source set。

## 内容

- `app/src/main/java/com/fongmi/android/tv/ai/skip/`：6 个旧运行类，包括音频探针、WAV 编码、API、设置及结果处理。
- `app/src/test/java/com/fongmi/android/tv/ai/skip/`：3 个旧 JUnit 测试文件。
- `server/ai-skip-worker/`：8 个受跟踪的 Worker 源码、测试和构建配置文件。
- `ui/`：从设置页和共享凭据存储移除的 AISkip 专属代码片段。
- `resources/`：原英文、简体中文、繁体中文的 AISkip 字符串。
- `notes/AI-SKIP-SERVICE.md`：原连接记录；历史明文令牌已替换为 `[REDACTED]`。

归档只包含受跟踪源码和说明，没有复制本地配置、实际密钥、音频样本、APK、依赖安装目录或其他生成文件。AI 字幕的代码、入口和凭据存储继续独立保留。

旧设置键 `ai_skip_enabled`、`ai_skip_url`、`ai_skip_token_cipher` 只在历史实现中有意义。应用中的旧运行调用由新的片头片尾模块替换；本目录不参与运行时偏好迁移或构建。
