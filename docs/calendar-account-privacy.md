# Calendar account privacy / 追更日历账号隐私

Updated: 2026-11-28. This notice covers the Trakt and Bangumi account features in the TV project at [wobuhui666/TV](https://github.com/wobuhui666/TV).

## Trakt

Signing in is optional and uses Trakt's device-code authorization. You sign in on Trakt's website; TV does not receive your Trakt password. TV sends its application Client ID, device authorization code and, after authorization, access or refresh tokens to Trakt over HTTPS.

If the app has no preset Client ID, the TV can show a temporary QR code for its local phone page. That page accepts a Client ID and displays the public verification code and a link to Trakt. It does not receive the private device code or your Trakt access/refresh tokens. The same temporary pairing and local-network constraints described below apply. Cancelled or unsuccessful authorization keeps the previous account connection.

TV reads your TV show watchlist and personal airing schedule to display followed titles. When you explicitly follow or unfollow a show, TV sends its identifier to Trakt to add or remove it from your watchlist. TV does not automatically scrobble playback, upload local playback history or ratings, or transfer your Trakt account data to Bangumi or other tracking services.

Access and refresh tokens are encrypted using Android Keystore and stored on your device. The imported followed-title list and last synchronization time are also stored locally. These account credentials and imported lists are separate from local favorites and are not included in the app's favorites synchronization or WebDAV backup. Account requests go to Trakt's HTTPS endpoints, which receive the network and request information needed to provide their service, including the requesting IP address and app version.

Choose **Calendar → Trakt → Account → Disconnect account** to delete the saved Trakt credentials and imported list from this device. Disconnecting does not change your remote watchlist or revoke authorization at Trakt. You can separately revoke TV's authorization in your Trakt account settings.

## Bangumi

Bangumi connection uses a personal access token created on Bangumi's website. You may enter it on the TV or scan the TV's temporary QR code and paste it on the phone page served by that TV. The phone sends the token to the TV over the local network; this connection uses HTTP, so use a trusted network. The page does not save the account token in browser storage or put it in URLs. A temporary pairing code is kept in the browser tab's session storage to support refreshing the page, and is cleared when the page observes completion or expiry.

The TV checks the account and imports animation titles marked as wish/doing. Explicit follow or pause actions update the selected Bangumi collection entry. The token is encrypted in the device's account storage, separate from favorites and WebDAV. Choose **Calendar → Bangumi → Account → Disconnect account** to remove the saved credentials and imported list. Revoke the personal access token on Bangumi's website if you also want to invalidate it there. Bangumi and Trakt accounts are connected separately; the app does not transfer their account lists between services.

## Questions

Contact the project through [GitHub Issues](https://github.com/wobuhui666/TV/issues). Do not include passwords, account tokens or application secrets in an issue. Trakt and Bangumi operate their own services and their privacy policies apply to information they receive.

## 中文说明

追更日历账号连接为可选功能。Trakt 使用官网设备码授权，应用不会接收密码；只读取电视剧 watchlist 与个人排期，并在用户明确点击追剧／取消追剧时修改对应条目，不自动上传观看进度、评分或本地播放历史，不向 Bangumi 等其他服务转移 Trakt 账号数据。

Trakt 已配置 Client ID 时，电视二维码直接打开官网。未配置时，可用手机扫描电视的局域网配对二维码填写 Client ID，再在网页打开官网授权；本地网页只接收公开验证码和官网地址，不接收私有设备码、访问令牌或刷新令牌。取消或失败保留之前的账号连接。

Bangumi 使用官网生成的个人访问令牌，可直接在电视输入，或手机扫码后在电视提供的网页粘贴。手机到电视使用局域网 HTTP，请使用信任的网络；网页不将账号令牌写入浏览器存储或 URL，只在当前标签页暂存配对码以支持刷新。校验成功后导入「想看／在看」动画，追剧／搁置仅在用户主动操作时更新选中条目。

两种账号令牌均经 Android Keystore 加密后保存在本机，导入列表和同步时间也保存在本机；与本地收藏、多设备收藏同步及 WebDAV 备份独立。账号 API 经 HTTPS 访问对应官方服务，服务会收到提供功能所需的请求、来源 IP 和应用版本。**账号 → 断开账号** 清除本机凭据与导入列表，不修改远端资料，也不自动撤销官网授权；需要完全撤销时，请在 Trakt 授权设置或 Bangumi 个人令牌页面操作。
