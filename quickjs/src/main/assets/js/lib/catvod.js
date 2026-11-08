// A stable module facade leaves the legacy global functions and source factory contracts intact.
export const net = globalThis.net;
export const local = {
    get(key, fallback = null) {
        const value = globalThis.__SPIDER_LOCAL_GET__ ? globalThis.__SPIDER_LOCAL_GET__(key) : globalThis.local.get(key);
        return value === null ? fallback : value;
    },
    set(key, value) { return globalThis.local.set(key, value); },
    delete(key) { return globalThis.local.delete(key); },
    clear() { return globalThis.local.clear(); }
};
export const js = {eval: (source, options = {}) => globalThis.__SPIDER_JS_EVAL__(source, options)};
export function dialog(options, callback = null) {
    if (!options || typeof options !== 'object' || Array.isArray(options)) return Promise.reject(new TypeError('Dialog options must be an object'));
    if (callback != null && typeof callback !== 'function') return Promise.reject(new TypeError('Dialog callback must be a function'));
    return globalThis.dialog(callback == null ? options : Object.assign({}, options, {onAction: callback}));
}
export const wasm = globalThis.wasm;
export const proxyUrl = (params = {}, local = true) => globalThis.proxyUrl(params, local);
export const danmakuUrl = id => globalThis.danmakuUrl(id);
export const link = (...args) => globalThis.link(...args);
export function page(items, pg, limit, total) {
    pg = Math.max(1, Number(pg));
    if (!Array.isArray(items) || !Number.isSafeInteger(pg) || !Number.isSafeInteger(limit) || limit < 1) throw new RangeError('Invalid page options');
    if (total == null) {total = items.length; items = items.slice((pg - 1) * limit, pg * limit);}
    if (!Number.isSafeInteger(total) || total < 0) throw new RangeError('Invalid page total');
    return {list: items, page: pg, pagecount: Math.max(1, Math.ceil(total / limit)), limit, total};
}
export const s2t = text => globalThis.s2t(text);
export const t2s = text => globalThis.t2s(text);
export const joinUrl = (parent, child) => globalThis.joinUrl(parent, child);
export const md5X = text => globalThis.md5X(text);
export const sha256X = text => globalThis.sha256X(text);
export const randomX = size => globalThis.randomX(size);
export const aesX = (...args) => globalThis.aesX(...args);
export const desX = (...args) => globalThis.desX(...args);
export const rsaX = (...args) => globalThis.rsaX(...args);
export const openWeb = options => globalThis.__SPIDER_OPEN_WEB__(options);
export const htmlQuery = (html, plan) => globalThis.__SPIDER_HTML_QUERY__(html, plan);

function bindMethods(spider) {
    const names = new Set(['constructor', ...Object.getOwnPropertyNames(spider)]);
    for (let prototype = Object.getPrototypeOf(spider); prototype && prototype !== Object.prototype; prototype = Object.getPrototypeOf(prototype)) {
        for (const name of Object.getOwnPropertyNames(prototype)) {
            if (names.has(name)) continue;
            names.add(name);
            const method = Object.getOwnPropertyDescriptor(prototype, name).value;
            if (typeof method === 'function') Object.defineProperty(spider, name, {value: method.bind(spider), writable: true, configurable: true});
        }
    }
}
export class Spider {
    constructor() {
        this.siteKey = globalThis.__SPIDER_SITE__.key;
        this.net = net; this.local = local; this.js = js; this.dialog = dialog; this.wasm = wasm;
        bindMethods(this);
    }
    init(extend) {}
    homeContent(filter) {return {class: []};}
    homeVideoContent() {return {list: []};}
    categoryContent(tid, pg, filter, extend) {return {list: []};}
    detailContent(ids) {return {list: []};}
    searchContent(key, quick, pg = '1') {return {list: []};}
    playerContent(flag, id, vipFlags) {return {};}
    liveContent(url) {return '';}
    manualVideoCheck() {return false;}
    isVideoFormat(url) {return false;}
    proxy(params) {return [404, 'text/plain', ''];}
    action(action) {return {};}
    danmaku(id, emit) {throw new Error('Spider does not support live danmaku');}
    destroy() {}
    proxyUrl(params = {}, local = true) {return proxyUrl(params, local);}
    danmakuUrl(id) {return danmakuUrl(id);}
    link(name, target) {return link(name, target);}
    page(items, pg, limit, total) {return page(items, pg, limit, total);}
    log(message) {console.log(message);}
}
