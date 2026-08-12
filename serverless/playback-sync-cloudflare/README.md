# WebHTV Playback Sync Cloudflare Worker

这是独立的观影记录同步参考服务，只提供 `webhtv.playback.v1`，不包含远程控制 Relay。Token 仅用于 SHA-256 Durable Object 分区，不写入 SQLite 或日志；`configKey` 在分区内继续隔离配置。

部署：复制 `wrangler.toml.example` 为 `wrangler.toml`，执行 `npm install`、`npx wrangler deploy`。

上传：

```sh
curl -X POST 'https://worker.example/api/playback/sync' \
  -H 'Content-Type: application/json' -H 'X-WebHTV-Token: secret' \
  -H 'X-WebHTV-Config-Key: sha256-config-key' \
  --data '{"schema":"webhtv.playback.v1","event":"playback.progress","eventId":"evt-1","siteKey":"site","vodId":"vod","updatedAt":1770000000000}'
```

增量拉取：

```sh
curl 'https://worker.example/api/playback/sync?since=0&limit=100' \
  -H 'X-WebHTV-Token: secret' -H 'X-WebHTV-Config-Key: sha256-config-key'
```

状态：在同一 GET 地址增加 `status=1`；Token 分区级摘要使用 `status=1&allConfigs=1`，不需要提交 `configKey`。服务限制请求体 128 KiB、上传 100 条、拉取 1000 条；全量删除必须提交 `scope=all` 与 `confirm=true`。
