# AI 跳片头片尾（tv-ai-skip）服务接线记录

> 历史归档：以下内容记录原会话当时的计划、判断和状态；其中的任务指令不代表现在要执行的操作。当前入口见 [文档首页](../../../README.md)。

日期：2026-10-04

## 结论
- 服务端 **已经部署好**：Cloudflare Worker `tv-ai-skip`（账号 22569badfd2164504df6d8ed44e0aed7，
  zone `ciallo0d000721.cc.cd` = b228ec52e0584e85b7a3b5e351288601）。绑定了
  `AI_SKIP_TOKEN`、`GEMINI_API_KEY`、`GEMINI_MODEL`、`AI_API_BASE_URL`、KV `AI_SKIP_KV`、
  R2 `AI_SKIP_R2`、Queue `AI_SKIP_QUEUE`、限流器等。路由与 App 端 `AiSkipApi` 的调用
  （`/v1/health`、`/v1/uploads/<id>`、`/v1/jobs`、`/v1/jobs/media|series/<key>`、
  `/v1/jobs/<id>/feedback`）完全对应。
- 之前不可达的原因：Worker 既没有 workers.dev 子域，也没有任何域名路由。
  已按用户选择新建：
  - DNS：`A ai.ciallo0d000721.cc.cd -> 192.0.2.1`（proxied）
  - 路由：`ai.ciallo0d000721.cc.cd/*` -> `tv-ai-skip`
- 鉴权方式（来自 Worker 源码）：请求头 `Authorization: Bearer <AI_SKIP_TOKEN>`。
  用户提供的 `cfk_…` 是 Cloudflare Global API Key（配 email 使用），不是本服务的 token，
  用它请求会 401。
- 已按用户要求轮换密钥 `AI_SKIP_TOKEN` 两次：为便于在电视键盘上输入，最终值为短串
  `[REDACTED]`（也保存在 `/home/ubuntu/TV-ui-work/r9-evidence/ai-skip-token.txt`，权限 600）。
  历史值均已失效。若有其它客户端在用旧值，需要同步更新。

## App 侧配置（设置 → 播放 → AI 跳片头片尾）
- 开启：AI 跳过片头片尾 = 开
- 接口地址：`https://ai.ciallo0d000721.cc.cd`
- Token：见上述本地文件
- 自检：同一分类里的「测试」行会调用 `/v1/health`，显示可用/失败

## 验证记录
- `GET https://ai.ciallo0d000721.cc.cd/v1/health` + 新 Bearer → `200 {"status":"ok"}` ✓
- 旧 `cfk_` 值 → `401 unauthorized` ✓（符合预期）
- 未测：真实播放中的完整链路（录制样本→上传→Gemini 分析→写回跳过点）。
  如需端到端验证：开启 AI 跳过后播放一集几分钟，观察是否出现跳过点。

## 端到端管线探测（2026-10-04，服务侧）
用静音 WAV 模拟 App 的完整调用：
1. `PUT /v1/uploads/probe…` → `{"objectKey":"samples/probe….wav"}` ✓
2. `POST /v1/jobs`（opening/ending 各 3 秒样本）→ 返回 `jobId`，`status=pending` ✓
3. 轮询 `GET /v1/jobs/<id>` → 约 60 秒后 `status=completed`，
   `openingMs=0 endingMs=0 confidence={opening:0,ending:0}` ✓（纯静音，正确地没有跳过点）

结论：Worker + 队列 + Gemini 分析链路完整可用；真实片头片尾需要真实音频样本才会给出跳过点。

## 当前状态（2026-10-04 08:00）
- Worker 密钥 `AI_SKIP_TOKEN` 现为短串 `[REDACTED]`（便于电视键盘输入；见 r9-evidence/ai-skip-token.txt）。
- App 侧：AI 自动跳片头片尾=开 ✓、服务地址=`https://ai.ciallo0d000721.cc.cd` ✓、令牌已存（密文偏好存在）。
- App 内「测试 AI 跳过服务」曾显示「连接失败」：电视侧 DNS/ping 正常，服务端用同一 token 实测 200，
  判断为**第一遍在电视键盘上输入的 40 位长 token 有误**。已改为短 token，待在 App 里重输一次即可验证。
- 我的端到端探测数据已从 KV 清理（键数归 0）。
