package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.ByteBufferMemory;
import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.runtime.StackFrame;
import com.dylibso.chicory.runtime.alloc.MemAllocStrategy;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.PassiveDataSegment;
import com.dylibso.chicory.wasm.types.ValType;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded, synchronous WASM interpreter. Operations on a runtime are serialized; cancellation
 * and close may be requested from another thread. Host functions remain synchronous and must
 * cooperate with cancellation themselves. Compile deadlines are checked during binary preflight
 * and before/after Chicory parsing, whose bounded parsing phase cannot be forcibly interrupted.
 */
public final class WasmRuntime implements AutoCloseable {

    public enum Type { I32, I64, F32, F64 }

    public enum Kind { FUNCTION, MEMORY, TABLE, GLOBAL, TAG }

    public record Signature(List<Type> params, List<Type> results) {
        public Signature {
            params = List.copyOf(params);
            results = List.copyOf(results);
        }
    }

    public record Import(String module, String name, Kind kind, Signature signature) { }

    public record Export(String name, Kind kind, Signature signature) { }

    @FunctionalInterface
    public interface HostFunction {
        Object[] call(Instance instance, Object[] arguments) throws Exception;
    }

    public static class CompileException extends RuntimeException {
        public CompileException(String message) { super(message); }
        CompileException(Throwable cause) { super(cause.getMessage(), cause); }
    }

    public static class LinkException extends RuntimeException {
        public LinkException(String message) { super(message); }
        LinkException(Throwable cause) { super(cause.getMessage(), cause); }
    }

    public static class TrapException extends RuntimeException {
        public TrapException(String message) { super(message); }
        TrapException(Throwable cause) { super(cause.getMessage(), cause); }
    }

    public static class LimitException extends RuntimeException {
        public LimitException(String message) { super(message); }
    }

    public static final int MAX_MODULE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_MEMORY_PAGES = 1024;
    private static final int MAX_TOTAL_MODULE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_MODULES = 32;
    private static final int MAX_INSTANCES = 64;
    private static final int MAX_MEMORIES = 64;
    private static final int MAX_TOTAL_MEMORY_PAGES = 2048;
    private static final long DEFAULT_TIMEOUT_MILLIS = 10000;
    private static final int PAGE_BYTES = com.dylibso.chicory.runtime.Memory.PAGE_SIZE;
    private static final int MAX_CALL_DEPTH = 128;
    private static final MemAllocStrategy EXACT_ALLOCATION = new MemAllocStrategy() {
        @Override public int initial(int minimum) { return minimum; }
        @Override public int next(int previous, int minimum) { return minimum; }
    };

    private interface Operation<T> { T run() throws Exception; }

    private static final class Signal extends RuntimeException {
        final Exception failure;
        Signal(Exception failure) {
            super(null, failure, false, false);
            this.failure = failure;
        }
    }

    private static final class HostFailure extends RuntimeException {
        final Exception failure;
        HostFailure(Exception failure) {
            super(null, failure, false, false);
            this.failure = failure;
        }
    }

    private final class Call {
        // Each outer operation owns a distinct cancellation token, so a late cancel cannot
        // leak into the next operation after this generation has unwound.
        final AtomicBoolean canceled = new AtomicBoolean();
        final Map<Instance, Integer> participants = new ConcurrentHashMap<>();
        long deadline;
        int instructions;
        int depth;
        Call(long deadline) { this.deadline = deadline; }
    }

    private static final class BoundedMachine extends InterpreterMachine {
        BoundedMachine(com.dylibso.chicory.runtime.Instance instance) { super(instance); }

        @Override
        protected long[] call(MStack stack, com.dylibso.chicory.runtime.Instance instance,
                              Deque<StackFrame> frames, int index, long[] arguments,
                              FunctionType type, boolean popResults) {
            if (frames.size() >= MAX_CALL_DEPTH) throw new LimitException("WASM call stack budget exhausted");
            return super.call(stack, instance, frames, index, arguments, type, popResults);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Runnable> cleanup = new ConcurrentLinkedQueue<>();
    private final Set<Module> modules = new HashSet<>();
    private final Set<Instance> instances = new HashSet<>();
    private final Set<Memory> memories = new HashSet<>();
    private volatile Call active;
    private volatile Runnable executionCheck = () -> { };
    private int moduleBytes;
    private int memoryPages;
    private int moduleInstructions;
    private int moduleLocals;
    private int moduleTypeSlots;
    private int moduleOperands;

    public void setExecutionCheck(Runnable check) {
        executionCheck = Objects.requireNonNull(check, "check");
    }

    public Module compile(byte[] binary) throws Exception {
        return compileTimed(DEFAULT_TIMEOUT_MILLIS, binary);
    }

    public Module compileTimed(long timeoutMillis, byte[] binary) throws Exception {
        Objects.requireNonNull(binary, "binary");
        return execute(timeoutMillis, () -> {
            if (binary.length > MAX_MODULE_BYTES || binary.length > MAX_TOTAL_MODULE_BYTES - moduleBytes
                    || modules.size() >= MAX_MODULES) throw new LimitException("WASM module budget exhausted");
            // Snapshot first: callers must not mutate lengths between preflight and parsing.
            byte[] snapshot = binary.clone();
            WasmBinaryGuard.Budget budget = WasmBinaryGuard.validate(snapshot, this::check);
            if (budget.instructions() > 500000 - moduleInstructions
                    || budget.locals() > 131072 - moduleLocals
                    || budget.typeSlots() > 131072 - moduleTypeSlots
                    || budget.operands() > 1000000 - moduleOperands) {
                throw new LimitException("WASM compiled module budget exhausted");
            }
            WasmModule parsed;
            try {
                // Custom metadata has separate parsers and is unnecessary for execution.
                Parser.Builder builder = Parser.builder();
                for (int id = 1; id <= 12; id++) builder.includeSectionId(id);
                parsed = builder.build().parse(() -> new ByteArrayInputStream(snapshot));
            } catch (RuntimeException error) {
                throw new CompileException(error);
            }
            check();
            Module module = new Module(parsed, snapshot.length, budget);
            modules.add(module);
            moduleBytes += snapshot.length;
            moduleInstructions += budget.instructions();
            moduleLocals += budget.locals();
            moduleTypeSlots += budget.typeSlots();
            moduleOperands += budget.operands();
            return module;
        }, true);
    }

    public Function function(Signature signature, HostFunction callback) {
        if (closed.get()) throw new IllegalStateException("WASM runtime is closed");
        return new Function(Objects.requireNonNull(signature), Objects.requireNonNull(callback), null, null);
    }

    public Instance instantiate(Module module) throws Exception {
        return instantiate(module, Map.of());
    }

    public Instance instantiate(Module module, Map<String, ? extends Map<String, ?>> imports) throws Exception {
        return instantiateTimed(DEFAULT_TIMEOUT_MILLIS, module, imports);
    }

    public Instance instantiateTimed(long timeoutMillis, Module module,
                                     Map<String, ? extends Map<String, ?>> imports) throws Exception {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(imports, "imports");
        return execute(timeoutMillis, () -> {
            requireOwner(module.owner());
            if (module.closed.get()) throw new IllegalStateException("WASM module is closed");
            if (instances.size() >= MAX_INSTANCES) throw new LimitException("WASM instance budget exhausted");
            Instance instance = new Instance(module);
            enter(instance);
            instances.add(instance);
            module.users++;
            try {
                ImportValues values = instance.link(imports);
                instance.backend = com.dylibso.chicory.runtime.Instance.builder(module.backend)
                        .withInitialize(false)
                        .withImportValues(values)
                        .withMemoryFactory(limits -> {
                            Memory memory = createMemory(limits.initialPages(),
                                    Math.min(limits.maximumPages(), MAX_MEMORY_PAGES), instance);
                            instance.memory = memory;
                            return memory.backend;
                        })
                        .withMachineFactory(owner -> {
                            instance.machine = new BoundedMachine(owner);
                            return (index, arguments) -> instance.machine.call(index, arguments);
                        })
                        .withUnsafeExecutionListener((instruction, stack) -> {
                            if (stack.size() > 65536) throw new LimitException("WASM operand stack budget exhausted");
                            poll();
                        })
                        .build();
                instance.backend.initialize(false);
                if (module.backend.startSection().isPresent()) {
                    int start = Math.toIntExact(module.backend.startSection().get().startIndex());
                    instance.backend.getMachine().call(start, new long[0]);
                }
                check();
                return instance;
            } catch (Exception | Error error) {
                instance.closed.set(true);
                instance.dispose();
                if (error instanceof ChicoryException) throw new TrapException(error);
                throw error;
            } finally {
                leave(instance);
            }
        }, true);
    }

    public Memory memory(int initialPages, int maximumPages) throws Exception {
        return execute(DEFAULT_TIMEOUT_MILLIS, () -> createMemory(initialPages, maximumPages, null), true);
    }

    private Memory createMemory(int initialPages, int maximumPages, Instance owner) {
        if (initialPages < 0 || maximumPages < initialPages) throw new IllegalArgumentException("Invalid WASM memory limits");
        if (maximumPages > MAX_MEMORY_PAGES || maximumPages > MAX_TOTAL_MEMORY_PAGES - memoryPages
                || memories.size() >= MAX_MEMORIES) throw new LimitException("WASM memory budget exhausted");
        Memory memory = new Memory(new ByteBufferMemory(new MemoryLimits(initialPages, maximumPages), EXACT_ALLOCATION), owner);
        memories.add(memory);
        // Reserve the maximum so guest memory.grow cannot bypass aggregate accounting.
        memoryPages += maximumPages;
        return memory;
    }

    public void cancel() {
        Call call = active;
        if (call != null) call.canceled.set(true);
    }

    public void checkCancellation() throws Exception {
        execute(DEFAULT_TIMEOUT_MILLIS, () -> null);
    }

    private void poll() {
        Call call = active;
        if (call != null && (++call.instructions & 1023) == 0) check();
    }

    private void check() {
        if (closed.get()) throw new IllegalStateException("WASM runtime is closed");
        if (Thread.currentThread().isInterrupted()) throw new Signal(new InterruptedException("WASM execution interrupted"));
        Call call = active;
        if (call != null) {
            if (call.canceled.get()) throw new CancellationException("WASM execution canceled");
            if (System.nanoTime() - call.deadline >= 0) throw new Signal(new TimeoutException("WASM execution deadline exceeded"));
            for (Instance participant : call.participants.keySet()) {
                if (participant.closed.get()) throw new IllegalStateException("WASM instance is closed");
            }
        }
        executionCheck.run();
    }

    private <T> T execute(long timeoutMillis, Operation<T> operation) throws Exception {
        return execute(timeoutMillis, operation, false);
    }

    private <T> T execute(long timeoutMillis, Operation<T> operation, boolean owned) throws Exception {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative WASM timeout");
        long timeout = timeoutMillis == 0 ? DEFAULT_TIMEOUT_MILLIS : timeoutMillis;
        // Limit durations to keep nanoTime subtraction valid across overflow.
        timeout = Math.min(timeout, TimeUnit.DAYS.toMillis(1));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
            if (!lock.tryLock(timeout, TimeUnit.MILLISECONDS)) throw new TimeoutException("WASM execution deadline exceeded");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
        boolean outer = active == null;
        if (outer) active = new Call(deadline);
        Call call = active;
        long previous = call.deadline;
        if (deadline - previous < 0) call.deadline = deadline;
        T result = null;
        boolean success = false;
        try {
            check();
            result = operation.run();
            check();
            success = true;
            return result;
        } catch (Signal error) {
            if (error.failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw error.failure;
        } catch (HostFailure error) {
            throw error.failure;
        } catch (ChicoryInterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedException(error.getMessage());
        } catch (ChicoryException error) {
            throw new TrapException(error);
        } finally {
            try {
                if (!success && owned && result instanceof AutoCloseable resource) resource.close();
            } finally {
                call.deadline = previous;
                try {
                    if (outer) {
                        active = null;
                        drainCleanup();
                    }
                } finally {
                    lock.unlock();
                    // Cover a close enqueued between the last sweep and unlocking.
                    tryCleanup();
                }
            }
        }
    }

    private void enter(Instance instance) {
        if (active.depth >= MAX_CALL_DEPTH) throw new LimitException("WASM reentry budget exhausted");
        active.depth++;
        active.participants.merge(instance, 1, Integer::sum);
    }

    private void leave(Instance instance) {
        active.depth--;
        active.participants.computeIfPresent(instance, (key, count) -> count == 1 ? null : count - 1);
    }

    private void requireOwner(WasmRuntime owner) {
        if (owner != this) throw new LinkException("WASM values belong to another runtime");
    }

    private void scheduleCleanup(Runnable action) {
        cleanup.add(action);
        tryCleanup();
    }

    private void tryCleanup() {
        if (cleanup.isEmpty() || !lock.tryLock()) return;
        try {
            if (active == null) drainCleanup();
        } finally {
            lock.unlock();
        }
    }

    private void drainCleanup() {
        for (Runnable action; (action = cleanup.poll()) != null; ) action.run();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancel();
        scheduleCleanup(() -> {
            for (Instance instance : new ArrayList<>(instances)) instance.dispose();
            for (Memory memory : new ArrayList<>(memories)) memory.dispose();
            for (Module module : new ArrayList<>(modules)) module.dispose();
            executionCheck = () -> { };
        });
    }

    private static Signature signature(FunctionType type) {
        return new Signature(type.params().stream().map(WasmRuntime::type).toList(),
                type.returns().stream().map(WasmRuntime::type).toList());
    }

    private static Type type(ValType type) {
        if (type.equals(ValType.I32)) return Type.I32;
        if (type.equals(ValType.I64)) return Type.I64;
        if (type.equals(ValType.F32)) return Type.F32;
        if (type.equals(ValType.F64)) return Type.F64;
        throw new CompileException("Only scalar WASM numeric types are supported");
    }

    private static long[] encode(List<Type> types, Object[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (types.size() != arguments.length) throw new IllegalArgumentException("Invalid WASM argument count");
        long[] result = new long[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            Object value = arguments[i];
            result[i] = switch (types.get(i)) {
                case I32 -> {
                    if (!(value instanceof Integer)) throw new IllegalArgumentException("i32 requires Integer");
                    yield (Integer) value;
                }
                case I64 -> {
                    if (!(value instanceof Long)) throw new IllegalArgumentException("i64 requires Long");
                    yield (Long) value;
                }
                case F32 -> {
                    if (!(value instanceof Float)) throw new IllegalArgumentException("f32 requires Float");
                    yield Float.floatToRawIntBits((Float) value);
                }
                case F64 -> {
                    if (!(value instanceof Double)) throw new IllegalArgumentException("f64 requires Double");
                    yield Double.doubleToRawLongBits((Double) value);
                }
            };
        }
        return result;
    }

    private static Object[] decode(List<Type> types, long[] values) {
        if (values == null) values = new long[0];
        if (types.size() != values.length) throw new TrapException("Invalid WASM result count");
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = switch (types.get(i)) {
                case I32 -> (int) values[i];
                case I64 -> values[i];
                case F32 -> Float.intBitsToFloat((int) values[i]);
                case F64 -> Double.longBitsToDouble(values[i]);
            };
        }
        return result;
    }

    public final class Module implements AutoCloseable {

        private WasmModule backend;
        private final int bytes;
        private final WasmBinaryGuard.Budget budget;
        private final boolean passiveData;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<Import> imports;
        private final List<Export> exports;
        private final List<Signature> functions = new ArrayList<>();
        private int users = 1;

        private Module(WasmModule backend, int bytes, WasmBinaryGuard.Budget budget) {
            this.backend = backend;
            this.bytes = bytes;
            this.budget = budget;
            boolean passive = false;
            for (int i = 0; i < backend.dataSection().dataSegmentCount(); i++) {
                passive |= backend.dataSection().getDataSegment(i) instanceof PassiveDataSegment;
            }
            passiveData = passive;
            List<Import> imported = new ArrayList<>();
            backend.importSection().stream().forEach(value -> {
                Signature signature = value instanceof FunctionImport function
                        ? WasmRuntime.signature(backend.typeSection().getType(function.typeIndex())) : null;
                imported.add(new Import(value.module(), value.name(), Kind.valueOf(value.importType().name()), signature));
                if (signature != null) functions.add(signature);
            });
            for (int i = 0; i < backend.functionSection().functionCount(); i++) {
                functions.add(WasmRuntime.signature(backend.functionSection().getFunctionType(i, backend.typeSection())));
            }
            imports = List.copyOf(imported);
            List<Export> exported = new ArrayList<>();
            for (int i = 0; i < backend.exportSection().exportCount(); i++) {
                var value = backend.exportSection().getExport(i);
                exported.add(new Export(value.name(), Kind.valueOf(value.exportType().name()),
                        value.exportType() == ExternalType.FUNCTION ? functions.get(value.index()) : null));
            }
            exports = List.copyOf(exported);
        }

        private WasmRuntime owner() { return WasmRuntime.this; }

        public List<Import> imports() { return imports; }

        public List<Export> exports() { return exports; }

        public Signature signature(String name) {
            for (Export export : exports) {
                if (export.name().equals(name) && export.kind() == Kind.FUNCTION) return export.signature();
            }
            throw new IllegalArgumentException("Unknown WASM function: " + name);
        }

        private void release() {
            if (--users == 0) dispose();
        }

        private void dispose() {
            if (backend == null) return;
            modules.remove(this);
            moduleBytes -= bytes;
            moduleInstructions -= budget.instructions();
            moduleLocals -= budget.locals();
            moduleTypeSlots -= budget.typeSlots();
            moduleOperands -= budget.operands();
            backend = null;
            closed.set(true);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) scheduleCleanup(this::release);
        }
    }

    public final class Function {

        private final Signature signature;
        private final HostFunction callback;
        private final Instance instance;
        private final String name;

        private Function(Signature signature, HostFunction callback, Instance instance, String name) {
            this.signature = signature;
            this.callback = callback;
            this.instance = instance;
            this.name = name;
        }

        private WasmRuntime owner() { return WasmRuntime.this; }

        public Signature signature() { return signature; }

        public Object[] call(Object... arguments) throws Exception {
            if (instance == null) throw new IllegalStateException("Host functions are called by a WASM instance");
            return instance.call(name, arguments);
        }
    }

    public final class Instance implements AutoCloseable {

        private Module module;
        private com.dylibso.chicory.runtime.Instance backend;
        private InterpreterMachine machine;
        private Memory memory;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Instance(Module module) { this.module = module; }

        private ImportValues link(Map<String, ? extends Map<String, ?>> imports) {
            ImportValues.Builder builder = ImportValues.builder();
            for (int i = 0; i < module.backend.importSection().importCount(); i++) {
                var spec = module.backend.importSection().getImport(i);
                Map<String, ?> namespace = imports.get(spec.module());
                Object value = namespace == null ? null : namespace.get(spec.name());
                if (spec instanceof FunctionImport function) {
                    Signature expected = WasmRuntime.signature(module.backend.typeSection().getType(function.typeIndex()));
                    if (!(value instanceof Function supplied)) throw new LinkException("Missing WASM function import: " + spec.module() + "." + spec.name());
                    requireOwner(supplied.owner());
                    if (!expected.equals(supplied.signature)) throw new LinkException("WASM function import signature mismatch");
                    if (supplied.instance != null) supplied.instance.requireOpen();
                    builder.addFunction(new ImportFunction(spec.module(), spec.name(),
                            module.backend.typeSection().getType(function.typeIndex()), (owner, arguments) -> {
                        check();
                        try {
                            Object[] decoded = decode(expected.params(), arguments);
                            Object[] result = supplied.instance == null ? supplied.callback.call(this, decoded) : supplied.call(decoded);
                            check();
                            if (result == null) throw new IllegalArgumentException("WASM imports require synchronous Object[] results");
                            return encode(expected.results(), result);
                        } catch (Exception error) {
                            if (error instanceof Signal signal) throw signal;
                            throw new HostFailure(error);
                        }
                    }));
                } else if (spec instanceof MemoryImport imported) {
                    if (!(value instanceof Memory supplied)) throw new LinkException("Missing WASM memory import: " + spec.module() + "." + spec.name());
                    requireOwner(supplied.owner());
                    supplied.requireOpen();
                    // Chicory 1.4 keeps passive segments on the memory object, so sharing it
                    // across these modules would silently replace another instance's segments.
                    if (module.passiveData || supplied.passiveData) {
                        throw new LinkException("Passive data segments with imported WASM memory are unsupported");
                    }
                    if (supplied.backend.pages() < imported.limits().initialPages()
                            || supplied.maximumPages > imported.limits().maximumPages()) throw new LinkException("WASM memory import limits mismatch");
                    supplied.users++;
                    memory = supplied;
                    builder.addMemory(new ImportMemory(spec.module(), spec.name(), supplied.backend));
                } else throw new LinkException("Unsupported WASM import: " + spec.importType());
            }
            return builder.build();
        }

        private void requireOpen() {
            if (closed.get() || backend == null) throw new IllegalStateException("WASM instance is closed");
        }

        public Object[] call(String name, Object... arguments) throws Exception {
            return callTimed(DEFAULT_TIMEOUT_MILLIS, name, arguments);
        }

        public Object[] callTimed(long timeoutMillis, String name, Object... arguments) throws Exception {
            return execute(timeoutMillis, () -> {
                requireOpen();
                Signature signature = module.signature(name);
                long[] encoded = encode(signature.params(), arguments);
                enter(this);
                boolean success = false;
                try {
                    Object[] result = decode(signature.results(), backend.export(name).apply(encoded));
                    check();
                    success = true;
                    return result;
                } finally {
                    leave(this);
                    // Chicory 1.4 can leave interpreter stacks populated after an external trap.
                    if (!success) machine = new BoundedMachine(backend);
                }
            });
        }

        public Function function(String name) throws Exception {
            return execute(DEFAULT_TIMEOUT_MILLIS, () -> {
                requireOpen();
                return new Function(module.signature(name), null, this, name);
            });
        }

        public Memory memory() throws Exception { return memory(null); }

        public Memory memory(String name) throws Exception {
            return execute(DEFAULT_TIMEOUT_MILLIS, () -> {
                requireOpen();
                if (memory == null) throw new IllegalArgumentException("Missing WASM memory");
                if (name != null) {
                    boolean found = module.exports.stream().anyMatch(export -> export.kind() == Kind.MEMORY && export.name().equals(name));
                    if (!found) throw new IllegalArgumentException("Unknown WASM memory: " + name);
                }
                memory.requireOpen();
                return memory;
            });
        }

        public void cancel() {
            Call call = active;
            if (call != null && call.participants.containsKey(this)) WasmRuntime.this.cancel();
        }

        private void dispose() {
            if (module == null) return;
            instances.remove(this);
            backend = null;
            machine = null;
            if (memory != null) {
                memory.release();
                memory = null;
            }
            module.release();
            module = null;
            closed.set(true);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) scheduleCleanup(this::dispose);
        }
    }

    public final class Memory implements AutoCloseable {

        private ByteBufferMemory backend;
        private final int maximumPages;
        private final Instance instance;
        private final boolean passiveData;
        private final AtomicBoolean closed = new AtomicBoolean();
        private int users = 1;

        private Memory(ByteBufferMemory backend, Instance instance) {
            this.backend = backend;
            this.maximumPages = backend.maximumPages();
            this.instance = instance;
            passiveData = instance != null && instance.module.passiveData;
        }

        private WasmRuntime owner() { return WasmRuntime.this; }

        private void requireOpen() {
            if (closed.get() || backend == null) throw new IllegalStateException("WASM memory is closed");
        }

        public int pages() throws Exception {
            return execute(DEFAULT_TIMEOUT_MILLIS, () -> {
                requireOpen();
                return backend.pages();
            });
        }

        public byte[] read(int offset, int length) throws Exception {
            return execute(DEFAULT_TIMEOUT_MILLIS, () -> {
                requireOpen();
                range(offset, length);
                return backend.readBytes(offset, length);
            });
        }

        public void write(int offset, byte[] bytes) throws Exception {
            Objects.requireNonNull(bytes, "bytes");
            execute(DEFAULT_TIMEOUT_MILLIS, () -> {
                requireOpen();
                range(offset, bytes.length);
                backend.write(offset, bytes);
                return null;
            });
        }

        public int grow(int pages) throws Exception {
            return execute(DEFAULT_TIMEOUT_MILLIS, () -> {
                requireOpen();
                if (pages < 0) throw new IllegalArgumentException("Negative WASM memory growth");
                return backend.grow(pages);
            });
        }

        private void range(int offset, int length) {
            int size = backend.pages() * PAGE_BYTES;
            if (offset < 0 || length < 0 || offset > size || length > size - offset) throw new IndexOutOfBoundsException("WASM memory access is out of bounds");
        }

        private void release() {
            if (--users == 0) dispose();
        }

        private void dispose() {
            if (backend == null) return;
            memories.remove(this);
            memoryPages -= maximumPages;
            backend = null;
            closed.set(true);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && instance == null) scheduleCleanup(this::release);
        }
    }
}
