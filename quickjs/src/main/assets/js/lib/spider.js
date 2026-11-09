import * as entry from %s;
import {Spider} from 'catvod';

if (!globalThis.__JS_SPIDER__) {
    if (entry.__jsEvalReturn) {
        globalThis.req = http;
        globalThis.__JS_SPIDER__ = entry.__jsEvalReturn();
    } else if (entry.default) {
        const modern = typeof entry.default === 'function' && entry.default.prototype instanceof Spider;
        globalThis.__SPIDER_STANDARD__ = modern;
        globalThis.__JS_SPIDER__ = modern ? adaptStandard(new entry.default())
            : typeof entry.default === 'function' ? entry.default(globalThis.__SPIDER_SITE__) : entry.default;
    }
}
const api = globalThis.__JS_SPIDER__;
if (!api || typeof api !== 'object') throw new TypeError('JS spider must return an object');
const wrapped = Object.create(api);
for (const name of ['init','home','homeVod','category','detail','search','play','live','sniffer','isVideo','proxy','action','destroy','danmaku']) {
    if (typeof api[name] === 'function') Object.defineProperty(wrapped, name, {value: api[name].bind(api), configurable: true, writable: true});
}
bindDanmaku(wrapped);
globalThis.__JS_SPIDER__ = wrapped;

function adaptStandard(source) {
    const wrapped = {};
    const methods = {home: 'homeContent', homeVod: 'homeVideoContent', category: 'categoryContent',
        detail: 'detailContent', search: 'searchContent', play: 'playerContent', live: 'liveContent',
        sniffer: 'manualVideoCheck', isVideo: 'isVideoFormat', init: 'init', proxy: 'proxy', action: 'action', destroy: 'destroy'};
    for (const [name, standard] of Object.entries(methods)) {
        const serialize = ['home', 'homeVod', 'category', 'detail', 'search', 'play', 'live', 'action'].includes(name);
        wrapped[name] = async (...args) => {
            const value = await source[standard](...args);
            if (!serialize) return value;
            if (name === 'live' && typeof value === 'string') return value;
            if (!value || typeof value !== 'object') throw new TypeError('Spider data must be an object or array');
            return JSON.stringify(value, (key, item) => {
                if (item && typeof item.then === 'function') throw new TypeError('Await asynchronous values before returning JSON');
                return item;
            });
        };
    }
    wrapped.danmaku = (...args) => source.danmaku(...args);
    wrapped.wasm = source.wasm;
    return wrapped;
}

function bindDanmaku(source) {
    const active = new Map();
    let destroyed = false;
    source.startDanmaku = async function(id, token) {
        if (destroyed) throw new Error('Spider is closed');
        if (typeof source.danmaku !== 'function') throw new Error('Spider does not support live danmaku');
        const state = {closed: false, stop: null};
        active.set(token, state);
        try {
            const stop = await source.danmaku(id, item => {
                if (state.closed || destroyed) return;
                if (!item || typeof item !== 'object' || Array.isArray(item)) throw new TypeError('Danmaku must be an object');
                globalThis.__SPIDER_DANMAKU_EMIT__(token, JSON.stringify(item));
            });
            if (typeof stop !== 'function') throw new TypeError('danmaku must return a stop function');
            if (state.closed || destroyed) await stop();
            else state.stop = stop;
        } catch (error) {
            state.closed = true;
            active.delete(token);
            throw error;
        }
    };
    source.stopDanmaku = async function(token) {
        const state = active.get(token);
        if (!state) return;
        state.closed = true;
        active.delete(token);
        if (state.stop) await state.stop();
    };
    const originalDestroy = typeof source.destroy === 'function' ? source.destroy.bind(source) : () => {};
    source.destroy = async function(...args) {
        if (destroyed) return;
        destroyed = true;
        let failure;
        for (const token of [...active.keys()]) {
            try { await source.stopDanmaku(token); } catch (error) { failure ??= error; }
        }
        try { await originalDestroy(...args); } catch (error) { failure ??= error; }
        if (failure) throw failure;
    };
}
