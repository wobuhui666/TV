# 真实播放列表测试语料

`playlists.json` 于 2026-11-08 从用户授权配置的真实搜索、详情响应抽取，保留原片名、年份、类型、线路名称和集数标签。配置地址、媒体地址、请求头及来源 ID 均已移除。详情缺片名时保留真实搜索片名，对齐 `Vod.checkName` 的回退行为，并注明 `titleOrigin`。

《莲花楼》《绝命毒师》第一、二季和《白莲花度假村》保留完整列表。《你好星期六》《种地吧3》《声生不息·家年华》保留注明的子集及原列表位置、数量，覆盖日期、上下期、纯享版、先导篇和超前营业。

`CommunitySkipRealPlaylistAndroidTest` 使用这些真实标题和标签请求 TMDB，确认身份后通过实际 `CommunitySkipRuntime`、HTTP 区间解析、`VodPlaybackController` 和 ExoPlayer 验证后台查询、首帧、进度、片头和片尾跳转及已消费区间保护。每集使用相同本地 H264 视频的唯一 URI fragment，保持原选集位置和重复版本的区别。

视频由 GHA 生成，时长为 90 秒。自动跳转断言使用明确标注的受控 HTTP 区间；公共社区接口另行真实请求，并明确记录替代视频时长。测试通过不表示原始播放地址可达，也不证明原剧集的社区区间覆盖。

运行 `.github/workflows/community-skip.yml` 的 `playlist_fixture=true`；真实 TMDB 密钥仅通过测试 App 私有文件输入，不编入 APK。公开结果要求实际视频解码、播放推进和片头、片尾 seek 均成功，原始日志不上传。
