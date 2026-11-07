(() => {
    const native = globalThis.__SPIDER_NET_NATIVE__;
    const legacyLocal = globalThis.local;
    const pending = new Map();
    const loaders = new Map();
    let nextId = 0;
    let closed = false;

    function unwrap(text) {
        const result = JSON.parse(text);
        if (Object.prototype.hasOwnProperty.call(result, 'error')) throw new Error(result.error);
        return result.value;
    }

    function invoke(method, owner, params = {}) {
        if (closed) throw new Error('Network bridge closed');
        return unwrap(native.call(method, owner, JSON.stringify(params)));
    }

    function encode(value) {
        const json = JSON.stringify(value);
        if (json === undefined) throw new TypeError('Value must be JSON');
        return json;
    }

    function parsed(value) {
        return value == null ? null : JSON.parse(value);
    }

    function begin(owner, operation, params, parseResult = true, loader) {
        const id = ++nextId;
        if (loader) loaders.set(id, {owner, loader});
        return new Promise((resolve, reject) => {
            pending.set(id, {resolve, reject, parseResult});
            try {
                invoke('start', owner, Object.assign({}, params, {id, operation}));
            } catch (error) {
                pending.delete(id);
                loaders.delete(id);
                reject(error);
            }
        });
    }

    globalThis.__SPIDER_NET_DELIVER__ = (kind, id, value, bytes) => {
        if (kind === 'close') {
            closed = true;
            const error = new Error('Network bridge closed');
            pending.forEach(item => item.reject(error));
            pending.clear();
            loaders.clear();
            return;
        }
        if (kind === 'releaseLoader') {
            loaders.delete(id);
            return;
        }
        if (kind === 'load') {
            const item = loaders.get(id);
            if (!item) return;
            loaders.delete(id);
            // Let the current native call finish before executing arbitrary spider code.
            Promise.resolve().then(item.loader).then(result => {
                invoke('loaded', item.owner, {id, value: encode(result)});
            }).catch(error => {
                if (!closed) {
                    try { invoke('loaded', item.owner, {id, error: String(error && error.message || error)}); } catch (_) {}
                }
            });
            return;
        }
        if (kind === 'refreshed') {
            // Background refresh has no caller waiting; preserve the previous cache value on error.
            return;
        }
        const item = pending.get(id);
        if (!item) return;
        pending.delete(id);
        try {
            const result = unwrap(value);
            const response = item.parseResult ? parsed(result) : result;
            if (kind === 'binary') {
                if (!(bytes instanceof ArrayBuffer)) throw new TypeError('HTTP binary response requires an ArrayBuffer');
                response.content = bytes;
            }
            item.resolve(response);
        } catch (error) {
            item.reject(error);
        }
    };

    // JSON cannot represent BufferSource bodies; normalize only the proxy body slot.
    globalThis.__SPIDER_NET_PROXY_JSON__ = value => {
        const body = value[2];
        if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
            const converted = Array.from(value);
            const bytes = body instanceof ArrayBuffer ? new Uint8Array(body)
                : new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
            converted[2] = Array.from(bytes);
            return JSON.stringify(converted);
        }
        return JSON.stringify(value);
    };

    function createNet(owner) {
        let ownerClosed = false;
        function check() {
            if (ownerClosed) throw new Error('Network session closed');
        }
        function sync(method, params) {
            check();
            return invoke(method, owner, params);
        }
        function async(operation, params, parseResult = true, loader) {
            try { check(); } catch (error) { return Promise.reject(error); }
            return begin(owner, operation, params, parseResult, loader);
        }
        const net = {
            req(url, options = {}) {
                if (options && options.async === true) return net.http(url, options);
                if (options && options.buffer === 1) {
                    check();
                    if (closed) throw new Error('Network bridge closed');
                    const result = native.reqBinary(owner, JSON.stringify({url, options}));
                    return typeof result === 'string' ? unwrap(result) : result;
                }
                return parsed(sync('req', {url, options}));
            },
            http(url, options = {}) { return async('http', {url, options}); },
            json(url, options = {}) { return async('json', {url, options}); },
            ws(url, options = {}) { return async('ws', {url, options}); },
            batch(requests, limit = 4) { return async('batch', {requests, limit}); },
            download(url, path, options = {}) { return async('download', {url, path, options}, false); },
            sleep(ms) { return async('sleep', {ms}, false); },
            session() { return createNet(sync('session')); },
            getCookie(url) { return sync('cookieGet', {url}); },
            setCookie(url, value) { return sync('cookieSet', {url, value}); },
            getUserAgent() { return sync('userAgent'); },
            cached(key, options, loader) {
                if (typeof loader !== 'function') return Promise.reject(new TypeError('Cache loader is required'));
                return async('cached', {key, options}, true, loader);
            },
            peek(key) { return parsed(sync('peek', {key})); },
            refresh(key, options, loader) {
                check();
                if (typeof loader !== 'function') throw new TypeError('Cache loader is required');
                const id = ++nextId;
                loaders.set(id, {owner, loader});
                try { sync('start', {id, operation: 'refresh', key, options}); }
                catch (error) { loaders.delete(id); throw error; }
            },
            clearCache(key) { sync('clearCache', key === undefined ? {} : {key}); },
            close() {
                if (ownerClosed) return;
                sync('close');
                ownerClosed = true;
            }
        };
        return net;
    }

    globalThis.net = createNet(0);
    globalThis.local = {
        get(key) {
            if (arguments.length >= 2) return legacyLocal.get.apply(legacyLocal, arguments);
            return parsed(invoke('localGet', 0, {key}));
        },
        set(key, value) {
            if (arguments.length >= 3) return legacyLocal.set.apply(legacyLocal, arguments);
            invoke('localSet', 0, {key, value: encode(value)});
        },
        delete(key) {
            if (arguments.length >= 2) return legacyLocal.delete.apply(legacyLocal, arguments);
            invoke('localDelete', 0, {key});
        },
        clear() { invoke('localClear', 0); }
    };
    globalThis.page = (items, pg, limit) => {
        const current = Number(pg);
        const size = Number(limit);
        if (!Array.isArray(items) || !Number.isSafeInteger(current) || current < 1 || !Number.isSafeInteger(size) || size < 1) {
            throw new TypeError('Page requires an array and positive integer page/limit');
        }
        const start = Math.min(items.length, (current - 1) * size);
        return {list: items.slice(start, start + size), page: current,
            pagecount: Math.max(1, Math.ceil(items.length / size)), limit: size, total: items.length};
    };
    globalThis.link = (name, target) => {
        if (!target || typeof target !== 'object' || Array.isArray(target)) throw new TypeError('Link requires a target object');
        return '[a=cr:' + encode(target) + '/]' + String(name) + '[/a]';
    };
    globalThis.getProxyUrl = (params = {}) => {
        if (!params || typeof params !== 'object' || Array.isArray(params)) throw new TypeError('Proxy parameters must be an object');
        return invoke('proxy', 0, params);
    };
})();
