import { describe, expect, it } from "vitest";
import worker, { __test } from "../src/index";

function env(overrides: Record<string, unknown> = {}) {
  return {
    AI_SKIP_TOKEN: "secret",
    AI_SKIP_RATE_LIMITER: { limit: async () => ({ success: true }) },
    AI_SKIP_KV: { get: async () => null },
    AI_SKIP_R2: { get: async () => null, put: async () => undefined, delete: async () => undefined },
    ...overrides
  } as any;
}

describe("AI skip request validation", () => {
  it("accepts bounded opening and ending samples", () => {
    const samples = __test.parseSamples([
      { side: "opening", startMs: 0, durationMs: 30_000, objectKey: "samples/abcdefghijkl.wav" },
      { side: "ending", startMs: 570_000, durationMs: 30_000, objectKey: "samples/mnopqrstuvwx.wav" }
    ], 600_000);

    expect(samples).toHaveLength(2);
  });

  it("rejects samples outside the media duration", () => {
    const samples = __test.parseSamples([
      { side: "ending", startMs: 590_000, durationMs: 30_000, objectKey: "samples/abcdefghijkl.wav" }
    ], 600_000);

    expect(samples).toHaveLength(0);
  });

  it("rejects samples longer than one 30-second chunk", () => {
    const samples = __test.parseSamples([
      { side: "opening", startMs: 0, durationMs: 30_001, objectKey: "samples/abcdefghijkl.wav" }
    ], 600_000);

    expect(samples).toHaveLength(0);
  });

  it("recognizes a RIFF WAVE header", () => {
    const header = new Uint8Array(12);
    header.set(new TextEncoder().encode("RIFF"), 0);
    header.set(new TextEncoder().encode("WAVE"), 8);

    expect(__test.isWav(header)).toBe(true);
  });

  it("uses the full encoded media key", () => {
    expect(__test.mediaStorageKey("series/1:episode 2")).toBe("series%2F1%3Aepisode%202");
  });

  it("allows native requests and only the configured browser origin", () => {
    expect(__test.isOriginAllowed(null, undefined)).toBe(true);
    expect(__test.isOriginAllowed("https://tv.example.com", "https://tv.example.com")).toBe(true);
    expect(__test.isOriginAllowed("https://other.example.com", "https://tv.example.com")).toBe(false);
  });

  it("uses the Cloudflare client address for rate limiting", () => {
    const request = new Request("https://worker.example.com/v1/health", {
      headers: { "cf-connecting-ip": "203.0.113.10" }
    });

    expect(__test.clientRateLimitKey(request)).toBe("203.0.113.10");
  });

  it("rejects unauthenticated requests before accessing bindings", async () => {
    const response = await worker.fetch(new Request("https://worker.example.com/v1/health"), env());

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ error: "unauthorized" });
  });

  it("returns 429 when the client quota is exhausted", async () => {
    const response = await worker.fetch(new Request("https://worker.example.com/v1/health", {
      headers: { authorization: "Bearer secret" }
    }), env({ AI_SKIP_RATE_LIMITER: { limit: async () => ({ success: false }) } }));

    expect(response.status).toBe(429);
    expect(response.headers.get("retry-after")).toBe("60");
  });

  it("stores uploaded WAV samples in R2", async () => {
    let storedKey = "";
    let storedBytes = 0;
    const wav = new Uint8Array(45);
    wav.set(new TextEncoder().encode("RIFF"), 0);
    wav.set(new TextEncoder().encode("WAVE"), 8);
    const response = await worker.fetch(new Request("https://worker.example.com/v1/uploads/abcdefghijkl", {
      method: "PUT",
      headers: { authorization: "Bearer secret", "content-type": "audio/wav" },
      body: wav
    }), env({
      AI_SKIP_R2: {
        put: async (key: string, value: Uint8Array) => {
          storedKey = key;
          storedBytes = value.byteLength;
        }
      }
    }));

    expect(response.status).toBe(201);
    expect(storedKey).toBe("samples/abcdefghijkl.wav");
    expect(storedBytes).toBe(wav.byteLength);
  });

  it("returns a stored job status", async () => {
    const job = {
      jobId: "12345678-1234-1234-1234-123456789abc",
      mediaKey: "episode-key",
      seriesKey: "series-key",
      episode: "1",
      durationMs: 600_000,
      samples: [],
      status: "completed",
      openingMs: 75_000,
      endingMs: 90_000,
      openingConfidence: 0.92,
      endingConfidence: 0.88,
      createdAt: 1,
      updatedAt: 2
    };
    const response = await worker.fetch(new Request(`https://worker.example.com/v1/jobs/${job.jobId}`, {
      headers: { authorization: "Bearer secret" }
    }), env({ AI_SKIP_KV: { get: async () => job } }));

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      status: "completed",
      openingMs: 75_000,
      endingMs: 90_000
    });
  });

  it("returns a reusable series result with its source duration", async () => {
    const job = {
      jobId: "12345678-1234-1234-1234-123456789abc",
      mediaKey: "episode-key",
      seriesKey: "series-key",
      episode: "1",
      durationMs: 600_000,
      samples: [],
      status: "completed",
      openingMs: 75_000,
      endingMs: 90_000,
      openingConfidence: 0.92,
      endingConfidence: 0.88,
      createdAt: 1,
      updatedAt: 2
    };
    const response = await worker.fetch(new Request("https://worker.example.com/v1/jobs/series/series-key", {
      headers: { authorization: "Bearer secret" }
    }), env({ AI_SKIP_KV: { get: async (key: string) => key === "series:series-key" ? job : null } }));

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({ durationMs: 600_000, openingMs: 75_000 });
  });

  it("rejects malformed Gemini JSON", () => {
    expect(() => __test.parseGeminiText("not-json")).toThrow();
  });

  it("parses JSON wrapped in a Markdown code fence", () => {
    expect(__test.parseGeminiText("```json\n{\"openingMs\":1000,\"endingMs\":2000,\"openingConfidence\":0.9,\"endingConfidence\":0.8}\n```")).toEqual({
      openingMs: 1000,
      endingMs: 2000,
      openingConfidence: 0.9,
      endingConfidence: 0.8
    });
  });

  it("retries a queue message when its KV job is not visible yet", async () => {
    let retryDelay = 0;
    let acknowledged = false;
    const message = {
      body: { jobId: "12345678-1234-1234-1234-123456789abc" },
      retry: ({ delaySeconds }: { delaySeconds: number }) => { retryDelay = delaySeconds; },
      ack: () => { acknowledged = true; }
    };

    await worker.queue({ messages: [message] } as any, env());

    expect(retryDelay).toBe(30);
    expect(acknowledged).toBe(false);
  });
});
