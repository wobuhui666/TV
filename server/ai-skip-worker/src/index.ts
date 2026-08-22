interface Env {
  AI_SKIP_KV: KVNamespace;
  AI_SKIP_R2: R2Bucket;
  AI_SKIP_QUEUE: Queue<{ jobId: string }>;
  AI_SKIP_RATE_LIMITER: RateLimit;
  AI_SKIP_TOKEN: string;
  GEMINI_API_KEY: string;
  GEMINI_MODEL: string;
  AI_API_BASE_URL: string;
  ALLOWED_ORIGIN?: string;
  RESULT_TTL_SECONDS?: string;
  MAX_SAMPLE_BYTES?: string;
  MIN_CONFIDENCE?: string;
}

type JobStatus = "pending" | "processing" | "completed" | "failed";

interface Sample {
  side: "opening" | "ending";
  startMs: number;
  durationMs: number;
  objectKey: string;
}

interface Job {
  jobId: string;
  mediaKey: string;
  seriesKey: string;
  episode: string;
  durationMs: number;
  samples: Sample[];
  status: JobStatus;
  openingMs?: number;
  endingMs?: number;
  openingConfidence?: number;
  endingConfidence?: number;
  error?: string;
  createdAt: number;
  updatedAt: number;
}

interface GeminiPart {
  inlineData?: { mimeType: string; data: string };
  text?: string;
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };
const MAX_SAMPLE_DURATION_MS = 30_000;
const MAX_TOTAL_SAMPLES = 6;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!isOriginAllowed(request.headers.get("origin"), env.ALLOWED_ORIGIN)) {
      return json({ error: "origin_not_allowed" }, 403);
    }
    if (request.method === "OPTIONS") return withCors(new Response(null, { status: 204 }), request, env);
    if (!authorized(request, env)) return withCors(json({ error: "unauthorized" }, 401), request, env);
    const rateLimit = await env.AI_SKIP_RATE_LIMITER.limit({ key: clientRateLimitKey(request) });
    if (!rateLimit.success) {
      return withCors(json({ error: "rate_limited" }, 429, { "retry-after": "60" }), request, env);
    }

    return withCors(await route(request, env), request, env);
  },

  async queue(batch: MessageBatch<{ jobId: string }>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      try {
        const job = await env.AI_SKIP_KV.get<Job>(`job:${message.body.jobId}`, "json");
        if (!job) {
          message.retry({ delaySeconds: 30 });
          continue;
        }
        await processJob(env, job);
        message.ack();
      } catch {
        message.retry({ delaySeconds: 30 });
      }
    }
  }
};

async function route(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/$/, "");
    if (request.method === "GET" && path === "/v1/health") return json({ status: "ok" });
    if (request.method === "PUT" && path.startsWith("/v1/uploads/")) {
      return upload(request, env, path.slice("/v1/uploads/".length));
    }
    if (request.method === "POST" && path === "/v1/jobs") return createJob(request, env);
    if (request.method === "GET" && path.startsWith("/v1/jobs/media/")) {
      return getMediaJob(env, path.slice("/v1/jobs/media/".length));
    }
    if (request.method === "GET" && path.startsWith("/v1/jobs/")) {
      return getJob(env, path.slice("/v1/jobs/".length));
    }
    if (request.method === "POST" && path.startsWith("/v1/jobs/") && path.endsWith("/feedback")) {
      return feedback(request, env, path.slice("/v1/jobs/".length, -"/feedback".length));
    }
    return json({ error: "not_found" }, 404);
}

function withCors(response: Response, request: Request, env: Env): Response {
  const headers = new Headers(response.headers);
  const origin = request.headers.get("origin")?.trim();
  if (origin && isOriginAllowed(origin, env.ALLOWED_ORIGIN)) {
    headers.set("access-control-allow-origin", origin);
    headers.append("vary", "Origin");
  }
  headers.set("access-control-allow-headers", "authorization, content-type");
  headers.set("access-control-allow-methods", "GET, POST, PUT, OPTIONS");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function isOriginAllowed(origin: string | null, allowedOrigin: string | undefined): boolean {
  if (!origin) return true;
  const allowed = allowedOrigin?.trim();
  return Boolean(allowed && origin === allowed);
}

function clientRateLimitKey(request: Request): string {
  return request.headers.get("cf-connecting-ip")?.trim() || "unknown";
}

function authorized(request: Request, env: Env): boolean {
  const expected = env.AI_SKIP_TOKEN?.trim();
  if (!expected) return false;
  return request.headers.get("authorization") === `Bearer ${expected}`;
}

function json(value: unknown, status = 200, extraHeaders?: HeadersInit): Response {
  return new Response(JSON.stringify(value), { status, headers: { ...JSON_HEADERS, ...extraHeaders } });
}

async function upload(request: Request, env: Env, uploadId: string): Promise<Response> {
  if (!/^[A-Za-z0-9_-]{12,80}$/.test(uploadId)) return json({ error: "invalid_upload_id" }, 400);
  const contentType = request.headers.get("content-type")?.split(";", 1)[0] ?? "";
  if (contentType !== "audio/wav" && contentType !== "audio/x-wav") return json({ error: "wav_required" }, 415);
  const maxBytes = numberEnv(env.MAX_SAMPLE_BYTES, 12_000_000);
  const length = Number(request.headers.get("content-length") ?? 0);
  if (length > maxBytes) return json({ error: "sample_too_large" }, 413);
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length <= 44 || bytes.length > maxBytes) return json({ error: "sample_too_large" }, 413);
  if (!isWav(bytes)) return json({ error: "invalid_wav" }, 400);
  const objectKey = `samples/${uploadId}.wav`;
  await env.AI_SKIP_R2.put(objectKey, bytes, {
    httpMetadata: { contentType: "audio/wav" }
  });
  return json({ objectKey }, 201);
}

async function createJob(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  if (!body) return json({ error: "invalid_json" }, 400);
  const mediaKey = stringValue(body.mediaKey, 160);
  const seriesKey = stringValue(body.seriesKey, 160);
  const episode = stringValue(body.episode, 80);
  const durationMs = integer(body.durationMs);
  const samples = parseSamples(body.samples, durationMs);
  if (!mediaKey || !seriesKey || !episode || durationMs <= 0 || samples.length < 2
      || !samples.some(sample => sample.side === "opening") || !samples.some(sample => sample.side === "ending")) {
    return json({ error: "invalid_job" }, 400);
  }
  const cacheKey = `media:${mediaStorageKey(mediaKey)}`;
  const cached = await env.AI_SKIP_KV.get<Job>(cacheKey, "json");
  if (cached && cached.status === "completed") {
    await deleteUnusedSamples(env, samples, []);
    return json(publicJob(cached));
  }
  const existingJobId = await env.AI_SKIP_KV.get(`media-job:${mediaStorageKey(mediaKey)}`);
  if (existingJobId) {
    const existing = await env.AI_SKIP_KV.get<Job>(`job:${existingJobId}`, "json");
    if (existing && (existing.status === "pending" || existing.status === "processing")) {
      await deleteUnusedSamples(env, samples, existing.samples);
      return json(publicJob(existing), 202);
    }
  }
  const job: Job = {
    jobId: crypto.randomUUID(), mediaKey, seriesKey, episode, durationMs, samples,
    status: "pending", createdAt: Date.now(), updatedAt: Date.now()
  };
  await saveJob(env, job);
  await env.AI_SKIP_QUEUE.send({ jobId: job.jobId });
  return json(publicJob(job), 202);
}

async function getJob(env: Env, jobId: string): Promise<Response> {
  if (!/^[0-9a-f-]{20,80}$/i.test(jobId)) return json({ error: "invalid_job_id" }, 400);
  const job = await env.AI_SKIP_KV.get<Job>(`job:${jobId}`, "json");
  return job ? json(publicJob(job)) : json({ error: "not_found" }, 404);
}

async function getMediaJob(env: Env, encodedKey: string): Promise<Response> {
  let mediaKey: string;
  try { mediaKey = decodeURIComponent(encodedKey); } catch { return json({ error: "invalid_media_key" }, 400); }
  if (!mediaKey || mediaKey.length > 160) return json({ error: "invalid_media_key" }, 400);
  const job = await env.AI_SKIP_KV.get<Job>(`media:${mediaStorageKey(mediaKey)}`, "json");
  return job ? json(publicJob(job)) : json({ error: "not_found" }, 404);
}

async function feedback(request: Request, env: Env, jobId: string): Promise<Response> {
  const job = await env.AI_SKIP_KV.get<Job>(`job:${jobId}`, "json");
  if (!job) return json({ error: "not_found" }, 404);
  const body = await readJson(request);
  if (!body) return json({ error: "invalid_json" }, 400);
  const openingMs = integer(body.openingMs);
  const endingMs = integer(body.endingMs);
  if (openingMs < 0 || endingMs < 0 || openingMs + endingMs >= job.durationMs) return json({ error: "invalid_result" }, 400);
  job.openingMs = openingMs || undefined;
  job.endingMs = endingMs || undefined;
  job.openingConfidence = 1;
  job.endingConfidence = 1;
  job.status = "completed";
  job.updatedAt = Date.now();
  await saveJob(env, job);
  return json(publicJob(job));
}

async function processJob(env: Env, initial: Job): Promise<void> {
  const job = await env.AI_SKIP_KV.get<Job>(`job:${initial.jobId}`, "json");
  if (!job || job.status !== "pending") return;
  try {
    const parts: GeminiPart[] = [];
    for (const sample of job.samples) {
      const object = await env.AI_SKIP_R2.get(sample.objectKey);
      if (!object) throw new Error("sample_missing");
      const bytes = new Uint8Array(await object.arrayBuffer());
      parts.push({ text: `${sample.side} sample at ${sample.startMs}ms:` });
      parts.push({ inlineData: { mimeType: "audio/wav", data: toBase64(bytes) } });
    }
    job.status = "processing";
    job.updatedAt = Date.now();
    await saveJob(env, job);
    const result = await callGemini(env, job, parts);
    const minConfidence = numberEnv(env.MIN_CONFIDENCE, 0.8);
    job.openingMs = validBoundary(result.openingMs, job.durationMs) ? result.openingMs : undefined;
    job.endingMs = validBoundary(result.endingMs, job.durationMs) ? result.endingMs : undefined;
    job.openingConfidence = result.openingConfidence;
    job.endingConfidence = result.endingConfidence;
    if ((job.openingConfidence ?? 0) < minConfidence) job.openingMs = undefined;
    if ((job.endingConfidence ?? 0) < minConfidence) job.endingMs = undefined;
    if ((job.openingMs ?? 0) + (job.endingMs ?? 0) >= job.durationMs) {
      job.openingMs = undefined;
      job.endingMs = undefined;
    }
    job.status = "completed";
    job.updatedAt = Date.now();
    await saveJob(env, job);
    for (const sample of job.samples) await env.AI_SKIP_R2.delete(sample.objectKey);
  } catch (error) {
    if (error instanceof Error && error.message === "sample_missing"
        && Date.now() - job.createdAt < 120_000) {
      job.status = "pending";
      job.updatedAt = Date.now();
      await saveJob(env, job);
      throw error;
    }
    job.status = "failed";
    job.error = error instanceof Error ? error.message.slice(0, 120) : "analysis_failed";
    job.updatedAt = Date.now();
    await saveJob(env, job);
  }
}

async function callGemini(env: Env, job: Job, sampleParts: GeminiPart[]) {
  const prompt = `Analyze opening and ending credits in the supplied audio samples for a TV episode. Return only JSON with integer openingMs and endingMs (skip durations from the beginning/end), openingConfidence and endingConfidence between 0 and 1. Use 0 for an undetectable boundary. Episode duration is ${job.durationMs}ms. Do not infer boundaries from silence alone.`;
  const baseUrl = env.AI_API_BASE_URL?.trim().replace(/\/+$/, "");
  if (!baseUrl || !baseUrl.startsWith("https://")) throw new Error("ai_api_url_invalid");
  const content = sampleParts.map(part => part.text
    ? { type: "text", text: part.text }
    : { type: "input_audio", input_audio: { data: part.inlineData?.data ?? "", format: "wav" } });
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: "POST", headers: {
      "authorization": `Bearer ${env.GEMINI_API_KEY}`,
      "content-type": "application/json"
    },
    body: JSON.stringify({
      model: env.GEMINI_MODEL || "gemini-3.7-flash",
      messages: [{ role: "user", content: [{ type: "text", text: prompt }, ...content] }],
      response_format: {
        type: "json_schema",
        json_schema: {
          name: "skip_boundaries",
          strict: true,
          schema: {
            type: "object",
            additionalProperties: false,
            required: ["openingMs", "endingMs", "openingConfidence", "endingConfidence"],
            properties: {
              openingMs: { type: "integer" }, endingMs: { type: "integer" },
              openingConfidence: { type: "number" }, endingConfidence: { type: "number" }
            }
          }
        }
      },
      temperature: 0,
      max_tokens: 2_048
    })
  });
  if (!response.ok) throw new Error(`gemini_${response.status}`);
  const payload = await response.json() as any;
  const text = payload?.choices?.[0]?.message?.content;
  if (typeof text !== "string") throw new Error("gemini_empty");
  if (payload?.choices?.[0]?.finish_reason === "length") throw new Error("gemini_truncated");
  try {
    return parseGeminiText(text);
  } catch {
    throw new Error("gemini_invalid_json");
  }
}

function parseGeminiText(text: string) {
  const normalized = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
  const parsed = JSON.parse(normalized);
  return {
    openingMs: integer(parsed.openingMs), endingMs: integer(parsed.endingMs),
    openingConfidence: clamp(parsed.openingConfidence), endingConfidence: clamp(parsed.endingConfidence)
  };
}

async function saveJob(env: Env, job: Job): Promise<void> {
  const ttl = numberEnv(env.RESULT_TTL_SECONDS, 2_592_000);
  await env.AI_SKIP_KV.put(`job:${job.jobId}`, JSON.stringify(job), { expirationTtl: ttl });
  await env.AI_SKIP_KV.put(`media-job:${mediaStorageKey(job.mediaKey)}`, job.jobId, { expirationTtl: ttl });
  if (job.status === "completed") await env.AI_SKIP_KV.put(`media:${mediaStorageKey(job.mediaKey)}`, JSON.stringify(job), { expirationTtl: ttl });
}

function publicJob(job: Job) {
  return { jobId: job.jobId, mediaKey: job.mediaKey, status: job.status, openingMs: job.openingMs ?? 0, endingMs: job.endingMs ?? 0, confidence: { opening: job.openingConfidence ?? 0, ending: job.endingConfidence ?? 0 }, error: job.error, updatedAt: job.updatedAt };
}

function parseSamples(value: unknown, durationMs: number): Sample[] {
  if (!Array.isArray(value) || value.length > MAX_TOTAL_SAMPLES) return [];
  return value.map((item: any) => ({ side: item.side, startMs: integer(item.startMs), durationMs: integer(item.durationMs), objectKey: stringValue(item.objectKey, 180) }))
    .filter((item): item is Sample => (item.side === "opening" || item.side === "ending") && item.startMs >= 0 && item.durationMs > 0 && item.durationMs <= MAX_SAMPLE_DURATION_MS && item.startMs + item.durationMs <= durationMs && /^samples\/[A-Za-z0-9_-]{12,80}\.wav$/.test(item.objectKey));
}

async function deleteUnusedSamples(env: Env, incoming: Sample[], keep: Sample[]): Promise<void> {
  const retained = new Set(keep.map(sample => sample.objectKey));
  await Promise.all(incoming.filter(sample => !retained.has(sample.objectKey)).map(sample => env.AI_SKIP_R2.delete(sample.objectKey)));
}

async function readJson(request: Request): Promise<any | null> {
  try { return await request.json(); } catch { return null; }
}

function integer(value: unknown): number { return Number.isFinite(Number(value)) ? Math.trunc(Number(value)) : -1; }
function clamp(value: unknown): number { const number = Number(value); return Number.isFinite(number) ? Math.max(0, Math.min(1, number)) : 0; }
function validBoundary(value: number, durationMs: number): boolean { return value >= 0 && value < durationMs; }
function numberEnv(value: string | undefined, fallback: number): number { const parsed = Number(value); return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback; }
function stringValue(value: unknown, max: number): string { return typeof value === "string" ? value.trim().slice(0, max) : ""; }
function mediaStorageKey(value: string): string { return encodeURIComponent(value); }
function isWav(bytes: Uint8Array): boolean { return ascii(bytes, 0, 4) === "RIFF" && ascii(bytes, 8, 12) === "WAVE"; }
function ascii(bytes: Uint8Array, start: number, end: number): string { return String.fromCharCode(...bytes.subarray(start, end)); }
function toBase64(bytes: Uint8Array): string { let binary = ""; const chunk = 0x8000; for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + chunk, bytes.length))); return btoa(binary); }

export const __test = { parseSamples, validBoundary, mediaStorageKey, isWav, isOriginAllowed, clientRateLimitKey, parseGeminiText };
