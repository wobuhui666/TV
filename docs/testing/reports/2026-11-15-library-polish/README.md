# 媒体库与全屏播放器实测

基于提交 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec` 加工作区修改。使用用户指定的 `https://9280.kstore.vip/aiwex.json`，真实请求 TMDB、片源插件与视频；在线过程没有替换响应或使用夹具。运行环境为既有 Codespace 的 Android 12 / API 31、KVM、`x86_64` 与 `libndk_translation` ARM64 桥，安装完整 ARM64 APK，保留原生库和 Python。该环境可以检查实际画面与遥控器操作，不能替代 ARM 电视硬件解码验证。

## 确认的问题与修改

| 实测或代码证据 | 修改 |
| --- | --- |
| 搜索《庆余年》得到 103 条资源，但海报缩成细线。Kotlin `LayoutParams.apply` 中的 `width` 指向 `MATCH_PARENT=-1`，未使用卡片宽度 | 明确使用卡片宽度计算 2:3 海报；继续使用 App 原生海报组件 |
| 全屏隐藏原来的选集区域，控制栏没有任意选集入口 | 增加直接选集按钮、定位当前集的回收列表；可用时提供线路与画质选择 |
| 聚焦其他集改写主播放目标；切季等待时旧季卡片仍可点击 | 浏览焦点与播放目标分开；切季立即移除旧卡并显示加载状态 |
| 正在选源时点击另一片源，只取消旧请求 | 一次点击取消旧请求并选择新片源；主按钮明确提供取消 |
| 多线路全集排在一条横排，加载状态消失时焦点无交接 | 原始剧集按线路与每 40 集范围分组，显示五列选集；加载完成交接焦点 |
| aiwex 同名同年、完整剧集的详情因分类只写“剧情,古装,国产”或缺失，被自动播放全部拒绝 | 同一线路至少两个不同的明确剧集编号可补足类型证据；生成序号、纯数字文件、预告、分段与冲突资料仍不据此自动播放 |

详情把播放、换源与收藏放在标题下方，主按钮标明续播集数与时间。搜索筛选直接显示，分页不再使结果区反复跳动；卡片只显示作品信息与已确认资源数量。全屏信息显示作品名、当前集、片源与线路。搜索与详情移除额外重试按钮，使用简短状态文字。

参考了 [Jellyfin Android TV 搜索焦点分组](https://github.com/jellyfin/jellyfin-androidtv/blob/d2fd0372e8b284a88f7b5b60f8682f717b1d3e7f/app/src/main/java/org/jellyfin/androidtv/ui/search/SearchFragment.kt)、[Jellyfin 详情续播](https://github.com/jellyfin/jellyfin-androidtv/blob/master/app/src/main/java/org/jellyfin/androidtv/ui/itemdetail/FullDetailsFragment.java) 与 [Google JetStream 详情布局](https://github.com/android/tv-samples/blob/main/JetStreamCompose/jetstream/src/main/java/com/google/jetstream/presentation/screens/movies/MovieDetails.kt) 的交互方式，未复制其实现。

## 基线实播

旧包 SHA256：`d1bf9628289c87ae315bac9c42924f988162c448a6f664197ce5bdb79636260d`。配置加载与插件初始化成功，《庆余年》第一季详情显示 43 个资源；手动选“文才｜秒播”后播放第 1 集，实际看到连续视频画面，媒体为 1280×720、H.264 8bit / SDR、AAC 2.0、时长 44:47。暂停于 02:03，返回详情保留进度并恢复片源卡焦点。旧播放列表只有上集、下集、换源、重播、刷新，没有选集。

首次自动播放失败后回到资源区，稍后的截图确认了该状态，未连续测量完成时间。随后将真实详情中的标题、年份与分类送入原匹配器独立复现：花卷、蜡笔、闪电、文才与下饭样本均 `matches=true`、`score=100`，但 `kind=UNKNOWN`、`automatic=false`；同一文才样本改为明确“电视剧”分类则允许自动播放。完整列表已有明确剧集标签，修复据此补足类型，未放开所有未知分类。

另复核了旧播放器设置侧栏：等待展开动画完成后焦点正常进入侧栏，下键正常移动，未因中间帧截图的底栏描边改动该逻辑。重启后从观看记录打开详情再点主按钮，也能从文才源 02:03 继续播放。

## 新版验收

正在构建并执行新版在线操作与 Android 回归；此段将在验收后记录最终 APK、结果和实际截图。上述基线实播不代表新版已通过验收。构建、原始网络日志与 APK 保存在文档目录外。
