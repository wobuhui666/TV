# 本地构建环境配置

本文档按 `.github/workflows/build-release.yml` 中已成功的 GitHub Actions 配置整理，用于在本机复现 release APK 构建环境。

## 环境要求

- OS: Linux
- JDK: 21
- Python: 3.10
- Android SDK:
  - `platform-tools`
  - `platforms;android-37.0`
  - `build-tools;37.0.0`
- Media3 源码: `wobuhui666/media` 的 `release-1.11.0-fongmi` 分支
- Gradle: 使用仓库内 `./gradlew` 自动下载的 Gradle 9.1

## 本机已完成配置

本机当前配置如下：

- JDK 21: `/usr/lib/jvm/java-21-openjdk-amd64`
- Android SDK: `/opt/android-sdk`
- Python 3.10: `/root/.local/bin/python3.10`
- Media3 源码: `/root/TV/.media3`
- 本地签名文件: `/root/TV/release.jks`
- 本地签名配置: `/root/TV/local.properties`

`~/.bashrc` 已加入以下环境变量：

```bash
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="/opt/android-sdk"
export MEDIA3_SOURCE_DIR="/root/TV/.media3"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`/root/.local/bin` 已通过 `~/.local/bin/env` 加入 `PATH`，用于暴露 `python3.10` 和 `uv`。

## 从零配置步骤

### 1. 准备 JDK 21

确认 JDK 版本：

```bash
java -version
```

需要看到 Java 21。若路径不是 JDK 21，设置：

```bash
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$PATH"
```

### 2. 准备 Python 3.10

如果系统没有 `python3.10`，可用 `uv` 安装独立 Python：

```bash
curl -LsSf "https://astral.sh/uv/install.sh" | sh
source "$HOME/.local/bin/env"
uv python install "3.10"
python3.10 --version
```

### 3. 准备 Android SDK

设置 SDK 路径：

```bash
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="/opt/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

安装构建所需 SDK：

```bash
yes | sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

当前 SDK 包实际目录名为 `android-37.0`，但 AGP 8.12.3 查找 `android-37`。本机通过兼容链接解决：

```bash
ln -sfn "android-37.0" "$ANDROID_HOME/platforms/android-37"
```

### 4. 准备 Media3 源码

GitHub Actions 会 checkout `wobuhui666/media` 并通过 `MEDIA3_SOURCE_DIR` 做 composite build。本地执行：

```bash
cd "/root/TV"
git clone --depth 1 --branch "release-1.11.0-fongmi" "https://github.com/wobuhui666/media.git" ".media3"
cp ".github/media3-composite-settings.gradle.kts" ".media3/settings.gradle.kts"
export MEDIA3_SOURCE_DIR="/root/TV/.media3"
```

`.media3/` 是本地外部源码目录，已加入 `.git/info/exclude`，不应提交。

### 5. 准备本地签名配置

Release 构建需要签名。CI 使用 GitHub Secrets；本地可生成测试签名：

```bash
cd "/root/TV"
keytool -genkeypair -v \
  -keystore "release.jks" \
  -storepass "android" \
  -keypass "android" \
  -alias "local" \
  -keyalg "RSA" \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Local Build, OU=Local, O=TV, L=Local, S=Local, C=US"

printf '%s\n' \
  'storeFile=../release.jks' \
  'keyAlias=local' \
  'keyPassword=android' \
  'storePassword=android' \
  'tmdbLogoSize=w500' \
  > "local.properties"
```

`release.jks` 和 `local.properties` 已被 `.gitignore` 忽略。正式发布时应替换为自己的 keystore 和密码，不要复用上述测试签名。

TMDB logo 默认通过反代域名请求；如需在客户端显式携带 TMDB key，可在 `local.properties` 追加：

```properties
tmdbApiKey=你的_TMDB_API_KEY
tmdbLogoSize=w500
```

### 6. 授权 Gradle Wrapper

GitHub Actions 中会执行 `chmod +x ./gradlew`。本地执行：

```bash
chmod +x "./gradlew"
```

### 7. 执行与 GitHub Actions 一致的构建

```bash
cd "/root/TV"
export PATH="$HOME/.local/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH"
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="/opt/android-sdk"
export MEDIA3_SOURCE_DIR="/root/TV/.media3"

./gradlew assembleLeanbackArm64_v8aRelease assembleLeanbackArmeabi_v7aRelease --no-daemon --build-cache --max-workers=2
```

构建成功后 APK 位于：

```text
app/build/outputs/apk/leanbackArm64_v8a/release/leanback-arm64_v8a.apk
app/build/outputs/apk/leanbackArmeabi_v7a/release/leanback-armeabi_v7a.apk
```

## 本机验证结果

已使用上述配置完成本地 release 构建：

```text
BUILD SUCCESSFUL in 13m 15s
784 actionable tasks: 670 executed, 114 from cache
```

生成的 APK：

```text
app/build/outputs/apk/leanbackArm64_v8a/release/leanback-arm64_v8a.apk 49574064 bytes
app/build/outputs/apk/leanbackArmeabi_v7a/release/leanback-armeabi_v7a.apk 42693056 bytes
```

## 已知警告

构建中会出现以下警告，目前不阻断 APK 生成：

- AGP 8.12.3 对 `compileSdk 37.0` 提示未正式测试。
- Media3 部分模块使用旧 manifest `package` 属性。
- Java 编译会提示 source/target 8 在 JDK 21 下已过时。
- 部分 native so 无法 strip，会按原样打包。
- `sdkmanager --version` 可能提示 `platforms;android-37.0` 位于 `android-37` 的目录名不一致，这是为兼容 AGP 查找 `android-37` 创建链接导致的；已验证不影响本地 release 构建。
