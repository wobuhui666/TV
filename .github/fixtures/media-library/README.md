# 媒体库离线界面夹具

供 `LibraryExperienceAndroidTest` 使用。六张 JPEG 逐字复制自既有媒体库参考图片夹具，分别提供电影、剧集、动画的背景与分集缩略图；本集仅保留测试 `@Before` 必需的六张图片。背景均为 960×480，支持实际 Bitmap 解码及 TV 显示范围断言。文件摘要见 `SHA256SUMS`。

`series-logo.png` 与 `anime-logo.png` 是 Pillow 生成的确定性透明文字测试图，分别标注 SERIES 和 ANIME，四边保留透明边界。它们用于检查真实 PNG 加载、透明边缘对比和原语言 Logo URL 回退，不代表真实作品 Logo。

其他人物、推荐、预告与剧照 JPG 请求沿用测试现有的背景图回退，因此本集验证操作与布局，不能用于证明所有参考图片内容均正确。测试不访问线上 TMDB、片源或媒体，不需要真实 API Key；使用构建注入的非空 `compact-ui-fixture` 占位值。

在隔离 Preview APK 安装后，把本目录的 `.jpg` 和 `.png` 文件推送到 `/sdcard/Android/data/com.fongmi.android.tv.preview/files/library-fixtures/`。测试包需通过 `-PtvPreview` 构建。
