const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/assets/js/lib/net.js'), 'utf8');
const legacyCalls = [];
const localData = new Map();
const cache = new Map();
const flights = new Map();
let ownerId = 0;
const calls = [];
const context = {console, URL, setTimeout, clearTimeout, ArrayBuffer, Uint8Array, DataView};
context.local = {
    get(...args) { legacyCalls.push(['get', ...args]); return 'legacy'; },
    set(...args) { legacyCalls.push(['set', ...args]); },
    delete(...args) { legacyCalls.push(['delete', ...args]); }
};
context.req = function legacyReq() {};
context.http = function legacyHttp() {};
context.getProxy = function legacyProxy() {};
context.js2Proxy = function legacyJsProxy() {};
const originalGlobals = ['req', 'http', 'getProxy', 'js2Proxy'].map(key => context[key]);
context.__SPIDER_NET_NATIVE__ = {
    reqBinary(owner, json) {
        const p = JSON.parse(json);
        calls.push({method: 'reqBinary', owner, params: p});
        return {code: 200, headers: {'Content-Type': 'application/octet-stream'}, content: new Uint8Array([0,127,128,255]).buffer};
    },
    call(method, owner, json) {
        const p = JSON.parse(json);
        calls.push({method, owner, params: p});
        const ok = value => JSON.stringify({value});
        const deliver = (kind, id, value, bytes) => queueMicrotask(() => context.__SPIDER_NET_DELIVER__(kind, id, value, bytes));
        if (method === 'req') return ok(JSON.stringify({code: 200, content: 'sync'}));
        if (method === 'localGet') return ok(localData.get(p.key) ?? null);
        if (method === 'localSet') { localData.set(p.key, p.value); return ok(null); }
        if (method === 'localDelete') { localData.delete(p.key); return ok(null); }
        if (method === 'localClear') { localData.clear(); return ok(null); }
        if (method === 'session') return ok(++ownerId);
        if (method === 'close') return ok(null);
        if (method === 'cookieGet') return ok('sid=one');
        if (method === 'cookieSet') return ok(true);
        if (method === 'userAgent') return ok('test-UA');
        if (method === 'proxy') {
            if ('siteKey' in p || 'do' in p) return JSON.stringify({error: 'Reserved proxy parameter'});
            return ok('http://127.0.0.1/proxy?do=js&siteKey=test&' + new URLSearchParams(p));
        }
        if (method === 'peek') return ok(cache.get(p.key) ?? null);
        if (method === 'clearCache') { p.key === undefined ? cache.clear() : cache.delete(p.key); return ok(null); }
        if (method === 'loaded') {
            const flight = flights.get(p.id);
            if (flight) {
                flights.delete(p.id);
                if (!p.error) cache.set(flight.key, p.value);
                deliver('releaseLoader', p.id, '');
                deliver(flight.refresh ? 'refreshed' : 'result', p.id, p.error ? JSON.stringify({error: p.error}) : ok(p.value));
            }
            return ok(null);
        }
        if (method === 'start') {
            if (p.operation === 'cached' || p.operation === 'refresh') {
                if (cache.has(p.key) && p.key !== 'stale') {
                    deliver('releaseLoader', p.id, '');
                    deliver(p.operation === 'refresh' ? 'refreshed' : 'result', p.id, ok(cache.get(p.key)));
                } else {
                    flights.set(p.id, {key: p.key, refresh: p.operation === 'refresh'});
                    deliver('load', p.id, '');
                    if (p.key === 'stale') deliver('result', p.id, ok(cache.get(p.key)));
                }
                return ok(null);
            }
            if (p.url === 'error') deliver('result', p.id, JSON.stringify({error: 'HTTP 403'}));
            else if (p.url === 'pending') return ok(null);
            else if (p.operation === 'json') deliver('result', p.id, ok('{"items":[1,2]}'));
            else if (p.operation === 'http' && p.options?.buffer === 1) {
                const metadata = JSON.stringify({code: 200, headers: {'Content-Type': 'application/octet-stream', 'X-Multi': ['a','b']}});
                if (p.url === 'binary-error') deliver('result', p.id, ok('{"code":"","error":"Canceled","content":""}'));
                else if (p.url === 'binary-invalid') deliver('binary', p.id, ok(metadata), [0,127,128,255]);
                else deliver('binary', p.id, ok(metadata), new Uint8Array(p.url === 'binary-empty' ? [] : [0,127,128,255]).buffer);
            }
            else if (p.operation === 'http') deliver('result', p.id, ok('{"code":200,"content":"async"}'));
            else if (p.operation === 'ws') deliver('result', p.id, ok('{"code":101,"content":"message"}'));
            else if (p.operation === 'batch') deliver('result', p.id, ok('[{"code":200},{"code":404}]'));
            else if (p.operation === 'download') deliver('result', p.id, ok('/cache/file'));
            else if (p.operation === 'sleep') deliver('result', p.id, ok(null));
            else throw new Error('Unexpected operation ' + p.operation);
            return ok(null);
        }
        throw new Error('Unexpected method ' + method);
    }
};
vm.createContext(context);
vm.runInContext(source, context);
const plain = value => JSON.parse(JSON.stringify(value));

(async () => {
    ['req', 'http', 'getProxy', 'js2Proxy'].forEach((key, i) => assert.equal(context[key], originalGlobals[i]));
    assert.equal(context.local.get('rule', 'key'), 'legacy');
    context.local.set('rule', 'key', 'value');
    context.local.delete('rule', 'key');
    assert.deepEqual(legacyCalls, [['get', 'rule', 'key'], ['set', 'rule', 'key', 'value'], ['delete', 'rule', 'key']]);
    context.local.set('key', {items: [1, false, null]});
    assert.deepEqual(plain(context.local.get('key')), {items: [1, false, null]});
    context.local.delete('key');
    assert.equal(context.local.get('key'), null);
    assert.throws(() => context.local.set('key', undefined), /JSON/);
    assert.deepEqual(plain(context.page([1, 2, 3, 4, 5], '2', 2)), {list: [3, 4], page: 2, pagecount: 3, limit: 2, total: 5});
    assert.deepEqual(plain(context.page([], 1, 2)).list, []);
    assert.throws(() => context.page([1], 0, 2), /positive/);
    assert.equal(context.link('name', {id: '1'}), '[a=cr:{"id":"1"}/]name[/a]');
    assert.match(context.getProxyUrl({q: 'a b&中'}), /q=a\+b%26%E4%B8%AD/);
    assert.throws(() => context.getProxyUrl({siteKey: 'wrong'}), /Reserved/);
    assert.equal(context.net.req('url').content, 'sync');
    assert.equal((await context.net.req('url', {async: true})).content, 'async');
    const binary = await context.net.http('binary-buffer', {buffer: 1});
    assert(binary.content instanceof ArrayBuffer);
    assert.deepEqual(Array.from(new Uint8Array(binary.content)), [0,127,128,255]);
    assert.deepEqual(plain(binary.headers), {'Content-Type':'application/octet-stream', 'X-Multi':['a','b']});
    assert.equal(binary.code, 200);
    assert((await context.net.http('binary-empty', {buffer:1})).content instanceof ArrayBuffer);
    assert.equal((await context.net.http('binary-empty', {buffer:1})).content.byteLength, 0);
    assert.deepEqual(plain(await context.net.http('binary-error', {buffer:1})), {code:'',error:'Canceled',content:''});
    await assert.rejects(context.net.http('binary-invalid', {buffer:1}), {name:'TypeError'});
    const synchronousBinary = context.net.req('binary-buffer', {buffer:1});
    assert(synchronousBinary.content instanceof ArrayBuffer);
    assert.deepEqual(Array.from(new Uint8Array(synchronousBinary.content)), [0,127,128,255]);
    const binaryProxy = [200,'application/octet-stream',binary.content,{'X-Probe':'yes'}];
    assert.deepEqual(JSON.parse(context.__SPIDER_NET_PROXY_JSON__(binaryProxy)), [200,'application/octet-stream',[0,127,128,255],{'X-Probe':'yes'}]);
    assert.equal(binaryProxy[2], binary.content);
    const paddedBody = new Uint8Array([9,0,127,128,255,8]);
    assert.deepEqual(JSON.parse(context.__SPIDER_NET_PROXY_JSON__([200,'binary',new DataView(paddedBody.buffer,1,4)]))[2], [0,127,128,255]);
    assert.deepEqual(JSON.parse(context.__SPIDER_NET_PROXY_JSON__([200,'binary',[0,127,-128,-1]]))[2], [0,127,-128,-1]);
    const binaryOptions = {method: 'POST', body: Array.from({length: 256}, (_, i) => i),
        headers: {'Content-Type': 'application/octet-stream'}, buffer: 3};
    context.net.req('binary', binaryOptions);
    assert.deepEqual(calls.at(-1).params.options, binaryOptions);
    await context.net.http('binary', binaryOptions);
    assert.deepEqual(calls.at(-1).params.options, binaryOptions);
    const binaryJsonOptions = {...binaryOptions, buffer: 0};
    await context.net.json('binary-json', binaryJsonOptions);
    assert.deepEqual(calls.at(-1).params.options, binaryJsonOptions);
    const binaryBatch = [{url: 'binary-batch', options: binaryOptions}];
    await context.net.batch(binaryBatch);
    assert.deepEqual(calls.at(-1).params.requests, binaryBatch);
    assert.deepEqual(binaryOptions.body, Array.from({length: 256}, (_, i) => i));
    assert.equal(Object.hasOwn(binaryOptions, 'data'), false);
    assert.deepEqual(plain(await context.net.json('url')), {items: [1, 2]});
    await assert.rejects(context.net.json('error'), /HTTP 403/);
    assert.equal((await context.net.ws('url')).code, 101);
    assert.deepEqual(plain(await context.net.batch([{url: 'a'}, {url: 'b'}])), [{code: 200}, {code: 404}]);
    assert.equal(await context.net.download('url', 'file'), '/cache/file');
    assert.equal(await context.net.sleep(1), null);
    assert.equal(context.net.getUserAgent(), 'test-UA');
    const session = context.net.session();
    assert.equal(session.getCookie('url'), 'sid=one');
    assert.equal(session.setCookie('url', 'sid=two'), true);
    session.close();
    await assert.rejects(session.http('url'), /closed/);
    let loads = 0;
    assert.deepEqual(plain(await context.net.cached('key', {ttl: 1000}, async () => { loads++; return {value: 1}; })), {value: 1});
    assert.deepEqual(plain(await context.net.cached('key', {ttl: 1000}, () => { loads++; return {value: 2}; })), {value: 1});
    assert.equal(loads, 1);
    assert.deepEqual(plain(context.net.peek('key')), {value: 1});
    context.net.clearCache('key');
    assert.equal(context.net.peek('key'), null);
    await assert.rejects(context.net.cached('bad', {ttl: 1}, () => undefined), /JSON/);
    cache.set('stale', '{"value":"old"}');
    let finish;
    const stale = await context.net.cached('stale', {ttl: 1}, () => new Promise(resolve => { finish = resolve; }));
    assert.deepEqual(plain(stale), {value: 'old'});
    finish({value: 'new'});
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(plain(context.net.peek('stale')), {value: 'new'});
    assert.equal(context.net.refresh('refresh', {ttl: 1}, async () => ({updated: true})), undefined);
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(plain(context.net.peek('refresh')), {updated: true});
    const waiting = context.net.http('pending');
    const waitingBinary = context.net.http('pending', {buffer:1});
    context.__SPIDER_NET_DELIVER__('close', 0, '');
    await assert.rejects(waiting, /closed/);
    await assert.rejects(waitingBinary, /closed/);
    assert.throws(() => context.local.get('key'), /closed/);
    assert.equal(calls.some(call => call.owner === 1 && call.method === 'cookieGet'), true);
    console.log('PASS QuickJS facade protocol: legacy globals/local, values, HTTP/JSON/WS/batch/download/sleep/session, cache loaders/stale/refresh, close rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
