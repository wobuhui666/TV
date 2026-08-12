const DAY_MS = 86_400_000;

export class PlaybackStore {
  constructor(sql) {
    this.sql = sql;
    sql.exec(`CREATE TABLE IF NOT EXISTS events (
      cursor INTEGER PRIMARY KEY AUTOINCREMENT,
      event_id TEXT NOT NULL UNIQUE,
      config_key TEXT NOT NULL,
      site_key TEXT NOT NULL,
      vod_id TEXT NOT NULL,
      event TEXT NOT NULL,
      scope TEXT NOT NULL,
      updated_at INTEGER NOT NULL,
      payload TEXT NOT NULL
    )`);
    sql.exec('CREATE INDEX IF NOT EXISTS events_config_cursor ON events(config_key, cursor)');
    sql.exec(`CREATE TABLE IF NOT EXISTS records (
      config_key TEXT NOT NULL,
      site_key TEXT NOT NULL,
      vod_id TEXT NOT NULL,
      updated_at INTEGER NOT NULL,
      deleted_at INTEGER NOT NULL,
      payload TEXT NOT NULL,
      PRIMARY KEY(config_key, site_key, vod_id)
    )`);
  }

  push(configKey, incoming, now = Date.now()) {
    if (!Array.isArray(incoming) || incoming.length > 100) throw new HttpError(400, 'batch must contain 1-100 events');
    const normalized = incoming.map((item) => validateEvent(configKey, item, now));
    this.sql.exec('BEGIN IMMEDIATE');
    try {
      for (const item of normalized) this.apply(item);
      this.sql.exec('COMMIT');
    } catch (error) {
      this.sql.exec('ROLLBACK');
      throw error;
    }
    return normalized.length;
  }

  apply(item) {
    const duplicate = [...this.sql.exec('SELECT 1 FROM events WHERE event_id = ? LIMIT 1', item.eventId)].length > 0;
    if (duplicate) return;
    if (item.event === 'playback.deleted' && item.scope !== 'item') this.applyScopedDelete(item);
    else this.applyRecord(item);
    this.sql.exec('INSERT INTO events(event_id, config_key, site_key, vod_id, event, scope, updated_at, payload) VALUES(?, ?, ?, ?, ?, ?, ?, ?)',
      item.eventId, item.configKey, item.siteKey, item.vodId, item.event, item.scope, item.updatedAt, JSON.stringify(item));
  }

  applyRecord(item) {
    const existing = [...this.sql.exec('SELECT updated_at, deleted_at FROM records WHERE config_key = ? AND site_key = ? AND vod_id = ?', item.configKey, item.siteKey, item.vodId)][0];
    const currentTime = existing ? Math.max(Number(existing.updated_at), Number(existing.deleted_at)) : -1;
    if (item.updatedAt <= currentTime) return;
    const deletedAt = item.event === 'playback.deleted' ? item.updatedAt : 0;
    this.sql.exec(`INSERT INTO records(config_key, site_key, vod_id, updated_at, deleted_at, payload)
      VALUES(?, ?, ?, ?, ?, ?)
      ON CONFLICT(config_key, site_key, vod_id) DO UPDATE SET updated_at=excluded.updated_at, deleted_at=excluded.deleted_at, payload=excluded.payload`,
      item.configKey, item.siteKey, item.vodId, item.updatedAt, deletedAt, JSON.stringify(item));
  }

  applyScopedDelete(item) {
    const rows = item.scope === 'all'
      ? [...this.sql.exec('SELECT site_key, vod_id, updated_at, deleted_at FROM records WHERE config_key = ?', item.configKey)]
      : [...this.sql.exec('SELECT site_key, vod_id, updated_at, deleted_at FROM records WHERE config_key = ? AND site_key = ?', item.configKey, item.siteKey)];
    for (const row of rows) {
      if (item.updatedAt <= Math.max(Number(row.updated_at), Number(row.deleted_at))) continue;
      this.sql.exec('UPDATE records SET updated_at = ?, deleted_at = ?, payload = ? WHERE config_key = ? AND site_key = ? AND vod_id = ?',
        item.updatedAt, item.updatedAt, JSON.stringify({...item, siteKey: row.site_key, vodId: row.vod_id}), item.configKey, row.site_key, row.vod_id);
    }
  }

  pull(configKey, since, limit) {
    const rows = [...this.sql.exec('SELECT cursor, payload FROM events WHERE config_key = ? AND cursor > ? ORDER BY cursor LIMIT ?', configKey, since, limit + 1)];
    const hasMore = rows.length > limit;
    const page = rows.slice(0, limit);
    return {schema: 'webhtv.playback.v1', items: page.map((row) => JSON.parse(row.payload)), cursor: page.length ? Number(page.at(-1).cursor) : since, hasMore};
  }

  status(configKey) {
    const row = [...this.sql.exec('SELECT COUNT(*) AS count, COALESCE(MAX(cursor), 0) AS cursor FROM events WHERE config_key = ?', configKey)][0];
    return {schema: 'webhtv.playback.v1', count: Number(row?.count ?? 0), cursor: Number(row?.cursor ?? 0)};
  }

  statusAll() {
    const rows = [...this.sql.exec('SELECT config_key, COUNT(*) AS count, COALESCE(MAX(cursor), 0) AS cursor FROM events GROUP BY config_key')];
    return {schema: 'webhtv.playback.v1', configs: rows.map((row) => ({configKey: row.config_key, count: Number(row.count), cursor: Number(row.cursor)}))};
  }

  prune(now = Date.now()) {
    this.sql.exec('DELETE FROM records WHERE deleted_at > 0 AND deleted_at < ?', now - 90 * DAY_MS);
    this.sql.exec('DELETE FROM events WHERE event = ? AND updated_at < ?', 'playback.deleted', now - 90 * DAY_MS);
  }
}

export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function validateEvent(configKey, item, now) {
  if (!item || item.schema !== 'webhtv.playback.v1') throw new HttpError(400, 'invalid schema');
  const event = item.event || 'playback.progress';
  if (!['playback.progress', 'playback.ended', 'playback.deleted'].includes(event)) throw new HttpError(400, 'invalid event');
  const scope = item.scope || 'item';
  if (event === 'playback.deleted' && scope === 'all' && item.confirm !== true) throw new HttpError(400, 'scope=all requires confirm=true');
  if (!item.eventId) throw new HttpError(400, 'eventId is required');
  if (scope === 'site' && !item.siteKey) throw new HttpError(400, 'siteKey is required for site scope');
  if (scope === 'item' && (!item.siteKey || !item.vodId)) throw new HttpError(400, 'siteKey and vodId are required for item scope');
  const updatedAt = Number(item.updatedAt || item.deletedAt || item.timestamp || now);
  if (!Number.isSafeInteger(updatedAt) || updatedAt <= 0) throw new HttpError(400, 'invalid updatedAt');
  return {...item, schema: 'webhtv.playback.v1', configKey, event, scope, siteKey: item.siteKey || '', vodId: item.vodId || '', updatedAt};
}
