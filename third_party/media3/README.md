# Media3 本地补丁

上游仓库：`https://github.com/wobuhui666/media`。

固定基准：`342f6590e02ac390e0bd6b4488b8a1bff55747c1`。CI 的 release 与 validation 构建都检出该提交，不跟随浮动分支。

| 补丁 | SHA-256 |
| --- | --- |
| `paused-danmaku-toggle.patch` | `4251fc8a5b8a080f051674f5f3a9a8c5c1c9f8c9b55c382493341386a9d768a3` |
| `danmaku-render-index.patch` | `22343b0223dc63ebffe4e2f9ba76b408356269f172784597e799a6e38b212eda` |
| `danmaku-display-list.patch` | `1d7b37e7f1e87b9a09ed1704434fc718d8cd5621775c43e5be6046599844e01a` |

`paused-danmaku-toggle.patch` 修复暂停播放时关闭弹幕再开启后，画面中有效弹幕没有重新建立的问题，改动 `libraries/ui_danmaku/src/main/java/androidx/media3/ui/danmaku/` 下的 `DanmakuController.java` 和 `DanmakuView.java`。

`danmaku-render-index.patch` 改动同目录的 `DanmakuRenderPool.java`。分块在测量线程排序成数组，绘制时先检查首尾时间，再二分查询当前范围；历史分块不再逐帧创建 `subMap`、迭代器和装箱键，命中单个分块时不再重复排序。相同时间的输入顺序、查询起点排除/终点包含、回退、重测量与过期任务拦截保持一致。历史仍保留；查询仍遍历分块的首尾索引，不声称内存有界或整个查询与分块数无关。两份补丁保留原上游许可。

`danmaku-display-list.patch` 在 Android 10 及以上、未启用滚动弹幕重叠的硬件 Canvas 中复用可见文字的 RenderNode 绘制指令；保留原填充、描边、投影、阴影和渐变命令，不生成位图或离屏图层。最多保留 200 项、原生指令内存估算上限 4 MiB，每帧最多记录两项；重叠模式、超限项、软件 Canvas 和旧 Android 使用原绘制路径。连续两帧未使用的项、外观或重叠开关变化会释放原生指令。配套 View 清理位于 `paused-danmaku-toggle.patch`，覆盖停止、释放、清空、换池、播放中跳转、尺寸变化、隐藏和最后一条弹幕过期。所有补丁保留原上游许可；收益以 Android 播放对照结果为准。

TV 根 `settings.gradle` 在 `includeBuild(MEDIA3_SOURCE_DIR)` 前调用 `apply_patches.py`。CI、现有本地构建模板和手工构建使用同一入口，无需在各工作流复制应用命令。也可对选定 checkout 显式预检/应用：

```bash
python3 third_party/media3/apply_patches.py "$MEDIA3_SOURCE_DIR"
```

脚本只使用 Python 3 标准库与 Git：

- 校验补丁 SHA-256、checkout 根目录及固定 HEAD。
- 分别校验每份补丁的目标文件是完整原始 blob，或均是完整修改后 blob；额外本地改动、文件类型/模式异常及部分应用会失败。
- 原始状态必须通过 `git apply --check` 后才能应用；已应用状态必须通过反向 `git apply --reverse --check`。
- 应用后复核完整 blob ID。不会执行 reset、checkout、clean、反向应用或修改 index；其他文件的改动保留。

如果脚本拒绝已有依赖目录，请先检查该目录的 HEAD 和本地改动，或另建基准提交的 checkout。不要删除改动来绕过校验。更新补丁时需一并审查完整补丁、脚本中的 SHA、此处 SHA 和固定基准；用 `git diff --full-index` 保留目标文件的完整 old/new blob ID。

宿主机保护逻辑测试（临时 Git 仓库，不启动 Android 构建）：

```bash
python3 -m unittest discover -s third_party/media3 -p 'test_*.py' -v
```

弹幕查询与 Exo 日志的宿主机 JUnit 及分配对照测试：

```bash
python3 tools/testing/playback-stutter/verify.py
```

该测试使用真实的修改前后查询类与诊断记录实现，Android 绘制、线程调度及部分 Media3 数据接口使用小型替身；它不能代替实机音频与弹幕播放验证。环境、结果和覆盖限制见 [验证记录](../../docs/testing/reports/2026-11-14-playback-stutter/README.md)。
