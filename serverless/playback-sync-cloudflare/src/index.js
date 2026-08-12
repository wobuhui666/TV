import {HttpError, PlaybackStore} from './store.js';

const MAX_BODY = 128 * 1024;
const cors = {'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, X-WebHTV-Token, X-WebHTV-Config-Key, X-WebHTV-Since, X-WebHTV-Limit', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS'};

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, {status: 204, headers: cors});
    const token = request.headers.get('X-WebHTV-Token') || '';
    if (!token) return json({error: 'token required'}, 401);
    const hash = await sha256(token);
    const id = env.PLAYBACK_DO.idFromName(hash);
    return env.PLAYBACK_DO.get(id).fetch(request);
  }
};

export class PlaybackSyncObject {
  constructor(state) { this.store = new PlaybackStore(state.storage.sql); }

  async fetch(request) {
    try {
      const url = new URL(request.url);
      if (!['/api/playback/sync', '/playback/sync'].includes(url.pathname)) throw new HttpError(404, 'not found');
      this.store.prune();
      if (request.method === 'GET' && url.searchParams.get('status') === '1' && url.searchParams.get('allConfigs') === '1') {
        return json(this.store.statusAll());
      }
      const configKey = request.headers.get('X-WebHTV-Config-Key') || url.searchParams.get('configKey') || '';
      if (!configKey) throw new HttpError(400, 'config key required');
      if (request.method === 'POST') {
        const length = Number(request.headers.get('content-length') || 0);
        if (length > MAX_BODY) throw new HttpError(413, 'body too large');
        const text = await request.text();
        if (new TextEncoder().encode(text).length > MAX_BODY) throw new HttpError(413, 'body too large');
        const body = JSON.parse(text);
        const items = Array.isArray(body) ? body : body.items || [body];
        return json({ok: true, accepted: this.store.push(configKey, items)});
      }
      if (request.method === 'GET') {
        if (url.searchParams.get('status') === '1') return json(this.store.status(configKey));
        const since = Math.max(0, Number(request.headers.get('X-WebHTV-Since') || url.searchParams.get('since') || 0));
        const limit = Math.min(1000, Math.max(1, Number(request.headers.get('X-WebHTV-Limit') || url.searchParams.get('limit') || 100)));
        return json(this.store.pull(configKey, since, limit));
      }
      throw new HttpError(405, 'method not allowed');
    } catch (error) {
      return json({error: error.message || 'internal error'}, error instanceof HttpError ? error.status : 500);
    }
  }
}

function json(body, status = 200) { return new Response(JSON.stringify(body), {status, headers: {...cors, 'Content-Type': 'application/json; charset=utf-8'}}); }
async function sha256(value) { const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)); return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join(''); }
