# 原生 TV 浏览操作参考与取舍

本轮基于 `ui/apple-tv-redesign` 的原生海报墙、继续观看和收藏功能，检查同类应用的实际源码与 issue／PR。只借鉴交互逻辑；没有复制对方源码、图片或加入 HTML 页面。

## 继续观看卡片菜单

- Jellyfin Android TV [PR #5708](https://github.com/jellyfin/jellyfin-androidtv/pull/5708) 已合并，修复真实卡片长按进入菜单；[固定版本的 CardPresenter](https://github.com/jellyfin/jellyfin-androidtv/blob/09c912d39226d881d657400432af6e9c26377df3/app/src/main/java/org/jellyfin/androidtv/ui/presentation/CardPresenter.kt#L68) 是对应原生入口。
- SmartTube [VideoMenuPresenter](https://github.com/yuliskov/SmartTube/blob/2f3c73f6a9ea674b6decbf1ce52b86d333edd3e1/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/dialogs/menu/VideoMenuPresenter.java#L493) 提供单条历史移除；其 [#6079](https://github.com/yuliskov/SmartTube/issues/6079) 报告删除历史误影响订阅列表，说明操作必须绑定明确的数据对象。
- Jellyfin [PR #5476](https://github.com/jellyfin/jellyfin-androidtv/pull/5476) 关闭且未合并，描述删空继续观看行后的焦点／重绘问题；本项目用它确定回归场景，不把该 PR 当作已落地方案。
- [FongMi 原有首页](https://github.com/FongMi/TV/blob/c616c0aa3613e87529791587a9f71b78c278c991/app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java#L427) 同样采用二次长按清空历史的交互。这是当前项目所继承的行为，并不适合所有用户。

选择：保留原交互作为默认，新增独立开关。开启后，短按继续观看，长按进入原生菜单，明确提供继续观看、查找其他来源、删除这一条。菜单捕获配置与记录身份，取消不改变记录，删除不波及其他配置或收藏；删除最后一张卡片后焦点仍须可达。

## 首页“我的片单”

- Jellyfin [#1232](https://github.com/jellyfin/jellyfin-androidtv/issues/1232) 提出在首页集中查看收藏，避免逐个媒体库查找；检查时仍为 open。
- 相关 [PR #5786](https://github.com/jellyfin/jellyfin-androidtv/pull/5786) 已关闭且未合并。[查询原型](https://github.com/jellyfin/jellyfin-androidtv/blob/e02d8f8e09de8839c627047d5fffe920dfaec43f/app/src/main/java/org/jellyfin/androidtv/ui/favorites/FavoritesRepository.kt#L29) 复用收藏标记，但将读取失败当成空列表，不适合直接套用。
- SmartTube [#4803](https://github.com/yuliskov/SmartTube/issues/4803) 明确请求独立隐藏首页 Favorites。这支持提供单独开关，关闭展示不应删除收藏。
- FongMi [#126](https://github.com/FongMi/TV/issues/126) 的隐藏首页历史请求被关闭，理由是保持原设计；不能把关闭状态解释成该需求已实现。

选择：可选海报首页中增加一行，复用当前配置的点播收藏与全局发现收藏。读取本地数据库，离线也能显示；保留全部收藏入口。异步请求绑定配置和代次，读取失败保留同配置的已加载内容，切配置立即清掉旧配置的行。保持原有继续观看和推荐行的结构，不在本轮扩展任意行重排。

## 主视觉确认目标与轮播控制

Jellyfin [HomeRowsFragment](https://github.com/jellyfin/jellyfin-androidtv/blob/09c912d39226d881d657400432af6e9c26377df3/app/src/main/java/org/jellyfin/androidtv/ui/home/HomeRowsFragment.kt#L275) 的背景跟随当前选择；[BackgroundService](https://github.com/jellyfin/jellyfin-androidtv/blob/09c912d39226d881d657400432af6e9c26377df3/app/src/main/java/org/jellyfin/androidtv/data/service/BackgroundService.kt#L142) 也有轮播，但没有本项目拟增加的聚焦暂停模式。这部分是对本项目具体问题的修复，不宣称照搬了 Jellyfin 的成熟方案。

本项目旧 `FeaturedVodPresenter` 已在卡片聚焦时暂停；新 `DiscoverHeroPresenter` 却仍会每七秒换片，而且先更新点击对象、后在动画回调中更新片名。

选择：提供原有轮播、聚焦时暂停、仅手动切换三档。默认保持原有自动模式，左右键在各档均可切换；所有模式都让可见片名与点击目标同步更新。迟到图片只影响展示，不能改变确认目标。解绑、进入后台或打开遮挡窗口时撤销计时，返回不叠加多个轮播任务。

## 验收范围

功能入口统一放在“设置 → 首页与发现”；新菜单、片单行默认关闭，轮播默认原有模式。“恢复原有浏览体验”恢复上述默认，不删除历史或收藏。

回归覆盖原生菜单与取消、单条删除与配置变化、空行焦点、收藏隔离与迟到响应、真实轮播计时、模式切换、快速左右后确认、图片延迟／失败以及解绑重绑。具体构建与 Android 验证结果在完成后记入测试报告。
