# 本地构建与验证

本文依据 2026-10-05 的 `ui/apple-tv-redesign` 构建配置整理。命令以 Ubuntu 24.04 / Bash 为例；除工具安装步骤外，均在 TV 仓库根目录执行。首次构建需要联网下载 Gradle、Maven 依赖和 Python 包。

## 必要组件

| 组件 | 当前要求 |
| --- | --- |
| JDK | 完整 JDK 21，包含 `java`、`javac` 和 `keytool` |
| Python | `python3.10` 可从 `PATH` 调用，供 Chaquopy 17 使用 |
| Android SDK | `platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0` |
| Android Gradle Plugin | 仓库固定为 9.0.1 |
| Gradle | 使用 `./gradlew`，仓库固定为 9.1.0，无需另装系统 Gradle |
| Media3 | 公开仓库 `wobuhui666/media` 的固定提交 `342f6590e02ac390e0bd6b4488b8a1bff55747c1`、本仓库配套的 composite settings 与固定补丁 |
| APK 目标 | Android 7.0 / API 24 起；`arm64-v8a` 或 `armeabi-v7a` |

建议准备 4 核、16 GB 内存和至少 64 GB 磁盘；同时运行模拟器会增加资源占用。通常无需安装 NDK：MPV、FFmpeg、libass 等原生库已经随仓库提供。修改 MPV 桥时，再按 [MPV 原生桥说明](third_party/mpv/README.md) 使用固定 NDK r29 重编。

## 使用公开 Codespace 模板

[tv-codespace-template](https://github.com/zhhshss/tv-codespace-template) 提供 JDK、Python、SDK、Media3 和模拟器的安装脚本。通过该仓库创建 Codespace，等初始化完成后，在模板目录执行：

```bash
source scripts/env.sh
scripts/build-tv.sh :app:assembleLeanbackArm64_v8aDebug --max-workers=2
```

模板默认构建 `$TV_UI_DIR` 对应的 `ui/apple-tv-redesign` 工作树；路径可以通过模板 `.env` 调整。已有 Ubuntu 主机的安装入口及参数见模板 README。模板保留已有工作树，因此更新模板后仍需自行确认 TV 和 Media3 的检出版本。

`scripts/build-tv.sh` 已加载 SDK 37.0 兼容 init 脚本。以下手动配置步骤提供同样的做法。

## 手动配置

### 1. 检出 TV，准备 JDK 与 Python

已有仓库可直接进入根目录；新检出示例：

```bash
git clone --branch ui/apple-tv-redesign https://github.com/wobuhui666/TV.git TV
cd TV
export TV_PROJECT_DIR="$PWD"
chmod +x ./gradlew
```

Ubuntu 安装工具链：

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk curl unzip git
TV_JAVAC="$(update-alternatives --list javac | awk '/java-21-/ {print; exit}')"
test -n "$TV_JAVAC"
export JAVA_HOME="${TV_JAVAC%/bin/javac}"
export PATH="$JAVA_HOME/bin:$HOME/.local/bin:$PATH"
java -version
javac -version
```

其他发行版可使用自己的 JDK 21 安装路径设置 `JAVA_HOME`。`java` 与 `javac` 都应显示 21；仅安装 JRE 不够。

如果尚无 Python 3.10，可用 [uv](https://docs.astral.sh/uv/) 安装：

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="$HOME/.local/bin:$PATH"
uv python install 3.10
python3.10 --version
```

### 2. 安装 Android SDK

下面使用用户目录，也可将 `ANDROID_HOME` 换成已有 SDK 的绝对路径。两个 SDK 环境变量应指向同一位置。

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    TV_SDK_DOWNLOAD="$(mktemp -d)"
    curl -fL --retry 3 \
      https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
      -o "$TV_SDK_DOWNLOAD/cmdline-tools.zip"
    unzip -q "$TV_SDK_DOWNLOAD/cmdline-tools.zip" -d "$TV_SDK_DOWNLOAD"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    mv "$TV_SDK_DOWNLOAD/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
sdkmanager --sdk_root="$ANDROID_HOME" --list_installed
```

按提示阅读并接受 SDK 许可。若根目录已有 `local.properties`，其 `sdk.dir` 也应与所选 SDK 一致。

### 3. 配置 SDK 37.0 的 minor API

仓库使用整数 `compileSdk=37`，SDK 包名则是 `platforms;android-37.0`。AGP 9 支持用 `compileSdkMinor=0` 指定该平台。[模板的验证方案](https://github.com/zhhshss/tv-codespace-template/blob/main/scripts/sdk-compat.init.gradle) 使用以下 init 脚本，同时覆盖应用、库和 composite build 中的 Android 模块。

将脚本放在仓库外，后续 Gradle 命令用 `-I` 加载：

```bash
export TV_SDK_INIT="$HOME/.config/tv-build/sdk-compat.init.gradle"
mkdir -p "$(dirname "$TV_SDK_INIT")"
cat > "$TV_SDK_INIT" <<'GRADLE'
gradle.beforeProject { p ->
    ['com.android.application', 'com.android.library'].each { pluginId ->
        p.pluginManager.withPlugin(pluginId) {
            p.extensions.getByName('androidComponents').finalizeDsl { dsl ->
                if (dsl.compileSdk == 37) dsl.compileSdkMinor = 0
            }
        }
    }
}
GRADLE
```

若报 `Failed to find target with hash string 'android-37'`，先检查 SDK 安装和 `-I` 参数。无需给 SDK 创建 `android-37` 软链接、移动平台目录或修改 `package.xml`。

### 4. 准备配套 Media3 源码

当前播放器依赖该 fork 的接口与修复，构建时必须启用源码替换。仅下载 Maven 中的同版本 Media3 不能替代此步骤。

```bash
cd "$TV_PROJECT_DIR"
# 以下初始化命令仅用于全新目录，不修改已有 checkout。
test ! -e .media3
git init .media3
git -C .media3 remote add origin https://github.com/wobuhui666/media.git
git -C .media3 fetch --depth 1 origin 342f6590e02ac390e0bd6b4488b8a1bff55747c1
git -C .media3 checkout --detach FETCH_HEAD
cp .github/media3-composite-settings.gradle.kts .media3/settings.gradle.kts
export MEDIA3_SOURCE_DIR="$TV_PROJECT_DIR/.media3"

test -d "$MEDIA3_SOURCE_DIR/build-logic"
test -d .github/media3-stubs
cmp .github/media3-composite-settings.gradle.kts "$MEDIA3_SOURCE_DIR/settings.gradle.kts"
git rev-parse HEAD
git -C "$MEDIA3_SOURCE_DIR" rev-parse HEAD
./gradlew -I "$TV_SDK_INIT" --version
```

已有 `.media3` 时不要执行上面的初始化块；先核对 HEAD 和本地修改，再更新配套 settings。该 settings 会从 Media3 目录的父目录寻找 `.github/media3-stubs`，所以推荐放在当前 TV 工作树的 `.media3/`，不要随意指向其他目录。

根目录 `settings.gradle` 在加载 `MEDIA3_SOURCE_DIR` composite 前，统一调用 [固定补丁脚本](third_party/media3/apply_patches.py)。脚本需 `python3` 和 Git 位于 PATH；先校验固定 HEAD、每份补丁的 SHA-256 和目标文件的完整 blob ID，再应用暂停弹幕开关与弹幕时间索引修复。首次应用与重复构建均可通过；不匹配的 HEAD、部分应用、目标文件额外改动或不兼容补丁会明确失败，不自动 checkout/reset。其他文件（包括 composite settings）的本地改动会保留。补丁基准、SHA 及更新约束见 [Media3 补丁说明](third_party/media3/README.md)。

每个新终端都要重新导出 `MEDIA3_SOURCE_DIR`、`JAVA_HOME`、SDK 路径和 `TV_SDK_INIT`。记录 TV 提交、固定 Media3 提交及补丁 SHA，才能复现相同源码。

## 构建 APK

Debug 使用 Android 默认调试签名，不需要配置 release keystore：

```bash
./gradlew -I "$TV_SDK_INIT" \
  :app:assembleLeanbackArm64_v8aDebug \
  --no-daemon --build-cache --max-workers=2
```

其他变体可替换任务名：

```bash
./gradlew -I "$TV_SDK_INIT" :app:assembleLeanbackArmeabi_v7aDebug --max-workers=2
./gradlew -I "$TV_SDK_INIT" :app:assembleMobileArm64_v8aDebug --max-workers=2
```

默认 APK 路径如下，均相对于仓库根目录。Release 构建完成后还会将 release APK 复制到 `Release/apk/`；debug APK 保留在下表的输出目录：

| 变体 | APK |
| --- | --- |
| TV ARM64 debug | `app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk` |
| TV ARMv7 debug | `app/build/outputs/apk/leanbackArmeabi_v7a/debug/app-leanback-armeabi_v7a-debug.apk` |
| Mobile ARM64 debug | `app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk` |
| TV ARM64 release | `app/build/outputs/apk/leanbackArm64_v8a/release/leanback-arm64_v8a.apk` |
| TV ARMv7 release | `app/build/outputs/apk/leanbackArmeabi_v7a/release/leanback-armeabi_v7a.apk` |

### Release 签名

`app/build.gradle` 从根目录 `local.properties` 读取签名配置。使用已有发布密钥，或为本地验证单独创建测试密钥；下面的命令交互式询问密码，不把密码放进命令历史：

```bash
mkdir -p "$HOME/.local/share/tv-build"
keytool -genkeypair -v -storetype JKS \
  -keystore "$HOME/.local/share/tv-build/local-release.jks" \
  -alias local -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Local Build, O=TV, C=US"
```

在 `local.properties` 中填写以下字段，保留文件已有配置。示例值需替换为自己的绝对路径和密码，properties 文件不会展开 `$HOME`：

```properties
storeFile=/absolute/path/to/local-release.jks
keyAlias=local
storePassword=填写密钥库密码
keyPassword=填写密钥密码
```

`storeFile` 也支持相对路径，但基准是 `app/`，例如 `../release.jks` 指向仓库根目录。未填写 `keyPassword` 时会使用 `storePassword`。`local.properties` 和 `*.jks` 已被忽略，不应提交；持续更新同一个安装包需要保留相同签名密钥。

TMDB 配置是可选项：`tmdbApiKey` / `tmdbLogoSize`，或环境变量 `TMDB_API_KEY` / `TMDB_LOGO_SIZE`；logo 尺寸默认 `w500`。它们不属于签名配置。

```bash
./gradlew -I "$TV_SDK_INIT" \
  :app:assembleLeanbackArm64_v8aRelease \
  :app:assembleLeanbackArmeabi_v7aRelease \
  --no-daemon --build-cache --max-workers=2
```

## 测试与安装

### 单元测试和静态检查

沿用相同的 Media3 与 SDK 环境，无需连接设备：

```bash
./gradlew -I "$TV_SDK_INIT" \
  :app:testLeanbackArm64_v8aDebugUnitTest \
  :app:lintLeanbackArm64_v8aDebug --max-workers=2
```

报告分别位于 `app/build/reports/tests/testLeanbackArm64_v8aDebugUnitTest/` 和 `app/build/reports/lint-results-leanbackArm64_v8aDebug.html`。

### ARM 播放器回归

选择专用 ARM64 Android 测试设备，打开调试并授权 adb。以下命令使用默认应用 ID `com.fongmi.android.tv`；测试会修改部分播放器偏好，已有安装应先备份数据。设备已有不同签名的同包名应用时，需要自行处理签名冲突。

```bash
export DEVICE_SERIAL="替换为 adb devices 中的设备序列号"
adb devices
adb -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abilist
./gradlew -I "$TV_SDK_INIT" \
  :app:assembleLeanbackArm64_v8aDebug \
  :app:assembleLeanbackArm64_v8aDebugAndroidTest --max-workers=2
adb -s "$DEVICE_SERIAL" install -r \
  app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk
adb -s "$DEVICE_SERIAL" install -r \
  app/build/outputs/apk/androidTest/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug-androidTest.apk
```

完整播放器测试还需要素材。下面在仓库外生成合成视频，并下载脚本中固定 SHA-256 的公开字幕回归样本，再交给设备内的 loopback HTTP 服务；服务保留 Range 和每个视频独立请求头的检查。

```bash
sudo apt-get install -y ffmpeg fonts-dejavu-core
export TV_FIXTURES="$(mktemp -d)"
CORE_REAL_SAMPLES=1 bash tools/player-core-fixtures.sh "$TV_FIXTURES"
tar -czf "$TV_FIXTURES.tar.gz" -C "$TV_FIXTURES" .
adb -s "$DEVICE_SERIAL" push "$TV_FIXTURES.tar.gz" /data/local/tmp/core-fixtures.tar.gz
adb -s "$DEVICE_SERIAL" shell 'run-as com.fongmi.android.tv mkdir -p files/core-fixtures'
adb -s "$DEVICE_SERIAL" shell 'cat /data/local/tmp/core-fixtures.tar.gz | run-as com.fongmi.android.tv tar -xz -C files/core-fixtures'
adb -s "$DEVICE_SERIAL" shell am instrument -w -r \
  -e core_fixture_root core-fixtures \
  -e class com.fongmi.android.tv.test.PlayerCoreTest,com.fongmi.android.tv.player.exo.NextMediaPreloadTest,com.fongmi.android.tv.test.ArmNativeSmokeTest \
  com.fongmi.android.tv.test/androidx.test.runner.AndroidJUnitRunner
```

`PlayerCoreTest` 覆盖 Exo 字幕与音效，`NextMediaPreloadTest` 覆盖预加载，`ArmNativeSmokeTest` 覆盖实际 MPV、扩展 JNI 与 Python 原生模块。服务仅在传入测试参数时开启，并在各测试类结束后关闭。详细场景和验证边界见 [播放器核心说明](docs/player-core-sync.md)。

### x86 模拟器的边界

当前 Gradle 只提供 ARM64 与 ARMv7 两种 ABI。模板的 x86_64 / API 24 模拟器不能直接安装原始 ARM64 APK；其 `prepare-ui-apk.sh` 会另制 UI 测试包，调整原生库并跳过 Python 初始化。该产物仅用于界面联调，不用于发布，也不能替代 ARM 包的 MPV、Python 或硬件解码验证。模拟器的安装与启动命令见 [模板 README](https://github.com/zhhshss/tv-codespace-template#模拟器-ui-验证)。
