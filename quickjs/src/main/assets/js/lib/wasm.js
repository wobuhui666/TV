(() => {
    const native = globalThis.__SPIDER_WASM_NATIVE__;
    const moduleStates = new WeakMap();
    const instanceStates = new WeakMap();
    const memoryStates = new WeakMap();
    const instances = new Map();
    const memories = new Map();
    let nextInstance = 0;
    let closed = false;

    class CompileError extends Error { constructor(message) { super(message); this.name = 'CompileError'; } }
    class LinkError extends Error { constructor(message) { super(message); this.name = 'LinkError'; } }
    class RuntimeError extends Error { constructor(message) { super(message); this.name = 'RuntimeError'; } }
    const errors = {CompileError, LinkError, RuntimeError, TypeError, RangeError, Error};

    function unwrap(text) {
        const result = JSON.parse(text);
        if (result.error) {
            const error = result.error;
            throw new (errors[error.name] || RuntimeError)(error.message);
        }
        return result.value;
    }

    function invoke(method, params = {}, bytes) {
        if (closed) throw new RuntimeError('WASM runtime closed');
        return unwrap(native.call(method, JSON.stringify(params), bytes));
    }

    function bytesOf(value, arrays = false) {
        if (value instanceof ArrayBuffer) return value.slice(0);
        if (ArrayBuffer.isView(value)) return value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength);
        if (arrays && Array.isArray(value)) {
            if (!value.every(x => Number.isInteger(x) && x >= 0 && x <= 255)) throw new TypeError('WASM bytes must be integers from 0 to 255');
            return Uint8Array.from(value).buffer;
        }
        throw new TypeError('WASM requires an ArrayBuffer or typed array');
    }

    function integer(value, label) {
        if (!Number.isSafeInteger(value) || value < 0 || value > 2147483647) throw new RangeError(label + ' must be a nonnegative integer');
        return value;
    }

    function state(map, value, label) {
        const item = map.get(value);
        if (closed || !item || item.closed) throw new RuntimeError('WASM ' + label + ' closed or belongs to another source');
        return item;
    }

    function encodeScalar(type, value) {
        if (type === 'I64') {
            if (typeof value === 'number') throw new TypeError('WASM i64 requires a BigInt');
            return BigInt.asIntN(64, BigInt(value)).toString();
        }
        if (typeof value === 'bigint') throw new TypeError('WASM numeric argument cannot be a BigInt');
        let number = Number(value);
        if (type === 'I32') return String(number | 0);
        if (type === 'F32') number = Math.fround(number);
        if (Number.isNaN(number)) return 'nan';
        if (number === Infinity) return 'infinity';
        if (number === -Infinity) return '-infinity';
        return Object.is(number, -0) ? '-0.0' : String(number);
    }

    function decodeScalar(type, value) {
        if (type === 'I64') return BigInt(value);
        if (value === 'nan' || value === 'NaN') return NaN;
        if (value === 'infinity' || value === 'Infinity') return Infinity;
        if (value === '-infinity' || value === '-Infinity') return -Infinity;
        return type === 'F32' ? Math.fround(Number(value)) : Number(value);
    }

    function encodeArguments(signature, args) {
        // WebAssembly ignores extra arguments and converts absent arguments just like JS scalars.
        return signature.params.map((type, i) => encodeScalar(type, args[i]));
    }

    function decodeResults(signature, result) {
        const values = signature.results.map((type, i) => decodeScalar(type, result[i]));
        return values.length === 0 ? undefined : values.length === 1 ? values[0] : values;
    }

    function readNative(memory, offset, length) {
        if (closed) throw new RuntimeError('WASM runtime closed');
        const result = native.read(JSON.stringify({memory: memory.id, offset, length}));
        if (typeof result === 'string') return unwrap(result);
        return result;
    }

    function syncToNative(memory) {
        const item = state(memoryStates, memory, 'memory');
        if (item.buffer) invoke('memoryWrite', {memory: item.id, offset: 0}, item.buffer);
    }

    function syncFromNative(memory) {
        const item = state(memoryStates, memory, 'memory');
        const info = invoke('memoryInfo', {memory: item.id});
        item.pages = info.pages;
        if (!item.buffer) return;
        const bytes = readNative(item, 0, item.pages * 65536);
        if (item.buffer.byteLength === bytes.byteLength) new Uint8Array(item.buffer).set(new Uint8Array(bytes));
        else item.buffer = bytes;
    }

    function getMemory(descriptor) {
        if (!descriptor) return null;
        let memory = memories.get(descriptor.id);
        if (!memory) {
            memory = Object.create(Memory.prototype);
            memoryStates.set(memory, {id: descriptor.id, pages: descriptor.pages, closed: false, buffer: null});
            memories.set(descriptor.id, memory);
        } else state(memoryStates, memory, 'memory').pages = descriptor.pages;
        return memory;
    }

    class Memory {
        constructor(descriptor) {
            if (!descriptor || typeof descriptor !== 'object') throw new TypeError('WASM memory requires a descriptor');
            if (descriptor.shared || descriptor.index === 'i64') throw new TypeError('Shared and memory64 WASM memories are unavailable');
            const initial = integer(Number(descriptor.initial), 'Memory initial');
            const maximum = descriptor.maximum === undefined ? invoke('limits').memoryPages : integer(Number(descriptor.maximum), 'Memory maximum');
            if (maximum < initial) throw new RangeError('Memory maximum must cover initial pages');
            const info = invoke('memory', {initial, maximum});
            memoryStates.set(this, {id: info.id, pages: info.pages, closed: false, buffer: null});
            memories.set(info.id, this);
        }
        get byteLength() {
            const item = state(memoryStates, this, 'memory');
            item.pages = invoke('memoryInfo', {memory: item.id}).pages;
            return item.pages * 65536;
        }
        get buffer() {
            const item = state(memoryStates, this, 'memory');
            if (!item.buffer) item.buffer = readNative(item, 0, this.byteLength);
            return item.buffer;
        }
        read(offset, length) {
            const item = state(memoryStates, this, 'memory');
            integer(offset, 'Memory offset'); integer(length, 'Memory length');
            syncToNative(this);
            return readNative(item, offset, length);
        }
        write(offset, bytes) {
            const item = state(memoryStates, this, 'memory');
            integer(offset, 'Memory offset');
            const buffer = bytesOf(bytes, true);
            syncToNative(this);
            invoke('memoryWrite', {memory: item.id, offset}, buffer);
            syncFromNative(this);
        }
        grow(pages) {
            const item = state(memoryStates, this, 'memory');
            integer(pages, 'Memory growth');
            syncToNative(this);
            const previous = invoke('memoryGrow', {memory: item.id, pages});
            if (previous < 0) throw new RangeError('WASM memory maximum exceeded');
            syncFromNative(this);
            return previous;
        }
        close() {
            const item = memoryStates.get(this);
            if (!item || item.closed) return;
            if (!closed) invoke('closeMemory', {memory: item.id});
            item.closed = true;
            memories.delete(item.id);
            item.buffer = null;
        }
    }

    class Module {
        constructor(bytes) {
            moduleStates.set(this, {...invoke('compile', {}, bytesOf(bytes)), closed: false});
        }
        static imports(module) {
            return state(moduleStates, module, 'module').imports.map(({module, name, kind}) => ({module, name, kind: kind.toLowerCase()}));
        }
        static exports(module) {
            return state(moduleStates, module, 'module').exports.map(({name, kind}) => ({name, kind: kind.toLowerCase()}));
        }
        close() {
            const item = moduleStates.get(this);
            if (!item || item.closed) return;
            if (!closed) invoke('closeModule', {module: item.id});
            item.closed = true;
        }
    }

    function defaultMemory(instance) {
        const item = state(instanceStates, instance, 'instance');
        if (!item.memory) throw new RuntimeError('Missing WASM memory');
        return item.memory;
    }

    function releaseInstanceMemory(item) {
        if (!item.memory) return;
        const memory = memoryStates.get(item.memory);
        if (memory.closed) return;
        if (!item.importedMemory) memory.originOwnerClosed = true;
        if (!memory.originOwnerClosed) return;
        for (const instance of instances.values()) {
            if (instanceStates.get(instance).memory === item.memory) return;
        }
        memory.closed = true;
        memory.buffer = null;
        memories.delete(memory.id);
    }

    function callExport(instance, name, args, timeout) {
        const item = state(instanceStates, instance, 'instance');
        const signature = item.signatures[name];
        if (!signature) throw new TypeError('Unknown WASM function: ' + name);
        const arguments_ = encodeArguments(signature, args);
        if (timeout !== undefined && (!Number.isSafeInteger(timeout) || timeout < 1)) throw new RangeError('WASM timeout must be a positive integer');
        if (item.memory) syncToNative(item.memory);
        let failure;
        try {
            return decodeResults(signature, invoke('call', {instance: item.id, name, arguments: arguments_, ...(timeout === undefined ? {} : {timeout})}));
        } catch (error) {
            failure = error;
            throw error;
        } finally {
            if (item.memory && !closed) {
                try { syncFromNative(item.memory); }
                catch (error) { if (!failure) throw error; }
            }
        }
    }

    class InstanceHandle {
        get exports() { return state(instanceStates, this, 'instance').exports; }
        get signatures() { return state(instanceStates, this, 'instance').signatures; }
        get memory() { return defaultMemory(this); }
        get byteLength() { return defaultMemory(this).byteLength; }
        call(name, ...args) { return callExport(this, name, args); }
        callTimed(name, timeout, ...args) { return callExport(this, name, args, timeout); }
        read(offset, length) { return defaultMemory(this).read(offset, length); }
        write(offset, bytes) { defaultMemory(this).write(offset, bytes); }
        grow(pages) { return defaultMemory(this).grow(pages); }
        close() {
            const item = instanceStates.get(this);
            if (!item || item.closed) return;
            if (!closed) invoke('closeInstance', {instance: item.id});
            item.closed = true;
            instances.delete(item.id);
            item.imports = null;
            releaseInstanceMemory(item);
            if (item.ownedModule) item.ownedModule.close();
        }
    }

    function instantiateModule(module, imports = {}, standard = false, target = Object.create(InstanceHandle.prototype)) {
        const compiled = state(moduleStates, module, 'module');
        if (!imports || typeof imports !== 'object') throw new TypeError('WASM imports must be an object');
        const descriptors = Object.create(null);
        const functions = Object.create(null);
        for (const imported of compiled.imports) {
            const namespace = imports[imported.module];
            const value = namespace && namespace[imported.name];
            if (!descriptors[imported.module]) descriptors[imported.module] = Object.create(null);
            if (imported.kind === 'FUNCTION') {
                if (typeof value !== 'function') throw new LinkError('Missing WASM function import: ' + imported.module + '.' + imported.name);
                descriptors[imported.module][imported.name] = {kind: 'function'};
                if (!functions[imported.module]) functions[imported.module] = Object.create(null);
                functions[imported.module][imported.name] = value;
            } else if (imported.kind === 'MEMORY') {
                if (!memoryStates.has(value)) throw new LinkError('WASM memory import requires wasm.memory or WebAssembly.Memory');
                const memory = state(memoryStates, value, 'memory');
                syncToNative(value);
                descriptors[imported.module][imported.name] = {kind: 'memory', memory: memory.id};
            } else throw new LinkError('Unsupported WASM import: ' + imported.kind.toLowerCase());
        }
        const item = {id: ++nextInstance, module, imports: functions, standard, signatures: Object.create(null),
            exports: Object.create(null), memory: null, closed: false,
            importedMemory: compiled.imports.some(entry => entry.kind === 'MEMORY')};
        instanceStates.set(target, item);
        instances.set(item.id, target);
        try {
            const info = invoke('instantiate', {instance: item.id, module: compiled.id, imports: descriptors});
            item.memory = getMemory(info.memory);
            for (const exported of info.exports) {
                if (exported.kind === 'FUNCTION') {
                    const signature = Object.freeze({params: Object.freeze(exported.signature.params), results: Object.freeze(exported.signature.results)});
                    item.signatures[exported.name] = signature;
                    item.exports[exported.name] = (...args) => callExport(target, exported.name, args);
                } else if (exported.kind === 'MEMORY') item.exports[exported.name] = item.memory;
                else throw new LinkError('Unsupported WASM export: ' + exported.kind.toLowerCase());
            }
            Object.freeze(item.signatures);
            Object.freeze(item.exports);
            if (item.memory) syncFromNative(item.memory);
            return target;
        } catch (error) {
            // Includes a successful native instantiation followed by facade construction failure.
            try { invoke('closeInstance', {instance: item.id}); } catch (_) {}
            instances.delete(item.id);
            item.closed = true;
            releaseInstanceMemory(item);
            throw error;
        }
    }

    class Instance extends InstanceHandle {
        constructor(module, imports = {}) {
            super();
            instantiateModule(module, imports, true, this);
        }
    }

    function instantiate(bytes, imports = {}) {
        if (moduleStates.has(bytes)) return instantiateModule(bytes, imports);
        const module = new Module(bytesOf(bytes, true));
        try {
            const instance = instantiateModule(module, imports);
            instanceStates.get(instance).ownedModule = module;
            return instance;
        } catch (error) {
            module.close();
            throw error;
        }
    }

    globalThis.__SPIDER_WASM_DISPATCH__ = (kind, id, text) => {
        if (kind === 'close') {
            closed = true;
            instances.forEach(instance => { const item = instanceStates.get(instance); item.closed = true; item.imports = null; });
            memories.forEach(memory => { const item = memoryStates.get(memory); item.closed = true; item.buffer = null; });
            instances.clear(); memories.clear();
            return;
        }
        try {
            if (kind !== 'import') throw new RuntimeError('Unknown WASM callback');
            const instance = instances.get(id);
            const item = state(instanceStates, instance, 'instance');
            const request = JSON.parse(text);
            item.memory = getMemory(request.memory);
            if (item.memory) syncFromNative(item.memory);
            const fn = item.imports[request.module][request.name];
            const args = request.signature.params.map((type, i) => decodeScalar(type, request.arguments[i]));
            let returned;
            try { returned = item.standard ? fn(...args) : fn(instance, ...args); }
            finally { if (item.memory && !closed) syncToNative(item.memory); }
            const resultTypes = request.signature.results;
            const values = resultTypes.length === 0 ? [] : resultTypes.length === 1 ? [returned] : returned;
            if (!Array.isArray(values) || values.length !== resultTypes.length) throw new TypeError('WASM import results do not match signature');
            return JSON.stringify({value: resultTypes.map((type, i) => encodeScalar(type, values[i]))});
        } catch (error) {
            return JSON.stringify({error: {name: error && error.name || 'Error', message: String(error && error.message || error)}});
        }
    };

    const api = {
        instantiate,
        compile: bytes => new Module(bytesOf(bytes, true)),
        validate: bytes => invoke('validate', {}, bytesOf(bytes, true)),
        memory: (initial, maximum) => instanceStates.has(initial) ? defaultMemory(initial) : new Memory({initial, maximum}),
        call: (instance, name, ...args) => callExport(instance, name, args),
        callTimed: (instance, name, timeout, ...args) => callExport(instance, name, args, timeout),
        read: (instance, offset, length) => defaultMemory(instance).read(offset, length),
        write: (instance, offset, bytes) => defaultMemory(instance).write(offset, bytes),
        byteLength: instance => defaultMemory(instance).byteLength,
        close: instance => {
            if (!instanceStates.has(instance)) throw new RuntimeError('WASM instance belongs to another source');
            instance.close();
        },
        async create(source, imports = {}) {
            if (typeof source === 'string') {
                const response = await net.http(source, {buffer: 1});
                if (response.code < 200 || response.code >= 300) throw new RuntimeError('WASM HTTP ' + response.code);
                source = response.content;
            }
            return instantiate(source, imports);
        }
    };
    globalThis.wasm = Object.freeze(api);
    if (typeof globalThis.WebAssembly === 'undefined') {
        globalThis.WebAssembly = Object.freeze({Module, Instance, Memory, CompileError, LinkError, RuntimeError,
            validate: bytes => invoke('validate', {}, bytesOf(bytes)),
            compile: async bytes => new Module(bytes),
            instantiate: async (bytes, imports = {}) => {
                if (moduleStates.has(bytes)) return new Instance(bytes, imports);
                const module = new Module(bytes);
                try { return {module, instance: new Instance(module, imports)}; }
                catch (error) { module.close(); throw error; }
            }
        });
    }
})();
