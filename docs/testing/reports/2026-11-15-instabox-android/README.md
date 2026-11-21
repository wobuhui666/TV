# Instabox Android 安装与界面验证

2026-11-15 在现有 Instabox Android 实例上成功安装 ARM64 TV APK，并通过方向键完成首页、我的、设置及返回操作。此实例可用于基础安装、界面与交互验证；本轮未执行片源播放回归。

## 待测产物

| 项目 | 记录 |
| --- | --- |
| 检查时仓库 HEAD | `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，工作树有未提交修改 |
| APK 来源 | 现有 `app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk`；本轮未重新构建 |
| 包名／版本 | `com.fongmi.android.tv`，`5.8.7`／`587` |
| 文件大小 | `117039779` bytes |
| SHA-256 | `d1bf9628289c87ae315bac9c42924f988162c448a6f664197ce5bdb79636260d` |
| APK ABI／SDK | `arm64-v8a`；minSdk 24，targetSdk 37 |

仓库 HEAD 仅记录检查时环境，未核实此 APK 对应的完整源码状态。因此本轮结果不代表当前工作树全部最新改动通过。

## 实例环境

| 项目 | 实测记录 |
| --- | --- |
| Instabox Android 模板 | `2026-11-02.7-android.1` |
| 系统／设备型号 | Android 14，`redroid_x86_64` |
| 资源 | 4 vCPU，8192 MiB 内存 |
| ADB 设备 | `emulator-5554` |
| 支持 ABI | `x86_64,arm64-v8a,x86,armeabi-v7a,armeabi` |
| Native bridge | `libnb.so` 指向 `libndk_translation.so`；ISA 映射 `arm64=x86_64`、`arm=x86` |
| 图形渲染 | SurfaceFlinger GLES 为 `ANGLE (Google, Vulkan 1.3.0 SwiftShader Device LLVM 10.0.0)`，使用软件渲染 |

[Instabox Android 文档](https://instabox.sh/docs/android.md)称不支持 ARM translation，但此实例的属性、桥接库和 ARM64 APK 安装启动结果表明它存在 ARM 转译能力。该观察仅适用于本次实例，其他实例仍需检查 ABI 并实际安装确认。

## 已执行结果

| 检查 | 结果 |
| --- | --- |
| `adb install` | 返回 `Success` |
| `am start -W -n com.fongmi.android.tv/com.fongmi.android.tv.ui.activity.HomeActivity` | 冷启动返回 `Status: ok`，`TotalTime: 3048` ms |
| 首次界面 | 拒绝通知权限后，首页文字与海报背景正常显示 |
| 方向键导航 | 首页 `RIGHT` 三次、`CENTER` 进入 My library，焦点位于 Keep；`DOWN` 两次、`RIGHT`、`CENTER` 进入 Setting；`BACK` 两次回到 My library，随后再按 `BACK` 回到首页 |
| 进程与崩溃日志 | PID 始终为 `4139`；按此 PID 读取 crash buffer 及 `AndroidRuntime:E`／`libc:F` 输出均为空，仅说明本次观察窗口未发现对应错误 |
| 取证接口 | ADB 截图及 UI hierarchy 获取正常 |

`3048` ms 是单次启动记录，不构成性能测量或设备间性能比较。

截图：[首页](home.png) · [设置](settings.png)。两图均已目视确认。

## 覆盖限制

- 导航仅覆盖上述路径，未验证全部页面、触摸滚动或自动化 instrumentation 用例。
- 未导入片源或执行片源播放回归，不能据此判断 Exo、MPV、Python 来源或全部原生依赖可用。
- 当前运行环境使用 x86_64 上的 ARM 转译和软件渲染，不代表真实 ARM 电视硬解、HDR、音频直通或高码率播放性能。
- 使用已有 APK，未验证当前工作树全部最新改动。
