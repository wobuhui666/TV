package com.fongmi.quickjs;

import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.WasmEncoding;

/** Checks allocation-driving lengths before handing an untrusted binary to Chicory's parser. */
final class WasmBinaryGuard {

    private static final int MAX_VECTOR = 4096;
    private static final int MAX_SIGNATURE = 256;
    private static final int MAX_LOCALS = 8192;
    private static final int MAX_TOTAL_LOCALS = 65536;
    private static final int MAX_INSTRUCTIONS = 250000;
    private static final int MAX_TABLE = 32768;
    private static final int MAX_DEPTH = 256;

    private final Runnable check;
    private int instructions;
    private int operands;
    private int totalLocals;
    private int memories;
    private int typeSlots;
    private boolean unboundedTable;
    private boolean tableGrow;

    private WasmBinaryGuard(Runnable check) {
        this.check = check;
    }

    record Budget(int instructions, int locals, int typeSlots, int operands) { }

    static Budget validate(byte[] binary, Runnable check) {
        WasmBinaryGuard guard = new WasmBinaryGuard(check);
        guard.module(new Cursor(binary, 0, binary.length));
        return new Budget(guard.instructions, guard.totalLocals, guard.typeSlots, guard.operands);
    }

    private void module(Cursor input) {
        if (input.byteValue() != 0 || input.byteValue() != 0x61 || input.byteValue() != 0x73
                || input.byteValue() != 0x6d || input.byteValue() != 1
                || input.byteValue() != 0 || input.byteValue() != 0 || input.byteValue() != 0) {
            throw invalid("Invalid WASM magic or version");
        }
        boolean[] seen = new boolean[14];
        int sections = 0;
        while (input.remaining() > 0) {
            if (++sections > MAX_VECTOR) throw limit("WASM section budget exhausted");
            check.run();
            int id = input.byteValue();
            if (id > 13) throw invalid("Unknown WASM section");
            Cursor section = input.slice(input.length(input.remaining()));
            if (id != 0 && seen[id]) throw invalid("Duplicate WASM section");
            seen[id] = true;
            switch (id) {
                case 0 -> {
                    section.name();
                    section.skip(section.remaining());
                }
                case 1 -> types(section);
                case 2 -> imports(section);
                case 3 -> indices(section);
                case 4 -> tables(section);
                case 5 -> {
                    int count = section.count(1);
                    memories += count;
                    if (memories > 1) throw invalid("Multiple WASM memories are unsupported");
                    for (int i = 0; i < count; i++) memoryLimits(section);
                }
                case 6 -> globals(section);
                case 7 -> exports(section);
                case 8 -> section.unsigned();
                case 9 -> elements(section);
                case 10 -> code(section);
                case 11 -> data(section);
                case 12 -> section.length(MAX_VECTOR);
                case 13 -> throw invalid("WASM exception tags are unsupported");
                default -> throw invalid("Unknown WASM section");
            }
            if (section.remaining() != 0) throw invalid("WASM section length mismatch");
        }
        if (unboundedTable && tableGrow) {
            throw invalid("Growing an unbounded WASM table is unsupported");
        }
    }

    private void types(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) {
            if (input.byteValue() != 0x60) throw invalid("Only WASM function types are supported");
            int params = input.count(MAX_SIGNATURE);
            for (int p = 0; p < params; p++) input.scalar();
            int results = input.count(MAX_SIGNATURE);
            for (int r = 0; r < results; r++) input.scalar();
            typeSlots += params + results;
            if (typeSlots > MAX_TOTAL_LOCALS) throw limit("WASM signature budget exhausted");
        }
    }

    private void imports(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) {
            input.name();
            input.name();
            int kind = input.byteValue();
            if (kind == 0) input.unsigned();
            else if (kind == 2) {
                if (++memories > 1) throw invalid("Multiple WASM memories are unsupported");
                memoryLimits(input);
            } else throw invalid("Only WASM function and memory imports are supported");
        }
    }

    private void memoryLimits(Cursor input) {
        int flags = input.byteValue();
        if (flags > 1) throw invalid("Shared and 64-bit WASM memories are unsupported");
        long initial = input.unsigned();
        if (initial > WasmRuntime.MAX_MEMORY_PAGES) throw limit("WASM memory budget exhausted");
        if (flags == 1 && input.unsigned() < initial) throw invalid("Invalid WASM memory limits");
    }

    private void tables(Cursor input) {
        int count = input.count(8);
        int total = 0;
        for (int i = 0; i < count; i++) {
            if (input.byteValue() != 0x70) throw invalid("Only internal funcref WASM tables are supported");
            int flags = input.byteValue();
            if (flags > 1) throw invalid("Unsupported WASM table limits");
            int initial = input.length(MAX_TABLE);
            total += initial;
            if (total > MAX_TABLE) throw limit("WASM table budget exhausted");
            if (flags == 1) {
                int maximum = input.length(MAX_TABLE);
                if (maximum < initial) throw invalid("Invalid WASM table limits");
                total += maximum - initial;
                if (total > MAX_TABLE) throw limit("WASM table budget exhausted");
            } else unboundedTable = true;
        }
    }

    private void globals(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) {
            input.scalar();
            if (input.byteValue() > 1) throw invalid("Invalid WASM global mutability");
            expression(input);
        }
    }

    private void indices(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) input.unsigned();
    }

    private void exports(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) {
            input.name();
            if (input.byteValue() > 3) throw invalid("Unsupported WASM export");
            input.unsigned();
        }
    }

    private void elements(Cursor input) {
        int count = input.count(MAX_VECTOR);
        int total = 0;
        for (int i = 0; i < count; i++) {
            int flags = input.length(7);
            boolean active = (flags & 1) == 0;
            boolean explicitTable = active && (flags & 2) != 0;
            boolean expressions = (flags & 4) != 0;
            if (active) {
                if (explicitTable) input.unsigned();
                expression(input);
            }
            if (!active || explicitTable) {
                int kind = input.byteValue();
                if (kind != (expressions ? 0x70 : 0)) throw invalid("Unsupported WASM element type");
            }
            int size = input.count(MAX_TABLE);
            if ((total += size) > MAX_TABLE) throw limit("WASM element budget exhausted");
            for (int j = 0; j < size; j++) {
                if (expressions) expression(input);
                else input.unsigned();
            }
        }
    }

    private void code(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) {
            check.run();
            Cursor body = input.slice(input.length(input.remaining()));
            int groups = body.count(MAX_LOCALS);
            int locals = 0;
            for (int j = 0; j < groups; j++) {
                locals += body.length(MAX_LOCALS);
                if (locals > MAX_LOCALS) throw limit("WASM local budget exhausted");
                body.scalar();
            }
            totalLocals += locals;
            if (totalLocals > MAX_TOTAL_LOCALS) throw limit("WASM local budget exhausted");
            expression(body);
            if (body.remaining() != 0) throw invalid("WASM function body length mismatch");
        }
    }

    private void data(Cursor input) {
        int count = input.count(MAX_VECTOR);
        for (int i = 0; i < count; i++) {
            long mode = input.unsigned();
            if (mode == 0) expression(input);
            else if (mode == 2) {
                input.unsigned();
                expression(input);
            } else if (mode != 1) throw invalid("Invalid WASM data mode");
            input.skip(input.length(input.remaining()));
        }
    }

    private void expression(Cursor input) {
        int depth = 1;
        while (depth > 0) {
            if (++instructions > MAX_INSTRUCTIONS) throw limit("WASM instruction budget exhausted");
            if ((instructions & 1023) == 0) check.run();
            int opcode = input.byteValue();
            if (opcode == 0xfe || opcode == 0xfb || opcode == 0xfd) {
                throw invalid("WASM atomics, GC and SIMD instructions are unsupported");
            }
            if (opcode == 0xfc) opcode = (opcode << 8) + input.length(255);
            OpCode op = OpCode.byOpCode(opcode);
            if (op == null) throw invalid("Invalid WASM instruction");
            if (op == OpCode.BLOCK || op == OpCode.LOOP || op == OpCode.IF) {
                if (++depth > MAX_DEPTH) throw limit("WASM control nesting budget exhausted");
            } else if (op == OpCode.END) depth--;
            else if (op == OpCode.TABLE_GROW) tableGrow = true;
            if (op == OpCode.MEMORY_SIZE || op == OpCode.MEMORY_GROW) {
                if (input.byteValue() != 0) throw invalid("Invalid WASM memory index");
            }
            for (WasmEncoding encoding : OpCode.signature(op)) {
                operandCount(1);
                switch (encoding) {
                    case BYTE -> input.byteValue();
                    case VARUINT -> input.unsigned();
                    case VARSINT32 -> input.signed(5);
                    case VARSINT64 -> input.signed(10);
                    case FLOAT32 -> input.skip(4);
                    case FLOAT64 -> input.skip(8);
                    case BLOCK_TYPE -> input.signed(5);
                    case VEC_VARUINT -> {
                        int count = input.count(MAX_VECTOR);
                        operandCount(count);
                        for (int i = 0; i < count; i++) input.unsigned();
                    }
                    case VEC_VALUE_TYPE -> {
                        int count = input.count(MAX_SIGNATURE);
                        operandCount(count);
                        for (int i = 0; i < count; i++) input.scalar();
                    }
                    case V128, VEC_CATCH -> throw invalid("Unsupported WASM instruction operands");
                }
            }
        }
    }

    private void operandCount(int count) {
        operands += count;
        if (operands > 500000) throw limit("WASM instruction operand budget exhausted");
    }

    private static WasmRuntime.CompileException invalid(String message) {
        return new WasmRuntime.CompileException(message);
    }

    private static WasmRuntime.LimitException limit(String message) {
        return new WasmRuntime.LimitException(message);
    }

    private static final class Cursor {

        private final byte[] bytes;
        private final int end;
        private int position;

        Cursor(byte[] bytes, int position, int end) {
            this.bytes = bytes;
            this.position = position;
            this.end = end;
        }

        int remaining() {
            return end - position;
        }

        int byteValue() {
            if (position == end) throw invalid("Truncated WASM binary");
            return bytes[position++] & 0xff;
        }

        long unsigned() {
            long result = 0;
            for (int i = 0; i < 5; i++) {
                int value = byteValue();
                if (i == 4 && (value & 0xf0) != 0) throw invalid("Invalid WASM unsigned integer");
                result |= (long) (value & 0x7f) << (7 * i);
                if ((value & 0x80) == 0) return result;
            }
            throw invalid("Invalid WASM unsigned integer");
        }

        void signed(int maximumBytes) {
            for (int i = 0; i < maximumBytes; i++) {
                if ((byteValue() & 0x80) == 0) return;
            }
            throw invalid("Invalid WASM signed integer");
        }

        int length(int maximum) {
            long value = unsigned();
            if (value > maximum) throw limit("WASM declared allocation exceeds its budget");
            return (int) value;
        }

        int count(int maximum) {
            int count = length(maximum);
            if (count > remaining()) throw invalid("Truncated WASM vector");
            return count;
        }

        void scalar() {
            int value = byteValue();
            if (value < 0x7c || value > 0x7f) throw invalid("Only scalar WASM numeric types are supported");
        }

        void name() {
            skip(length(Math.min(65536, remaining())));
        }

        void skip(int count) {
            if (count < 0 || count > remaining()) throw invalid("Truncated WASM binary");
            position += count;
        }

        Cursor slice(int count) {
            if (count < 0 || count > remaining()) throw invalid("Truncated WASM section");
            Cursor result = new Cursor(bytes, position, position + count);
            position += count;
            return result;
        }
    }
}
