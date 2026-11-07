package com.fongmi.quickjs.method;

import com.fongmi.quickjs.WasmRuntime;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.CallTimeout;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Owns one WASM runtime; QuickJS values are only accessed on the source's JS executor. */
public final class WasmBridge implements AutoCloseable {

    private final WasmRuntime runtime = new WasmRuntime();
    private final Map<Integer, WasmRuntime.Module> modules = new HashMap<>();
    private final Map<Integer, Binding> instances = new HashMap<>();
    private final Map<Integer, WasmRuntime.Memory> memories = new HashMap<>();
    private final Map<WasmRuntime.Memory, Integer> memoryIds = new IdentityHashMap<>();
    private final Set<WasmRuntime.Memory> retiredOwnedMemories = Collections.newSetFromMap(new IdentityHashMap<>());
    private JSFunction dispatcher;
    private Runnable executionCheck = () -> {};
    private volatile boolean canceled;
    private boolean closed;
    private int nextModule;
    private int nextMemory;

    public WasmBridge(QuickJSContext context) {
        JSObject nativeObject = context.createNewJSObject();
        try {
            nativeObject.setProperty("call", args -> {
                String operation = "";
                try {
                    operation = (String) args[0];
                    JSONObject params = new JSONObject((String) args[1]);
                    byte[] bytes = args.length > 2 && args[2] instanceof byte[] ? (byte[]) args[2] : null;
                    return execute(operation, params, bytes);
                } catch (Exception error) {
                    return failure(error, operation);
                } finally {
                    releaseArguments(args);
                }
            });
            nativeObject.setProperty("read", args -> {
                try {
                    checkOpen();
                    JSONObject params = new JSONObject((String) args[0]);
                    return memory(params.getInt("memory")).read(params.getInt("offset"), params.getInt("length"));
                } catch (Exception error) {
                    return failure(error, "memoryRead");
                } finally {
                    releaseArguments(args);
                }
            });
            context.getGlobalObject().setProperty("__SPIDER_WASM_NATIVE__", nativeObject);
            context.evaluate(Asset.read("js/lib/wasm.js"));
            dispatcher = context.getGlobalObject().getJSFunction("__SPIDER_WASM_DISPATCH__");
            if (dispatcher == null) throw new IllegalStateException("WASM dispatcher missing");
        } catch (RuntimeException | Error error) {
            runtime.close();
            throw error;
        } finally {
            nativeObject.release();
        }
    }

    private String execute(String operation, JSONObject params, byte[] bytes) throws Exception {
        if (operation.startsWith("close")) {
            if (closed) throw new IllegalStateException("WASM runtime closed");
            return success(closeResource(operation, params));
        }
        checkOpen();
        Runnable previous = executionCheck;
        Invocation invocation = Invocation.current();
        CallTimeout deadline = CallTimeout.current();
        executionCheck = () -> {
            checkOpen();
            if (invocation != null) invocation.checkActive();
            if (deadline != null && deadline.isExpired()) throw new CancellationException("WASM request timed out");
        };
        runtime.setExecutionCheck(executionCheck);
        try {
            executionCheck.run();
            return success(switch (operation) {
                case "compile" -> compile(bytes);
                case "validate" -> validate(bytes);
                case "limits" -> new JSONObject().put("memoryPages", WasmRuntime.MAX_MEMORY_PAGES);
                case "instantiate" -> instantiate(params);
                case "call" -> call(params);
                case "memory" -> memoryDescriptor(runtime.memory(params.getInt("initial"), params.getInt("maximum")));
                case "memoryInfo" -> memoryDescriptor(memory(params.getInt("memory")));
                case "memoryWrite" -> {
                    if (bytes == null) throw new IllegalArgumentException("WASM bytes required");
                    memory(params.getInt("memory")).write(params.getInt("offset"), bytes);
                    yield null;
                }
                case "memoryGrow" -> memory(params.getInt("memory")).grow(params.getInt("pages"));
                default -> throw new IllegalArgumentException("Unknown WASM operation: " + operation);
            });
        } finally {
            executionCheck = previous;
            runtime.setExecutionCheck(previous);
        }
    }

    private Object closeResource(String operation, JSONObject params) throws Exception {
        switch (operation) {
            case "closeInstance" -> {
                Binding binding = instances.remove(params.getInt("instance"));
                if (binding != null) {
                    closeBinding(binding);
                }
            }
            case "closeModule" -> {
                WasmRuntime.Module module = modules.remove(params.getInt("module"));
                if (module != null) module.close();
            }
            case "closeMemory" -> {
                WasmRuntime.Memory memory = memories.remove(params.getInt("memory"));
                if (memory != null) {
                    memoryIds.remove(memory);
                    retiredOwnedMemories.remove(memory);
                    memory.close();
                }
            }
            default -> throw new IllegalArgumentException("Unknown WASM close operation");
        }
        return null;
    }

    private JSONObject compile(byte[] bytes) throws Exception {
        if (bytes == null) throw new IllegalArgumentException("WASM bytes required");
        WasmRuntime.Module module = runtime.compile(bytes);
        int id = ++nextModule;
        modules.put(id, module);
        return moduleDescriptor(id, module);
    }

    private boolean validate(byte[] bytes) throws Exception {
        if (bytes == null) throw new IllegalArgumentException("WASM bytes required");
        try (WasmRuntime.Module ignored = runtime.compile(bytes)) {
            return true;
        } catch (WasmRuntime.CompileException | WasmRuntime.LimitException error) {
            return false;
        }
    }

    private JSONObject instantiate(JSONObject params) throws Exception {
        int id = params.getInt("instance");
        if (id < 1 || instances.containsKey(id)) throw new IllegalArgumentException("Invalid WASM instance handle");
        WasmRuntime.Module module = module(params.getInt("module"));
        Binding binding = new Binding(id, module);
        instances.put(id, binding);
        Map<String, Map<String, Object>> imports = new HashMap<>();
        JSONObject supplied = params.getJSONObject("imports");
        try {
            for (WasmRuntime.Import imported : module.imports()) {
                JSONObject namespace = supplied.optJSONObject(imported.module());
                JSONObject descriptor = namespace == null ? null : namespace.optJSONObject(imported.name());
                if (descriptor == null) throw new WasmRuntime.LinkException("Missing WASM import: " + imported.module() + "." + imported.name());
                Object value;
                if (imported.kind() == WasmRuntime.Kind.FUNCTION && "function".equals(descriptor.optString("kind"))) {
                    value = runtime.function(imported.signature(), (instance, arguments) -> {
                        binding.instance = instance;
                        return hostCall(binding, imported, arguments);
                    });
                } else if (imported.kind() == WasmRuntime.Kind.MEMORY && "memory".equals(descriptor.optString("kind"))) {
                    value = memory(descriptor.getInt("memory"));
                    binding.importedMemory = true;
                    binding.memory = (WasmRuntime.Memory) value;
                } else {
                    throw new WasmRuntime.LinkException("Unsupported WASM import: " + imported.module() + "." + imported.name());
                }
                imports.computeIfAbsent(imported.module(), key -> new HashMap<>()).put(imported.name(), value);
            }
            binding.instance = runtime.instantiate(module, imports);
            JSONObject result = moduleDescriptor(id, module);
            result.put("memory", instanceMemory(binding));
            return result;
        } catch (Exception | Error error) {
            instances.remove(id);
            closeBinding(binding);
            throw error;
        }
    }

    private void closeBinding(Binding binding) {
        if (binding.memory != null) {
            if (!binding.importedMemory) retiredOwnedMemories.add(binding.memory);
            boolean linked = instances.values().stream().anyMatch(other -> other.memory == binding.memory);
            if (!linked && retiredOwnedMemories.remove(binding.memory)) {
                Integer id = memoryIds.remove(binding.memory);
                if (id != null) memories.remove(id);
            }
        }
        if (binding.instance != null) binding.instance.close();
    }

    private JSONArray call(JSONObject params) throws Exception {
        Binding binding = instance(params.getInt("instance"));
        String name = params.getString("name");
        WasmRuntime.Signature signature = binding.module.signature(name);
        Object[] arguments = decode(params.getJSONArray("arguments"), signature.params());
        Object[] result = params.has("timeout")
                ? binding.instance.callTimed(params.getLong("timeout"), name, arguments)
                : binding.instance.call(name, arguments);
        return encode(result, signature.results());
    }

    private Object[] hostCall(Binding binding, WasmRuntime.Import imported, Object[] arguments) throws Exception {
        JSONObject request = new JSONObject().put("module", imported.module()).put("name", imported.name())
                .put("signature", signature(imported.signature())).put("arguments", encode(arguments, imported.signature().params()))
                .put("memory", instanceMemory(binding));
        Object raw = dispatcher.call("import", binding.id, request.toString());
        if (!(raw instanceof String)) {
            if (raw instanceof JSObject object) object.release();
            throw new HostException("TypeError", "WASM import returned an invalid bridge result");
        }
        JSONObject response = new JSONObject((String) raw);
        JSONObject error = response.optJSONObject("error");
        if (error != null) throw new HostException(error.optString("name", "Error"), error.optString("message", "WASM import failed"));
        return decode(response.getJSONArray("value"), imported.signature().results());
    }

    private JSONObject moduleDescriptor(int id, WasmRuntime.Module module) throws Exception {
        JSONArray imports = new JSONArray();
        for (WasmRuntime.Import item : module.imports()) {
            imports.put(new JSONObject().put("module", item.module()).put("name", item.name()).put("kind", item.kind().name())
                    .put("signature", signature(item.signature())));
        }
        JSONArray exports = new JSONArray();
        for (WasmRuntime.Export item : module.exports()) {
            exports.put(new JSONObject().put("name", item.name()).put("kind", item.kind().name()).put("signature", signature(item.signature())));
        }
        return new JSONObject().put("id", id).put("imports", imports).put("exports", exports);
    }

    private Object instanceMemory(Binding binding) throws Exception {
        try {
            binding.memory = binding.instance.memory();
            return memoryDescriptor(binding.memory);
        } catch (IllegalArgumentException missing) {
            if ("Missing WASM memory".equals(missing.getMessage())) return JSONObject.NULL;
            throw missing;
        }
    }

    private JSONObject memoryDescriptor(WasmRuntime.Memory memory) throws Exception {
        Integer id = memoryIds.get(memory);
        if (id == null) {
            id = ++nextMemory;
            memoryIds.put(memory, id);
            memories.put(id, memory);
        }
        return new JSONObject().put("id", id).put("pages", memory.pages());
    }

    private static Object signature(WasmRuntime.Signature signature) throws Exception {
        if (signature == null) return JSONObject.NULL;
        JSONArray params = new JSONArray(), results = new JSONArray();
        for (WasmRuntime.Type type : signature.params()) params.put(type.name());
        for (WasmRuntime.Type type : signature.results()) results.put(type.name());
        return new JSONObject().put("params", params).put("results", results);
    }

    private static Object[] decode(JSONArray values, List<WasmRuntime.Type> types) throws Exception {
        if (values.length() != types.size()) throw new IllegalArgumentException("WASM argument count does not match signature");
        Object[] result = new Object[types.size()];
        for (int i = 0; i < types.size(); i++) {
            String value = values.getString(i);
            result[i] = switch (types.get(i)) {
                case I32 -> Integer.valueOf(value);
                case I64 -> Long.valueOf(value);
                case F32 -> Float.valueOf(floatText(value));
                case F64 -> Double.valueOf(floatText(value));
            };
        }
        return result;
    }

    private static JSONArray encode(Object[] values, List<WasmRuntime.Type> types) throws Exception {
        if (values.length != types.size()) throw new IllegalArgumentException("WASM result count does not match signature");
        JSONArray result = new JSONArray();
        for (Object value : values) result.put(value.toString());
        return result;
    }

    private static String floatText(String value) {
        return switch (value) {
            case "nan" -> "NaN";
            case "infinity" -> "Infinity";
            case "-infinity" -> "-Infinity";
            default -> value;
        };
    }

    private WasmRuntime.Module module(int id) {
        WasmRuntime.Module module = modules.get(id);
        if (module == null) throw new IllegalStateException("WASM module closed or belongs to another source");
        return module;
    }

    private Binding instance(int id) {
        Binding binding = instances.get(id);
        if (binding == null || binding.instance == null) throw new IllegalStateException("WASM instance closed or belongs to another source");
        return binding;
    }

    private WasmRuntime.Memory memory(int id) {
        WasmRuntime.Memory memory = memories.get(id);
        if (memory == null) throw new IllegalStateException("WASM memory closed or belongs to another source");
        return memory;
    }

    private void checkOpen() {
        if (canceled || closed) throw new CancellationException("WASM runtime closed");
    }

    /** May be called by the destroying caller before queued QuickJS cleanup can run. */
    public void cancel() {
        canceled = true;
        runtime.cancel();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancel();
        try {
            if (dispatcher != null) dispatcher.callVoid("close", 0, "");
        } finally {
            if (dispatcher != null) dispatcher.release();
            dispatcher = null;
            runtime.close();
            modules.clear();
            instances.clear();
            memories.clear();
            memoryIds.clear();
            retiredOwnedMemories.clear();
        }
    }

    private static String success(Object value) throws Exception {
        return new JSONObject().put("value", value == null ? JSONObject.NULL : value).toString();
    }

    private static String failure(Exception error, String operation) {
        String name = "RuntimeError";
        if (error instanceof HostException host) name = host.name;
        else if (error instanceof WasmRuntime.CompileException) name = "CompileError";
        else if (error instanceof WasmRuntime.LinkException) name = "LinkError";
        else if (error instanceof IndexOutOfBoundsException) name = "RangeError";
        else if (error instanceof IllegalArgumentException) name = operation.startsWith("memory") ? "RangeError" : "TypeError";
        try {
            return new JSONObject().put("error", new JSONObject().put("name", name)
                    .put("message", error.getMessage() == null ? name : error.getMessage())).toString();
        } catch (Exception impossible) {
            return "{\"error\":{\"name\":\"RuntimeError\",\"message\":\"WASM operation failed\"}}";
        }
    }

    private static void releaseArguments(Object[] arguments) {
        if (arguments == null) return;
        for (Object argument : arguments) if (argument instanceof JSObject object) object.release();
    }

    private static final class Binding {
        final int id;
        final WasmRuntime.Module module;
        WasmRuntime.Instance instance;
        WasmRuntime.Memory memory;
        boolean importedMemory;

        Binding(int id, WasmRuntime.Module module) {
            this.id = id;
            this.module = module;
        }
    }

    private static final class HostException extends RuntimeException {
        final String name;

        HostException(String name, String message) {
            super(message);
            this.name = name;
        }
    }
}
