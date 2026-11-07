package com.fongmi.android.tv.test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Tiny original WASM programs, encoded here so no binary downloads are needed by the tests. */
final class WasmFixture {

    static byte[] module(boolean callback, boolean importedMemory) {
        Bytes module = new Bytes().raw(0, 97, 115, 109, 1, 0, 0, 0);
        Bytes types = new Bytes().uleb(8);
        type(types, new int[]{0x7f, 0x7f}, new int[]{0x7f});
        type(types, new int[]{0x7f}, new int[]{0x7f});
        type(types, new int[]{0x7e}, new int[]{0x7e});
        type(types, new int[]{0x7d}, new int[]{0x7d});
        type(types, new int[]{0x7c}, new int[]{0x7c});
        type(types, new int[]{}, new int[]{0x7f});
        type(types, new int[]{}, new int[]{});
        type(types, new int[]{0x7f, 0x7f}, new int[]{});
        section(module, 1, types);
        if (callback || importedMemory) {
            Bytes imports = new Bytes().uleb((callback ? 1 : 0) + (importedMemory ? 1 : 0));
            if (callback) imports.name("env").name("double").raw(0).uleb(1);
            if (importedMemory) imports.name("env").name("memory").raw(2, 1).uleb(1).uleb(2);
            section(module, 2, imports);
        }
        int[] functionTypes = callback ? new int[]{0, 2, 3, 4, 1, 7, 5, 1, 6, 6, 1}
                : new int[]{0, 2, 3, 4, 1, 7, 5, 1, 6, 6};
        Bytes functions = new Bytes().uleb(functionTypes.length);
        for (int type : functionTypes) functions.uleb(type);
        section(module, 3, functions);
        if (!importedMemory) section(module, 5, new Bytes().raw(1, 1, 1, 2));
        String[] names = {"add", "i64_id", "f32_id", "f64_id", "peek", "poke", "size", "grow", "trap", "spin", "callback"};
        Bytes exports = new Bytes().uleb(functionTypes.length + 1);
        exports.name("memory").raw(2, 0);
        for (int index = 0; index < functionTypes.length; index++) {
            exports.name(names[index]).raw(0).uleb(index + (callback ? 1 : 0));
        }
        section(module, 7, exports);
        Bytes code = new Bytes().uleb(functionTypes.length);
        body(code, 0x20, 0, 0x20, 1, 0x6a); // add
        body(code, 0x20, 0); // i64 identity
        body(code, 0x20, 0); // f32 identity
        body(code, 0x20, 0); // f64 identity
        body(code, 0x20, 0, 0x2d, 0, 0); // i32.load8_u
        body(code, 0x20, 0, 0x20, 1, 0x3a, 0, 0); // i32.store8
        body(code, 0x3f, 0); // memory.size
        body(code, 0x20, 0, 0x40, 0); // memory.grow
        body(code, 0); // unreachable
        body(code, 0x03, 0x40, 0x0c, 0, 0x0b); // pure WASM loop { br 0 }
        if (callback) body(code, 0x20, 0, 0x10, 0);
        section(module, 10, code);
        return module.bytes();
    }

    private static void type(Bytes output, int[] parameters, int[] results) {
        output.raw(0x60).uleb(parameters.length).raw(parameters).uleb(results.length).raw(results);
    }

    private static void body(Bytes output, int... instructions) {
        Bytes body = new Bytes().raw(0).raw(instructions).raw(0x0b);
        output.uleb(body.bytes().length).raw(body.bytes());
    }

    private static void section(Bytes output, int id, Bytes contents) {
        output.raw(id).uleb(contents.bytes().length).raw(contents.bytes());
    }

    private static final class Bytes {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Bytes raw(int... bytes) { for (int value : bytes) output.write(value); return this; }
        Bytes raw(byte[] bytes) { output.write(bytes, 0, bytes.length); return this; }
        Bytes name(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return uleb(bytes.length).raw(bytes);
        }
        Bytes uleb(int value) {
            do {
                int next = value & 127;
                value >>>= 7;
                output.write(next | (value == 0 ? 0 : 128));
            } while (value != 0);
            return this;
        }
        byte[] bytes() { return output.toByteArray(); }
    }
}
