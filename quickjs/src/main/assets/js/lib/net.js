(() => {
    const native = globalThis.__SPIDER_NET_NATIVE__;
    const legacyLocal = globalThis.local;
    const pending = new Map();
    const loaders = new Map();
    const bodies = new Map();
    const sockets = new Map();
    const closedOwners = new Set();
    const ownerParents = new Map();
    function ownerIsClosed(owner) { return closedOwners.has(owner); }
    function markOwnerClosed(owner) {
        closedOwners.add(owner);
        for (const [child, parent] of ownerParents) if (parent === owner) markOwnerClosed(child);
        for (const [token, item] of sockets) if (ownerIsClosed(item.owner)) {
            item.closed = item.ended = true;
            sockets.delete(token);
        }
    }
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
            for (const body of bodies.values()) Promise.resolve().then(body.close).catch(() => {});
            bodies.clear();
            sockets.forEach(item => { item.closed = true; });
            sockets.clear();
            return;
        }
        if (kind === 'ownerClosed') {
            markOwnerClosed(id);
            return;
        }
        if (kind === 'pull') {
            const request = unwrap(value);
            const body = bodies.get(request.token);
            Promise.resolve().then(() => body ? body.pull(request.size) : null).then(chunk => {
                if (!closed) native.chunk(id, chunk, null);
            }, error => { if (!closed) native.chunk(id, null, String(error && error.message || error)); });
            return;
        }
        if (kind === 'releaseStream') {
            const body = bodies.get(id);
            bodies.delete(id);
            if (body) Promise.resolve().then(body.close).catch(error => console.error(error));
            return;
        }
        if (kind === 'socket') {
            const item = sockets.get(id);
            if (!item || item.ended) return;
            const event = unwrap(value);
            if (event.binary) {
                if (!(bytes instanceof ArrayBuffer)) throw new TypeError('Socket bytes require an ArrayBuffer');
                event.content = new Uint8Array(bytes);
            }
            if (event.type === 'close') {
                item.closed = item.ended = true;
                sockets.delete(id);
            }
            Promise.resolve().then(() => {
                if (closed || ownerIsClosed(item.owner) || event.type !== 'close' && (item.closed || item.ended)) return;
                return item.callback(item.socket, event);
            }).catch(error => {
                item.socket.cancel();
                console.error(error && error.message || error);
            });
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
                if (!closed) invoke('loaded', item.owner, {id, value: encode(result)});
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
            if (kind === 'streamBytes') {
                if (!(bytes instanceof ArrayBuffer)) throw new TypeError('Stream bytes require an ArrayBuffer');
                item.resolve(bytes);
                return;
            }
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

    function map(items, mapper, limit, active) {
        if (!Array.isArray(items) || typeof mapper !== 'function' || !Number.isSafeInteger(limit) || limit < 1) return Promise.reject(new TypeError('Invalid map options'));
        try { active(); } catch (error) { return Promise.reject(error); }
        const values = new Array(items.length);
        let index = 0, failed = false;
        async function worker() {
            while (!failed && index < items.length) {
                try {
                    active();
                    const current = index++;
                    const value = await mapper(items[current], current);
                    active();
                    values[current] = value;
                } catch (error) { failed = true; throw error; }
            }
        }
        return Promise.all(Array.from({length: Math.min(limit, items.length)}, worker)).then(() => values);
    }

    function streamHandle(token, owner) {
        return Object.freeze({$tvStream: token, close: () => invoke('streamClose', owner, {token})});
    }

    function streamBody(source, owner, options) {
        if (options && Object.keys(options).length) throw new TypeError('Generator streams do not accept options');
        if (!source || typeof source[Symbol.asyncIterator] !== 'function' && typeof source[Symbol.iterator] !== 'function') throw new TypeError('Stream body requires an iterable');
        const iterator = typeof source[Symbol.asyncIterator] === 'function' ? source[Symbol.asyncIterator]() : source[Symbol.iterator]();
        let chunk = null, offset = 0, bodyClosed = false;
        const body = {
            async pull(size) {
                while (!bodyClosed && (!chunk || offset === chunk.byteLength)) {
                    const next = await iterator.next();
                    if (bodyClosed || next.done) return null;
                    if (next.value instanceof ArrayBuffer) chunk = new Uint8Array(next.value);
                    else if (ArrayBuffer.isView(next.value)) chunk = new Uint8Array(next.value.buffer, next.value.byteOffset, next.value.byteLength);
                    else throw new TypeError('Stream body must yield bytes');
                    offset = 0;
                }
                if (bodyClosed) return null;
                const end = Math.min(offset + size, chunk.byteLength);
                const value = chunk.buffer.slice(chunk.byteOffset + offset, chunk.byteOffset + end);
                offset = end;
                return value;
            },
            async close() {
                if (bodyClosed) return;
                bodyClosed = true;
                chunk = null;
                if (typeof iterator.return === 'function') await iterator.return();
            }
        };
        const token = invoke('streamBody', owner);
        bodies.set(token, body);
        return {code: 200, headers: {}, contentType: 'application/octet-stream', stream: streamHandle(token, owner)};
    }

    function createNet(owner) {
        let ownerClosed = false;
        function check() {
            if (closed) throw new Error('Network bridge closed');
            if (ownerClosed || ownerIsClosed(owner)) throw new Error('Network session closed');
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
            map(items, mapper, limit = 4) { return map(items, mapper, limit, check); },
            async openStream(url, options = {}) {
                check();
                if (typeof url !== 'string' || options == null || typeof options !== 'object' || Array.isArray(options)) throw new TypeError('Invalid stream options');
                const reply = await async('openStream', {url, options});
                let pendingRead = false, readerClosed = false, eof = false;
                return Object.freeze({
                    code: reply.code, headers: reply.headers, contentType: reply.contentType,
                    async read(size = 65536) {
                        if (!Number.isInteger(size) || size < 1 || size > 1048576) throw new RangeError('Chunk size must be 1..1048576 bytes');
                        if (eof) return null;
                        if (readerClosed) throw new Error('Stream reader closed');
                        if (pendingRead) throw new Error('Stream read already pending');
                        pendingRead = true;
                        try {
                            const chunk = await async('streamRead', {token: reply.token, size}, false);
                            if (readerClosed) throw new Error('Stream reader closed');
                            if (chunk === null) eof = true;
                            return chunk;
                        } catch (error) {
                            readerClosed = true;
                            try { sync('streamClose', {token: reply.token}); } catch (_) {}
                            throw error;
                        } finally { pendingRead = false; }
                    },
                    close() {
                        if (readerClosed || eof) return false;
                        readerClosed = true;
                        return sync('streamClose', {token: reply.token});
                    }
                });
            },
            async stream(source, options = {}) {
                check();
                if (options == null || typeof options !== 'object' || Array.isArray(options)) throw new TypeError('Invalid stream options');
                if (typeof source !== 'string') return streamBody(source, owner, options);
                const reply = await async('stream', {url: source, options});
                return {code: reply.code, headers: reply.headers, contentType: reply.contentType, stream: streamHandle(reply.token, owner)};
            },
            connect(url, options, callback) {
                check();
                if (options == null) options = {};
                if (typeof url !== 'string' || typeof options !== 'object' || Array.isArray(options) || typeof callback !== 'function') throw new TypeError('Invalid socket options/listener');
                const item = {owner, closed: false, ended: false, callback};
                let token;
                const socket = {
                    send(data) {
                        check();
                        if (item.closed) return false;
                        if (typeof data === 'string') return native.socketSend(token, data, null);
                        const view = ArrayBuffer.isView(data), buffer = view ? data.buffer : data;
                        if (!(buffer instanceof ArrayBuffer)) throw new TypeError('Socket send requires text or bytes');
                        return native.socketSend(token, null, view ? buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) : buffer);
                    },
                    close() {
                        if (item.closed) return;
                        item.closed = true;
                        sync('socketClose', {token, cancel: false});
                    },
                    cancel() {
                        if (item.ended) return;
                        item.closed = item.ended = true;
                        sockets.delete(token);
                        sync('socketClose', {token, cancel: true});
                    }
                };
                item.socket = socket;
                token = sync('connect', {url, options});
                sockets.set(token, item);
                return socket;
            },

            batch(requests, limit = 4) { return async('batch', {requests, limit}); },
            download(url, path, options = {}) { return async('download', {url, path, options}, false); },
            sleep(ms) { return async('sleep', {ms}, false); },
            session() {
                const child = sync('session');
                ownerParents.set(child, owner);
                return createNet(child);
            },
            getCookie(url) { return sync('cookieGet', {url}); },
            setCookie(url, value) { return sync('cookieSet', {url, value}); },
            getUserAgent() { return sync('userAgent'); },
            cached(key, options, loader) {
                if (typeof loader !== 'function') return Promise.reject(new TypeError('Cache loader is required'));
                return async('cached', {key, options}, true, loader);
            },
            cachedText(key, options, loader) {
                if (typeof loader !== 'function') return Promise.reject(new TypeError('Cache loader is required'));
                return net.cached(key, options, async () => {
                    const value = await loader();
                    if (typeof value !== 'string') throw new TypeError('Text cache loader must return text');
                    return value;
                });
            },
            invalidateCache(key, expected) { return sync('invalidateCache', {key, expected: encode(expected)}); },
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
                markOwnerClosed(owner);
            }
        };
        return net;
    }

    globalThis.net = createNet(0);
    globalThis.__SPIDER_LOCAL_GET__ = (key, fallback = null) => {
        const value = parsed(invoke('localGet', 0, {key}));
        return value == null ? fallback : value;
    };
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
    globalThis.proxyUrl = (params = {}, local = true) => {
        params = params == null ? {} : params;
        if (Object.prototype.toString.call(params) !== '[object Object]' || Object.getOwnPropertySymbols(params).length || typeof local !== 'boolean') throw new TypeError('Invalid proxy parameters');
        for (const [key, value] of Object.entries(params)) {
            if (key === 'do' || key === 'siteKey') throw new TypeError('Reserved proxy parameter');
            if (typeof value !== 'string') throw new TypeError('Proxy parameters require strings');
        }
        return invoke('proxyUrl', 0, {params, local});
    };
    globalThis.danmakuUrl = id => invoke('danmakuUrl', 0, {id});
    globalThis.getProxyUrl = (params = {}) => {
        if (!params || typeof params !== 'object' || Array.isArray(params)) throw new TypeError('Proxy parameters must be an object');
        return invoke('proxy', 0, params);
    };
})();
