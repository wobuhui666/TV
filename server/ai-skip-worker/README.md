# TV AI Skip Worker

Cloudflare Worker for private intro/outro detection. Android uploads up to three 30-second mono WAV
samples from each end; the Worker stores them temporarily in R2, queues a Gemini analysis job, and
caches validated results in KV.

## Deploy

```bash
npm install
npx wrangler kv namespace create AI_SKIP_KV
npx wrangler r2 bucket create tv-ai-skip-samples
npx wrangler r2 bucket lifecycle add tv-ai-skip-samples expire-samples samples/ --expire-days 1
npx wrangler queues create tv-ai-skip-jobs
npx wrangler secret put AI_SKIP_TOKEN
npx wrangler secret put GEMINI_API_KEY
npx wrangler deploy
```

Replace the KV namespace ID in `wrangler.toml` before deploying. The R2 lifecycle rule expires
objects under `samples/` after one day; completed jobs also delete their samples immediately.
The included rate-limit binding allows 60 authenticated requests per client IP per minute. Change its
`namespace_id` if `1001` is already used by another Worker in the same Cloudflare account.

The Android TV settings require the deployed Worker URL and the same `AI_SKIP_TOKEN`. The configured
OpenAI-compatible upstream URL and Gemini model are regular Worker variables; the API key remains a
server-side secret. `GET /v1/health` can be used to verify authentication and connectivity.

Native Android requests do not send an `Origin` header and are accepted. Browser requests are denied
unless `[vars].ALLOWED_ORIGIN` is set to their exact origin, for example `https://tv.example.com`.

## API

- `PUT /v1/uploads/:uploadId`: upload an `audio/wav` sample.
- `POST /v1/jobs`: create or reuse an analysis job.
- `GET /v1/jobs/:jobId`: poll task state and boundaries.
- `GET /v1/jobs/media/:mediaKey`: retrieve a cached completed result.
- `GET /v1/jobs/series/:seriesKey`: retrieve the latest usable result for another episode in the same series.
- `POST /v1/jobs/:jobId/feedback`: store corrected boundaries.

All endpoints require `Authorization: Bearer <AI_SKIP_TOKEN>`. Authenticated requests are rate-limited
by Cloudflare's `cf-connecting-ip`; preflight requests do not consume the quota.
