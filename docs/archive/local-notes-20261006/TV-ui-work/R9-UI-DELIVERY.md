# R9 UI 交付说明（首页导航居中 + AI 跳片服务接线）

> 历史归档：以下内容记录原会话当时的计划、判断和状态；其中的任务指令不代表现在要执行的操作。当前入口见 [文档首页](../../../README.md)。

日期：2026-10-04（承接 R8-UI-DELIVERY.md）

## 本次交付
1. 首页顶部导航「选中胶囊内文字左右不居中」修复
   - 原因：TV Material Surface 的内容从起始位置排布，而最小宽度加在外框上，
     选中态出现胶囊后短标签（如「首页」）就偏左。
   - 修法：把 `widthIn(min = 62.dp)` 从外框移到文字容器（JetStreamHomeNavView.kt），
     胶囊贴合内容且文字居中；下划线也随之居中。
   - 真机验证：r9-evidence/r9nav-new-home.png（新，居中）对比
     r9-evidence/r9nav-old-home.png（旧，偏左）。
2. AI 跳片头片尾服务接线与验证
   - Worker `tv-ai-skip` 原先没有任何可达地址：已按用户选择新增
     DNS `A ai.ciallo0d000721.cc.cd -> 192.0.2.1`（proxied）与路由
     `ai.ciallo0d000721.cc.cd/* -> tv-ai-skip`。
   - 已轮换 Worker 密钥 `AI_SKIP_TOKEN`（用户确认）；新值见
     r9-evidence/ai-skip-token.txt，旧值即刻失效。
   - 验证：`GET /v1/health` + 新 Bearer → `200 {"status":"ok"}`；
     旧值 → 401。详见 [AI-SKIP-SERVICE.md](../../aiskip-20261101/notes/AI-SKIP-SERVICE.md)。
   - App 侧配置入口：设置 → 播放 → AI 跳过片头片尾（启用 / 接口地址 / Token / 测试）。

## 提交
- `de18401d4` fix(ui): restore neutral settings and playback surfaces（R8 的 11 个文件）
- `fedc4dd75` fix(ui): centre the home navigation label inside the selected pill
- 推送受限：当前 gh 账号（5q68fs6b86-netizen）对该仓库只有 `pull` 权限，
  `git push` 返回 403。需要仓库写权限（或由仓库所有者推送）后才能上远端。

## 构建
- Codespace `tv-r3-build-q7jwxvq6r4j5f9xrj`：
  r9 gate（assemble + 单元测试 + lint）BUILD SUCCESSFUL；预览包同样通过。
  （期间遇到 /workspaces 磁盘满导致一次失败，清理旧产物后重跑成功。）
- 产物：r9-arm64.apk `13b84c2d…`、r9-preview-arm64.apk `f393bc0d…`（本地 r9-evidence/）
- 真机安装：增量补丁（基线 r8）1,679,089 字节，设备端重建 sha 与产物一致。

## 待办
- 端到端 AI 跳片验证（播放一集几分钟，观察是否写入跳过点）未完成
- 远端推送待权限

## 补充：AI 跳片服务端端到端探测（2026-10-04）
用静音 WAV 模拟 App 完整调用链：上传 → 建任务 → 轮询，约 60 秒后 `status=completed`
（静音样本正确地返回 0 个跳过点）。说明 Worker + 队列 + Gemini 分析链路可用，
只差真实播放样本才会产出跳过点。详见 [AI-SKIP-SERVICE.md](../../aiskip-20261101/notes/AI-SKIP-SERVICE.md)。

## 推送完成（2026-10-04 08:53）
- 使用 zhhshss 账号的 token 推送成功：`a9c65efa9..fedc4dd75 -> ui/apple-tv-redesign`
- PR #62（Redesign TV UI and repair focus, clipping and empty states）head 已更新为 `fedc4dd75`，
  包含三个提交：`0390d72d`（上一轮）、`de18401d`（本轮回退+片库修复）、`fedc4dd7`（首页 Tab 居中）
- 本地与 origin/ui/apple-tv-redesign 完全同步（无领先/落后）
- 注：gh CLI 当前登录账号为 zhhshss（为此推送所用）
