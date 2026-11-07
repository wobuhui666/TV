package com.fongmi.quickjs;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class WasmRuntimeTest {

    @Test
    public void shouldExecuteFunctionsImportsAndExposeSignatures() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime))) {
            assertEquals("env", module.imports().get(0).module());
            assertEquals("add", module.imports().get(0).name());
            assertEquals(List.of(WasmRuntime.Type.I32, WasmRuntime.Type.I32), module.signature("calc").params());
            assertArrayEquals(new Object[]{42}, instance.call("calc", 19, 23));
            assertArrayEquals(new Object[]{42}, instance.function("calc").call(20, 22));
            assertArrayEquals(new Object[]{42, 7L}, instance.call("multi"));
            module.close();
            assertArrayEquals(new Object[]{42}, instance.call("calc", 1, 41));
            assertThrows(IllegalStateException.class, () -> runtime.instantiate(module, add(runtime)));
        }
    }

    @Test
    public void shouldPreserveI64AndFloatingPointBits() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime))) {
            for (long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 1L, 9007199254740993L}) {
                assertEquals(value, instance.call("i64", value)[0]);
            }
            for (float value : new float[]{-0.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits((Float) instance.call("f32", value)[0]));
            }
            for (double value : new double[]{-0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) instance.call("f64", value)[0]));
            }
            assertThrows(IllegalArgumentException.class, () -> instance.call("i64", 1));
            assertThrows(IllegalArgumentException.class, () -> instance.call("f32", 1.0));
            assertThrows(IllegalArgumentException.class, () -> instance.call("calc", 1));
        }
    }

    @Test
    public void shouldReadWriteAndBoundGuestMemoryGrowth() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime))) {
            WasmRuntime.Memory memory = instance.memory();
            assertSame(memory, instance.memory("memory"));
            memory.write(7, new byte[]{1, 2, 3});
            assertArrayEquals(new byte[]{1, 2, 3}, memory.read(7, 3));
            assertArrayEquals(new Object[]{1}, instance.call("grow", 1));
            assertEquals(2, memory.pages());
            assertArrayEquals(new Object[]{-1}, instance.call("grow", 1));
            assertThrows(IndexOutOfBoundsException.class, () -> memory.read(Integer.MAX_VALUE, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> memory.write(131072, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> instance.memory("missing"));
        }
    }

    @Test
    public void shouldShareImportedMemoryAndRetainItAcrossInstanceClose() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(memoryFixture());
             WasmRuntime.Memory memory = runtime.memory(1, 2);
             WasmRuntime.Instance first = runtime.instantiate(module, Map.of("env", Map.of("memory", memory)));
             WasmRuntime.Instance second = runtime.instantiate(module, Map.of("env", Map.of("memory", memory)))) {
            assertSame(memory, first.memory());
            assertSame(memory, second.memory());
            assertArrayEquals(new Object[]{77}, first.call("write", 10, 77));
            assertArrayEquals(new Object[]{77}, second.call("read", 10));
            first.close();
            assertArrayEquals(new Object[]{77}, second.call("read", 10));
            memory.close();
            assertArrayEquals(new Object[]{77}, second.call("read", 10));
            assertThrows(IllegalStateException.class, () -> memory.read(0, 1));
        }
    }

    @Test
    public void shouldCallImportsWithInstanceMemoryAndPreserveHostFailures() throws Exception {
        IOException failure = new IOException("host failed");
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2))) {
            WasmRuntime.Function function = runtime.function(module.imports().get(0).signature(), (instance, args) -> {
                instance.memory().write(3, new byte[]{8});
                throw failure;
            });
            try (WasmRuntime.Instance instance = runtime.instantiate(module, Map.of("env", Map.of("add", function)))) {
                assertSame(failure, assertThrows(IOException.class, () -> instance.call("calc", 1, 2)));
                assertArrayEquals(new byte[]{8}, instance.memory().read(3, 1));
                assertArrayEquals(new Object[]{123L}, instance.call("i64", 123L));
            }
        }
    }

    @Test
    public void shouldAllowSynchronousImportToReenterAnExport() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2))) {
            WasmRuntime.Function function = runtime.function(module.imports().get(0).signature(), (instance, args) -> {
                assertArrayEquals(new Object[]{17L}, instance.call("i64", 17L));
                return new Object[]{(Integer) args[0] + (Integer) args[1]};
            });
            try (WasmRuntime.Instance instance = runtime.instantiate(module, Map.of("env", Map.of("add", function)))) {
                assertArrayEquals(new Object[]{42}, instance.call("calc", 21, 21));
                assertArrayEquals(new Object[]{19L}, instance.call("i64", 19L));
            }
        }
    }

    @Test
    public void shouldBoundRecursiveGuestCallsAndRemainUsable() throws Exception {
        byte[] binary = module(section(1, vector(bytes(0x60, 0, 0))), section(3, vector(u(0))),
                section(7, vector(export("recursive", 0, 0))),
                section(10, code(bytes(0, 0x10, 0, 0x0b))));
        try (WasmRuntime runtime = new WasmRuntime(); WasmRuntime.Module module = runtime.compile(binary);
             WasmRuntime.Instance instance = runtime.instantiate(module)) {
            assertThrows(WasmRuntime.LimitException.class, () -> instance.call("recursive"));
        }
    }

    @Test
    public void shouldRejectMissingMismatchedAndForeignImports() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime(); WasmRuntime other = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2))) {
            assertThrows(WasmRuntime.LinkException.class, () -> runtime.instantiate(module));
            var wrong = runtime.function(new WasmRuntime.Signature(List.of(), List.of()), (instance, args) -> new Object[0]);
            assertThrows(WasmRuntime.LinkException.class, () -> runtime.instantiate(module, Map.of("env", Map.of("add", wrong))));
            assertThrows(WasmRuntime.LinkException.class, () -> runtime.instantiate(module, add(other)));
            assertThrows(WasmRuntime.LinkException.class, () -> other.instantiate(module, add(other)));
        }
    }

    @Test
    public void shouldStopInfiniteLoopAtDeadlineAndRemainUsableAfterTraps() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime))) {
            assertThrows(TimeoutException.class, () -> instance.callTimed(30, "loop"));
            assertArrayEquals(new Object[]{42}, instance.call("calc", 40, 2));
            assertThrows(WasmRuntime.TrapException.class, () -> instance.call("trap"));
            assertArrayEquals(new Object[]{42}, instance.call("calc", 41, 1));
        }
    }

    @Test
    public void shouldCancelFromAnotherThreadAndAllowTheNextCall() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime, entered::countDown))) {
            assertEquals("Instantiation must not signal guest execution", 1L, entered.getCount());
            var result = executor.submit(() -> instance.call("loop"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            instance.cancel();
            ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof CancellationException);
            assertArrayEquals(new Object[]{42}, instance.call("calc", 39, 3));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldCloseRuntimeFromAnotherThreadWithoutWaitingForLoop() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime, entered::countDown))) {
            assertEquals("Instantiation must not signal guest execution", 1L, entered.getCount());
            var result = executor.submit(() -> instance.call("loop"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runtime.close();
            ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IllegalStateException);
            assertThrows(IllegalStateException.class, () -> runtime.compile(fixture(false, 1, 2)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldObserveExecutionCheckAndPreserveThreadInterrupt() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(fixture(false, 1, 2));
             WasmRuntime.Instance instance = runtime.instantiate(module, add(runtime))) {
            runtime.setExecutionCheck(() -> { throw new CancellationException("source ended"); });
            assertThrows(CancellationException.class, () -> instance.call("calc", 1, 2));
            runtime.setExecutionCheck(() -> { });
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class, () -> instance.call("calc", 1, 2));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertArrayEquals(new Object[]{42}, instance.call("calc", 1, 41));
        }
    }

    @Test
    public void shouldReleaseMemoryBudgetAfterCloseAndFailedInitialization() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime()) {
            WasmRuntime.Memory first = runtime.memory(0, 1024);
            WasmRuntime.Memory second = runtime.memory(0, 1024);
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.memory(0, 1));
            first.close();
            second.close();
            try (WasmRuntime.Module module = runtime.compile(fixture(true, 0, 1024))) {
                for (int i = 0; i < 4; i++) {
                    assertThrows(WasmRuntime.TrapException.class, () -> runtime.instantiate(module, add(runtime)));
                }
            }
            try (WasmRuntime.Memory recovered = runtime.memory(0, 1024)) {
                assertEquals(0, recovered.pages());
            }
        }
    }

    @Test
    public void shouldBoundLiveModuleAndInstanceCounts() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime()) {
            WasmRuntime.Module[] modules = new WasmRuntime.Module[32];
            for (int i = 0; i < modules.length; i++) modules[i] = runtime.compile(emptyModule());
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.compile(emptyModule()));
            modules[0].close();
            try (WasmRuntime.Module module = runtime.compile(emptyModule())) {
                WasmRuntime.Instance[] instances = new WasmRuntime.Instance[64];
                for (int i = 0; i < instances.length; i++) instances[i] = runtime.instantiate(module);
                assertThrows(WasmRuntime.LimitException.class, () -> runtime.instantiate(module));
                instances[0].close();
                runtime.instantiate(module).close();
            }
        }
    }

    @Test
    public void shouldRejectOversizedParserAllocationsBeforeParsing() throws Exception {
        try (WasmRuntime runtime = new WasmRuntime()) {
            assertThrows(WasmRuntime.CompileException.class, () -> runtime.compile(new byte[]{0}));
            byte[] hugeParameters = concat(emptyModule(), section(1, concat(u(1), new byte[]{0x60}, u(Integer.MAX_VALUE))));
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.compile(hugeParameters));
            byte[] hugeData = concat(emptyModule(), section(11, concat(u(1), u(1), u(Integer.MAX_VALUE))));
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.compile(hugeData));
            byte[] locals = concat(u(2), u(8192), new byte[]{0x7f}, u(8192), new byte[]{0x7f, 0x0b});
            byte[] hugeLocals = module(section(1, vector(bytes(0x60, 0, 0))), section(3, vector(u(0))),
                    section(10, vector(concat(u(locals.length), locals))));
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.compile(hugeLocals));
            byte[] sharedMemory = module(section(5, vector(bytes(3, 1, 1))));
            assertThrows(WasmRuntime.CompileException.class, () -> runtime.compile(sharedMemory));
            byte[] foreignGlobal = module(section(2, vector(concat(name("env"), name("global"), bytes(3, 0x7f, 0)))));
            assertThrows(WasmRuntime.CompileException.class, () -> runtime.compile(foreignGlobal));
            byte[] branch = concat(bytes(0, 0x0e), u(Integer.MAX_VALUE), bytes(0, 0x0b));
            byte[] hugeBranch = module(section(1, vector(bytes(0x60, 0, 0))), section(3, vector(u(0))),
                    section(10, code(branch)));
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.compile(hugeBranch));
            byte[] largeMemory = module(section(5, vector(concat(u(0), u(2048)))));
            assertThrows(WasmRuntime.LimitException.class, () -> runtime.compile(largeMemory));
        }
    }

    @Test
    public void shouldSkipCustomMetadataParsingAndAcceptDataCount() throws Exception {
        // Debug metadata is opaque to execution: do not let a custom name parser allocate from
        // an attacker-controlled subsection count. The data-count section contains a count only.
        byte[] binary = module(section(0, concat(name("name"), bytes(1), u(5), u(Integer.MAX_VALUE))),
                section(5, vector(bytes(1, 1, 1))), section(12, u(1)),
                section(11, vector(bytes(1, 2, 8, 9))));
        try (WasmRuntime runtime = new WasmRuntime(); WasmRuntime.Module module = runtime.compile(binary);
             WasmRuntime.Instance instance = runtime.instantiate(module)) {
            assertEquals(1, instance.memory().pages());
        }
    }

    @Test
    public void shouldRejectUnboundedInternalTableGrowth() throws Exception {
        byte[] binary = module(section(1, vector(bytes(0x60, 0, 1, 0x7f))), section(3, vector(u(0))),
                section(4, vector(bytes(0x70, 0, 1))),
                section(10, code(bytes(0, 0xd0, 0x70, 0x41, 1, 0xfc, 0x0f, 0, 0x0b))));
        try (WasmRuntime runtime = new WasmRuntime()) {
            assertThrows(WasmRuntime.CompileException.class, () -> runtime.compile(binary));
        }
    }

    @Test
    public void shouldRejectPassiveSegmentsWithImportedMemoryWithoutChangingSharedMemory() throws Exception {
        byte[] importedPassive = module(section(2, vector(concat(name("env"), name("memory"), bytes(2, 1, 1, 2)))),
                section(11, vector(bytes(1, 1, 8))));
        byte[] definedPassive = module(section(5, vector(bytes(1, 1, 2))), section(11, vector(bytes(1, 1, 8))));
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module ordinary = runtime.compile(memoryFixture());
             WasmRuntime.Module passive = runtime.compile(importedPassive);
             WasmRuntime.Module ownerModule = runtime.compile(definedPassive);
             WasmRuntime.Memory memory = runtime.memory(1, 2);
             WasmRuntime.Instance first = runtime.instantiate(ordinary, Map.of("env", Map.of("memory", memory)));
             WasmRuntime.Instance second = runtime.instantiate(ordinary, Map.of("env", Map.of("memory", memory)));
             WasmRuntime.Instance owner = runtime.instantiate(ownerModule)) {
            assertThrows(WasmRuntime.LinkException.class, () -> runtime.instantiate(passive, Map.of("env", Map.of("memory", memory))));
            assertThrows(WasmRuntime.LinkException.class, () -> runtime.instantiate(ordinary, Map.of("env", Map.of("memory", owner.memory()))));
            first.call("write", 10, 73);
            assertArrayEquals(new Object[]{73}, second.call("read", 10));
        }
    }

    @Test
    public void shouldExecuteBoundedInternalFunctionTable() throws Exception {
        // (table 1 1 funcref) (elem (i32.const 0) $answer)
        // (func $answer (result i32) i32.const 42)
        // (func (export "indirect") (result i32) i32.const 0 call_indirect (type 0))
        byte[] binary = module(section(1, vector(bytes(0x60, 0, 1, 0x7f))),
                section(3, vector(u(0), u(0))), section(4, vector(bytes(0x70, 1, 1, 1))),
                section(7, vector(concat(name("indirect"), bytes(0, 1)))),
                section(9, vector(bytes(0, 0x41, 0, 0x0b, 1, 0))),
                section(10, code(bytes(0, 0x41, 42, 0x0b), bytes(0, 0x41, 0, 0x11, 0, 0, 0x0b))));
        try (WasmRuntime runtime = new WasmRuntime(); WasmRuntime.Module module = runtime.compile(binary);
             WasmRuntime.Instance instance = runtime.instantiate(module)) {
            assertArrayEquals(new Object[]{42}, instance.call("indirect"));
        }
    }

    private static Map<String, Map<String, Object>> add(WasmRuntime runtime) {
        return add(runtime, () -> { });
    }

    private static Map<String, Map<String, Object>> add(WasmRuntime runtime, Runnable onCall) {
        WasmRuntime.Function function = runtime.function(new WasmRuntime.Signature(
                List.of(WasmRuntime.Type.I32, WasmRuntime.Type.I32), List.of(WasmRuntime.Type.I32)),
                (instance, arguments) -> {
                    onCall.run();
                    return new Object[]{(Integer) arguments[0] + (Integer) arguments[1]};
                });
        return Map.of("env", Map.of("add", function));
    }

    private static byte[] fixture(boolean startTrap, int initialMemory, int maximumMemory) {
        // Imports env.add; exports calc, loop, grow, trap, scalar identity functions and multivalue.
        // The loop calls env.add(0, 0) first, giving host observers a real guest-execution signal.
        byte[] types = vector(bytes(0x60, 2, 0x7f, 0x7f, 1, 0x7f), bytes(0x60, 0, 0),
                bytes(0x60, 1, 0x7f, 1, 0x7f), bytes(0x60, 1, 0x7e, 1, 0x7e),
                bytes(0x60, 1, 0x7d, 1, 0x7d), bytes(0x60, 1, 0x7c, 1, 0x7c),
                bytes(0x60, 0, 2, 0x7f, 0x7e));
        return module(section(1, types), section(2, vector(concat(name("env"), name("add"), bytes(0, 0)))),
                section(3, vector(u(0), u(1), u(2), u(1), u(3), u(4), u(5), u(6))),
                section(5, vector(concat(u(1), u(initialMemory), u(maximumMemory)))),
                section(7, vector(export("calc", 0, 1), export("loop", 0, 2), export("grow", 0, 3),
                        export("trap", 0, 4), export("i64", 0, 5), export("f32", 0, 6),
                        export("f64", 0, 7), export("multi", 0, 8), export("memory", 2, 0))),
                startTrap ? section(8, u(4)) : new byte[0],
                section(10, code(bytes(0, 0x20, 0, 0x20, 1, 0x10, 0, 0x0b),
                        bytes(0, 0x41, 0, 0x41, 0, 0x10, 0, 0x1a, 3, 0x40, 0x0c, 0, 0x0b, 0x0b),
                        bytes(0, 0x20, 0, 0x40, 0, 0x0b), bytes(0, 0, 0x0b),
                        bytes(0, 0x20, 0, 0x0b), bytes(0, 0x20, 0, 0x0b), bytes(0, 0x20, 0, 0x0b),
                        bytes(0, 0x41, 42, 0x42, 7, 0x0b))));
    }

    private static byte[] memoryFixture() {
        return module(section(1, vector(bytes(0x60, 1, 0x7f, 1, 0x7f), bytes(0x60, 2, 0x7f, 0x7f, 1, 0x7f))),
                section(2, vector(concat(name("env"), name("memory"), bytes(2, 1, 1, 2)))),
                section(3, vector(u(0), u(1))),
                section(7, vector(export("read", 0, 0), export("write", 0, 1), export("memory", 2, 0))),
                section(10, code(bytes(0, 0x20, 0, 0x2d, 0, 0, 0x0b),
                        bytes(0, 0x20, 0, 0x20, 1, 0x3a, 0, 0, 0x20, 1, 0x0b))));
    }

    private static byte[] emptyModule() { return bytes(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0); }

    private static byte[] module(byte[]... sections) { return concat(emptyModule(), concat(sections)); }

    private static byte[] code(byte[]... bodies) {
        return vector(Arrays.stream(bodies).map(body -> concat(u(body.length), body)).toArray(byte[][]::new));
    }

    private static byte[] export(String name, int kind, int index) { return concat(name(name), u(kind), u(index)); }

    private static byte[] section(int id, byte[] content) { return concat(u(id), u(content.length), content); }

    private static byte[] vector(byte[]... values) { return concat(u(values.length), concat(values)); }

    private static byte[] name(String name) {
        byte[] value = name.getBytes(StandardCharsets.UTF_8);
        return concat(u(value.length), value);
    }

    private static byte[] u(int value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        do {
            int current = value & 127;
            value >>>= 7;
            output.write(current | (value == 0 ? 0 : 128));
        } while (value != 0);
        return output.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return bytes;
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }
}
