# 媒体库离线界面夹具

供 `LibraryExperienceAndroidTest` 使用。六张 JPEG 逐字复制自既有媒体库参考图片夹具，分别提供电影、剧集、动画的背景与分集缩略图；本集仅保留测试 `@Before` 必需的六张图片。背景均为 960×480，支持实际 Bitmap 解码及 TV 显示范围断言。文件摘要见 `SHA256SUMS`。

`series-logo.png` 与 `anime-logo.png` 是 Pillow 生成的确定性透明文字测试图，分别标注 SERIES 和 ANIME，四边保留透明边界。它们用于检查真实 PNG 加载、透明边缘对比和原语言 Logo URL 回退，不代表真实作品 Logo。

其他人物、推荐、预告与剧照 JPG 请求沿用测试现有的背景图回退，因此本集验证操作与布局，不能用于证明所有参考图片内容均正确。测试不访问线上 TMDB、片源或媒体，不需要真实 API Key；使用构建注入的非空 `compact-ui-fixture` 占位值。

`-PtvPreview` 将本目录加入 AndroidTest 的 `media-library` assets；生产 APK 不包含这些测试图片。每项媒体库测试由目标应用 UID 读取测试 APK 的图片、校验 SHA-256，复制到自身 external files 下的 `library-fixtures`，再逐项读取并复核摘要。`library-fixture-read.json` 记录 UID、进程、实际目录及八张图片的读取证据，CI 不再依赖 shell 用户预先创建的目录或 adb 推图。

`compact_home` 分别运行 19 项首页／发现／数据库／生命周期测试和 48 项媒体库测试，两组均执行后汇总，失败或跳过不会计为通过。初始化尚未捕获全局状态时，清理不恢复客户端或偏好；捕获之后的初始化失败仍恢复已修改的状态。
