const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// The facade runs in a separate realm. Node's real WASM engine models the native bridge,
// so fixtures exercise actual scalar conversions, traps, imports and linear memory.
const Engine = WebAssembly;
const uleb = value => { const result = []; do { let byte = value & 127; value >>>= 7; result.push(byte | (value ? 128 : 0)); } while (value); return result; };
const text = value => [...uleb(Buffer.byteLength(value)), ...Buffer.from(value)];
const section = (id, values) => [id, ...uleb(values.length), ...values];
const types = [['I32', 'I32'], ['I64'], ['F32'], ['F64'], ['I32'], ['I32', 'I32'], [], []];
const results = [['I32'], ['I64'], ['F32'], ['F64'], ['I32'], [], [], ['I32', 'I64']];
const typeCodes = {I32: 0x7f, I64: 0x7e, F32: 0x7d, F64: 0x7c};
const functionTypes = [0, 1, 2, 3, 4, 4, 5, 4, 6, 7];
const names = ['add', 'i64_id', 'f32_id', 'f64_id', 'callback', 'peek', 'poke', 'grow', 'trap', 'multi'];
const signatures = Object.fromEntries(names.map((name, i) => [name, {params: types[functionTypes[i]], results: results[functionTypes[i]]}]));
function binary(importMemory = false) {
    const encodedTypes = [types.length, ...types.flatMap((params, i) => [0x60, params.length, ...params.map(x => typeCodes[x]), results[i].length, ...results[i].map(x => typeCodes[x])])];
    const imports = [importMemory ? 2 : 1, ...text('env'), ...text('double'), 0, 4];
    if (importMemory) imports.push(...text('env'), ...text('memory'), 2, 1, 1, 2);
    const exports = [names.length + 1, ...names.flatMap((name, i) => [...text(name), 0, i + 1]), ...text('memory'), 2, 0];
    const bodies = [
        [0x20, 0, 0x20, 1, 0x6a], [0x20, 0], [0x20, 0], [0x20, 0],
        [0x20, 0, 0x10, 0], [0x20, 0, 0x2d, 0, 0], [0x20, 0, 0x20, 1, 0x3a, 0, 0],
        [0x20, 0, 0x40, 0], [0], [0x41, 7, 0x42, 9]
    ].map(code => [0, ...code, 0x0b]);
    return Uint8Array.from([0, 97, 115, 109, 1, 0, 0, 0, ...section(1, encodedTypes), ...section(2, imports),
        ...section(3, [functionTypes.length, ...functionTypes]), ...(importMemory ? [] : section(5, [1, 1, 1, 2])),
        ...section(7, exports), ...section(10, [bodies.length, ...bodies.flatMap(body => [...uleb(body.length), ...body])])]);
}

function sandbox() {
    const modules = new Map(), instances = new Map(), memories = new Map(), memoryIds = new WeakMap();
    let nextModule = 0, nextMemory = 0;
    const context = {console, ArrayBuffer, Uint8Array, DataView, WebAssembly: undefined};
    const ok = value => JSON.stringify({value});
    const fail = error => JSON.stringify({error: {name: error.name, message: error.message}});
    const encode = value => Object.is(value, -0) ? '-0.0' : String(value);
    const decode = (type, value) => type === 'I64' ? BigInt(value) : value === 'nan' ? NaN : value === 'infinity' ? Infinity : value === '-infinity' ? -Infinity : Number(value);
    function memoryInfo(backend) {
        let id = memoryIds.get(backend);
        if (!id) { id = ++nextMemory; memoryIds.set(backend, id); memories.set(id, backend); }
        return {id, pages: backend.buffer.byteLength / 65536};
    }
    function description(id, backend) {
        return {id, imports: Engine.Module.imports(backend).map(item => ({...item, kind: item.kind.toUpperCase(), signature: item.kind === 'function' ? {params: ['I32'], results: ['I32']} : null})),
            exports: Engine.Module.exports(backend).map(item => ({...item, kind: item.kind.toUpperCase(), signature: signatures[item.name] || null}))};
    }
    context.__SPIDER_WASM_NATIVE__ = {
        call(method, json, bytes) {
            const p = JSON.parse(json);
            try {
                if (method === 'limits') return ok({memoryPages: 1024});
                if (method === 'validate') return ok(Engine.validate(bytes));
                if (method === 'compile') { const backend = new Engine.Module(bytes); modules.set(++nextModule, backend); return ok(description(nextModule, backend)); }
                if (method === 'instantiate') {
                    const backend = modules.get(p.module);
                    const importedMemory = p.imports.env?.memory ? memories.get(p.imports.env.memory.memory) : null;
                    const binding = {backend: null};
                    const imports = {env: {double: (...args) => {
                        const reply = JSON.parse(context.__SPIDER_WASM_DISPATCH__('import', p.instance,
                            JSON.stringify({module: 'env', name: 'double', signature: {params: ['I32'], results: ['I32']}, arguments: args.map(encode), memory: memoryInfo(binding.backend.exports.memory)})));
                        if (reply.error) { const error = new Error(reply.error.message); error.name = reply.error.name; throw error; }
                        return Number(reply.value[0]);
                    }, ...(importedMemory ? {memory: importedMemory} : {})}};
                    binding.backend = new Engine.Instance(backend, imports);
                    instances.set(p.instance, binding.backend);
                    return ok({...description(p.instance, backend), memory: memoryInfo(binding.backend.exports.memory)});
                }
                if (method === 'call') {
                    const signature = signatures[p.name];
                    const result = instances.get(p.instance).exports[p.name](...p.arguments.map((value, i) => decode(signature.params[i], value)));
                    return ok((signature.results.length === 0 ? [] : signature.results.length === 1 ? [result] : result)
                        .map(value => p.name === 'f32_id' && value === Math.fround(1.1) ? '1.1' : encode(value)));
                }
                if (method === 'memory') return ok(memoryInfo(new Engine.Memory({initial: p.initial, maximum: p.maximum})));
                if (method === 'memoryInfo') return ok(memoryInfo(memories.get(p.memory)));
                if (method === 'memoryWrite') {
                    const buffer = memories.get(p.memory).buffer;
                    if (p.offset < 0 || p.offset + bytes.byteLength > buffer.byteLength) throw new RangeError('Memory write out of bounds');
                    new Uint8Array(buffer).set(new Uint8Array(bytes), p.offset); return ok(null);
                }
                if (method === 'memoryGrow') return ok(memories.get(p.memory).grow(p.pages));
                if (method === 'closeInstance') { instances.delete(p.instance); return ok(null); }
                if (method === 'closeModule') { modules.delete(p.module); return ok(null); }
                if (method === 'closeMemory') { memories.delete(p.memory); return ok(null); }
                throw new Error('Unexpected native operation ' + method);
            } catch (error) { return fail(error); }
        },
        read(json) {
            const p = JSON.parse(json), buffer = memories.get(p.memory).buffer;
            if (p.offset < 0 || p.length < 0 || p.offset + p.length > buffer.byteLength) return fail(new RangeError('Memory read out of bounds'));
            return buffer.slice(p.offset, p.offset + p.length);
        }
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/assets/js/lib/wasm.js'), 'utf8'), context);
    return context;
}

(async () => {
    const context = sandbox(), wasm = context.wasm, standard = context.WebAssembly;
    const raw = binary(), imported = binary(true);
    const imports = {env: {double: (owner, x) => { assert.equal(new Uint8Array(owner.read(0, 1))[0], 73); return x * 2; }}};
    const padded = new Uint8Array(raw.length + 4); padded.set(raw, 2);
    for (const bytes of [raw.buffer, padded.subarray(2, raw.length + 2), new DataView(padded.buffer, 2, raw.length), Array.from(raw)]) {
        const instance = wasm.instantiate(bytes, imports);
        assert.equal(instance.exports.add(19, 23), 42);
        assert.equal(wasm.call(instance, 'add', 40, 2), 42);
        instance.exports.poke(0, 73);
        assert.equal(instance.exports.callback(21), 42);
        assert.equal(instance.exports.i64_id(1n), 1n);
        assert.equal(instance.exports.i64_id(9223372036854775807n), 9223372036854775807n);
        assert.throws(() => instance.exports.i64_id(1), {name: 'TypeError'});
        for (const name of ['f32_id', 'f64_id']) {
            assert(Number.isNaN(instance.exports[name](NaN)));
            assert.equal(instance.exports[name](Infinity), Infinity);
            assert.equal(instance.exports[name](-Infinity), -Infinity);
            assert(Object.is(instance.exports[name](-0), -0));
        }
        assert.equal(instance.exports.f32_id(1.1), Math.fround(1.1));
        assert.deepEqual(Array.from(instance.exports.multi()), [7, 9n]);
        assert.equal(instance.exports.poke(0, 73), undefined);
        const saved = instance.exports.add;
        wasm.close(instance); wasm.close(instance);
        assert.throws(() => saved(1, 2), {name: 'RuntimeError'});
    }
    const instance = wasm.instantiate(raw, imports);
    wasm.write(instance, 4, new Uint8Array([9, 0, 127, 128, 255, 8]).subarray(1, 5));
    assert.deepEqual(Array.from(new Uint8Array(wasm.read(instance, 4, 4))), [0, 127, 128, 255]);
    assert.throws(() => instance.read(65535, 2), {name: 'RangeError'});
    assert.throws(() => instance.exports.trap(), {name: 'RuntimeError'});
    assert.equal(instance.call('add', 20, 22), 42);
    assert.equal(instance.grow(1), 1);
    assert.equal(instance.byteLength, 131072);
    assert.throws(() => instance.grow(1), {name: 'RangeError'});
    assert.throws(() => wasm.instantiate([0, 1, 2]), {name: 'CompileError'});
    assert.throws(() => wasm.instantiate(raw), {name: 'LinkError'});
    assert.throws(() => wasm.instantiate([256]), {name: 'TypeError'});
    assert.throws(() => wasm.call({}, 'add', 1, 2), {name: 'RuntimeError'});
    context.net = {http: async (url, options) => {
        assert.deepEqual({...options}, {buffer: 1});
        return {code: url === 'fixture.wasm' ? 200 : 404, content: raw.buffer};
    }};
    const fetched = await wasm.create('fixture.wasm', {env: {double: (owner, x) => x * 2}});
    assert.equal(fetched.exports.callback(21), 42);
    fetched.close();
    await assert.rejects(wasm.create('missing.wasm'), {name: 'RuntimeError'});

    const throwing = wasm.instantiate(raw, {env: {double: () => { throw new TypeError('host callback failure'); }}});
    assert.throws(() => throwing.exports.callback(1), {name: 'TypeError', message: 'host callback failure'});
    assert.equal(throwing.exports.add(1, 41), 42);
    throwing.close();

    const origin = wasm.instantiate(raw, {env: {double: (owner, x) => x * 2}});
    const shared = origin.memory;
    const sharedView = new Uint8Array(shared.buffer);
    sharedView[0] = 44;
    const borrower = wasm.instantiate(imported, {env: {memory: shared, double: (owner, x) => x * 2}});
    origin.close();
    assert.equal(borrower.exports.add(20, 22), 42);
    assert.equal(borrower.exports.peek(0), 44);
    borrower.exports.poke(0, 73);
    assert.equal(sharedView[0], 73);
    assert.equal(new Uint8Array(shared.read(0, 1))[0], 73);
    assert.equal(borrower.exports.callback(21), 42);
    borrower.close();
    assert.throws(() => shared.read(0, 1), {name: 'RuntimeError'});

    const memory = new standard.Memory({initial: 1, maximum: 2});
    const view = new Uint8Array(memory.buffer);
    view[0] = 41;
    const module = new standard.Module(imported);
    const host = {env: {memory, double: x => { assert.equal(view[0], 73); view[1] = 255; return x * 2; }}};
    const value = new standard.Instance(module, host);
    host.env.double = () => 0; // Imports capture their original function at instantiation.
    assert.equal(value.exports.peek(0), 41);
    value.exports.poke(0, 73);
    assert.equal(view[0], 73);
    assert.equal(value.exports.callback(21), 42);
    assert.equal(value.exports.peek(1), 255);
    assert.equal(value.exports.memory, memory);
    const sibling = new standard.Instance(module, {env: {memory, double: x => x}});
    value.close();
    assert.equal(sibling.exports.peek(1), 255);
    assert.equal(memory.grow(1), 1);
    assert.equal(memory.buffer.byteLength, 131072);
    assert.throws(() => memory.grow(1), {name: 'RangeError'});
    sibling.close();
    assert.equal(new Uint8Array(memory.read(1, 1))[0], 255);
    assert.deepEqual(Array.from(standard.Module.imports(module), x => x.kind), ['function', 'memory']);
    const asynchronous = await standard.instantiate(raw, {env: {double: x => x * 2}});
    assert(asynchronous.module instanceof standard.Module);
    assert(asynchronous.instance instanceof standard.Instance);
    assert.equal(asynchronous.instance.exports.callback(21), 42);
    assert.equal((await standard.instantiate(asynchronous.module, {env: {double: x => x}})).exports.add(1, 41), 42);
    assert(standard.validate(raw));
    assert(!standard.validate(new Uint8Array([0, 1, 2])));
    await assert.rejects(standard.compile(new Uint8Array([0, 1, 2])), {name: 'CompileError'});
    const foreign = sandbox().wasm.instantiate(raw, {env: {double: (owner, x) => x}});
    assert.throws(() => wasm.call(foreign, 'add', 1, 2), {name: 'RuntimeError'});
    context.__SPIDER_WASM_DISPATCH__('close', 0, '');
    assert.throws(() => sibling.exports.peek(0), {name: 'RuntimeError'});
    console.log('WASM facade with real engine: PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
