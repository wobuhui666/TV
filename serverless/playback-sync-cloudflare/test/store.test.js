import test from 'node:test';
import assert from 'node:assert/strict';
import {PlaybackStore} from '../src/store.js';

class Sql {
  constructor() { this.events=[]; this.records=new Map(); this.cursor=0; this.deleted=[]; }
  exec(query, ...args) {
    query=query.replace(/\s+/g,' ').trim();
    if (query.startsWith('CREATE') || query.startsWith('BEGIN') || query.startsWith('COMMIT') || query.startsWith('ROLLBACK')) return [];
    if (query.startsWith('DELETE FROM records')) { const cutoff=args[0]; for (const [key,value] of this.records) if (value.deleted_at>0&&value.deleted_at<cutoff) this.records.delete(key); return []; }
    if (query.startsWith('DELETE FROM events')) { const cutoff=args[1]; this.events=this.events.filter((value)=>{const payload=JSON.parse(value.payload);return payload.event!==args[0]||payload.updatedAt>=cutoff;}); return []; }
    if (query.startsWith('SELECT 1 FROM events')) return this.events.some((e)=>e.event_id===args[0]) ? [{1:1}] : [];
    if (query.startsWith('SELECT updated_at')) { const r=this.records.get(args.slice(0,3).join('|')); return r?[r]:[]; }
    if (query.startsWith('INSERT INTO records')) { this.records.set(args.slice(0,3).join('|'),{updated_at:args[3],deleted_at:args[4],payload:args[5]}); return []; }
    if (query.startsWith('SELECT site_key, vod_id')) return [...this.records.entries()].filter(([key])=>{const [config,site]=key.split('|');return config===args[0]&&(args.length===1||site===args[1]);}).map(([key,value])=>{const [,site_key,vod_id]=key.split('|');return {site_key,vod_id,...value};});
    if (query.startsWith('UPDATE records')) { const key=args.slice(3,6).join('|'); this.records.set(key,{updated_at:args[0],deleted_at:args[1],payload:args[2]}); return []; }
    if (query.startsWith('INSERT INTO events')) { this.events.push({cursor:++this.cursor,event_id:args[0],config_key:args[1],payload:args[7]}); return []; }
    if (query.startsWith('SELECT cursor, payload')) return this.events.filter((e)=>e.config_key===args[0]&&e.cursor>args[1]).slice(0,args[2]);
    if (query.startsWith('SELECT config_key, COUNT')) return [...new Set(this.events.map((x)=>x.config_key))].map((config_key)=>{const e=this.events.filter((x)=>x.config_key===config_key);return {config_key,count:e.length,cursor:e.at(-1)?.cursor||0};});
    if (query.startsWith('SELECT COUNT')) { const e=this.events.filter((x)=>x.config_key===args[0]); return [{count:e.length,cursor:e.at(-1)?.cursor||0}]; }
    throw new Error(query);
  }
}

const event=(id,time=10)=>({schema:'webhtv.playback.v1',event:'playback.progress',eventId:id,siteKey:'s',vodId:'v',updatedAt:time});

test('eventId is idempotent and cursor remains monotonic',()=>{ const store=new PlaybackStore(new Sql()); store.push('a',[event('1')]); store.push('a',[event('1')]); assert.equal(store.status('a').count,1); store.push('a',[event('2',11)]); assert.equal(store.pull('a',0,1).hasMore,true); assert.equal(store.status('a').cursor,2); });
test('config keys are isolated',()=>{ const store=new PlaybackStore(new Sql()); store.push('a',[event('1')]); store.push('b',[event('2')]); assert.equal(store.pull('a',0,100).items.length,1); assert.equal(store.pull('b',0,100).items.length,1); });
test('validates whole batch before transaction',()=>{ const store=new PlaybackStore(new Sql()); assert.throws(()=>store.push('a',[event('1'),{schema:'bad'}])); assert.equal(store.status('a').count,0); });
test('all-scope delete requires confirmation',()=>{ const store=new PlaybackStore(new Sql()); assert.throws(()=>store.push('a',[{...event('1'),event:'playback.deleted',scope:'all'}])); });
test('all-scope delete needs no item identity and atomically tombstones records',()=>{ const sql=new Sql(); const store=new PlaybackStore(sql); store.push('a',[event('1',10)]); store.push('a',[{schema:'webhtv.playback.v1',event:'playback.deleted',eventId:'2',scope:'all',confirm:true,updatedAt:20}]); assert.equal(sql.records.get('a|s|v').deleted_at,20); });
test('site-scope delete only affects matching site',()=>{ const sql=new Sql(); const store=new PlaybackStore(sql); store.push('a',[event('1',10),{...event('2',10),siteKey:'other'}]); store.push('a',[{schema:'webhtv.playback.v1',event:'playback.deleted',eventId:'3',scope:'site',siteKey:'s',updatedAt:20}]); assert.equal(sql.records.get('a|s|v').deleted_at,20); assert.equal(sql.records.get('a|other|v').deleted_at,0); });
test('batch and pull limits are enforced',()=>{ const store=new PlaybackStore(new Sql()); assert.throws(()=>store.push('a',Array.from({length:101},(_,i)=>event(String(i))))); store.push('a',[event('1')]); assert.equal(store.pull('a',0,1000).items.length,1); });
test('status can summarize isolated config keys',()=>{ const store=new PlaybackStore(new Sql()); store.push('a',[event('1')]); store.push('b',[event('2')]); assert.deepEqual(store.statusAll().configs.map((item)=>item.configKey).sort(),['a','b']); });
test('expired tombstones are pruned after 90 days',()=>{ const sql=new Sql(); const store=new PlaybackStore(sql); store.push('a',[{...event('1',10),event:'playback.deleted'}]); store.prune(10+91*86_400_000); assert.equal(sql.records.size,0); assert.equal(store.status('a').count,0); });
