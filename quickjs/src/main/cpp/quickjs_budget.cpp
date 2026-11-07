#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <new>

// QuickJS 3.2.3 exports this C API. No QuickJS struct layout is accessed here.
struct JSRuntime;
using Interrupt = int (*)(JSRuntime *, void *);
using SetInterrupt = void (*)(JSRuntime *, Interrupt, void *);

struct Budget {
    JSRuntime *runtime;
    SetInterrupt setInterrupt;
    std::atomic<bool> canceled{false};
    std::chrono::steady_clock::time_point deadline;
};

static std::atomic<int> activeBudgets{0};

static int interrupted(JSRuntime *, void *opaque) {
    auto *budget = static_cast<Budget *>(opaque);
    return budget->canceled.load(std::memory_order_relaxed) || std::chrono::steady_clock::now() >= budget->deadline;
}

static void failure(JNIEnv *env, const char *message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fongmi_quickjs_utils_NativeBudget_attachNative(JNIEnv *env, jclass, jobject context, jlong timeoutMillis) {
    // Use the loaded wrapper handle: Android loads dependency libraries in a local namespace.
    void *library = dlopen("libquickjs-android-wrapper.so", RTLD_NOW | RTLD_NOLOAD);
    if (library == nullptr) { failure(env, "QuickJS native library is unavailable"); return 0; }
    auto setInterrupt = reinterpret_cast<SetInterrupt>(dlsym(library, "JS_SetInterruptHandler"));
    dlclose(library);
    if (setInterrupt == nullptr) { failure(env, "QuickJS native interrupt API is unavailable"); return 0; }
    jclass type = env->GetObjectClass(context);
    jfieldID runtimeField = env->GetFieldID(type, "runtime", "J");
    env->DeleteLocalRef(type);
    if (runtimeField == nullptr) return 0;
    auto *runtime = reinterpret_cast<JSRuntime *>(static_cast<intptr_t>(env->GetLongField(context, runtimeField)));
    if (runtime == nullptr) { failure(env, "QuickJS runtime is unavailable"); return 0; }
    // Bound the duration before conversion to nanoseconds to avoid overflowing the native clock.
    constexpr jlong maxMillis = INT64_MAX / 1000000 / 2;
    const auto duration = std::chrono::milliseconds(timeoutMillis > maxMillis ? maxMillis : timeoutMillis);
    auto *budget = new (std::nothrow) Budget{runtime, setInterrupt, false, std::chrono::steady_clock::now() + duration};
    if (budget == nullptr) { failure(env, "Cannot allocate QuickJS execution budget"); return 0; }
    setInterrupt(runtime, interrupted, budget);
    activeBudgets.fetch_add(1, std::memory_order_relaxed);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(budget));
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_quickjs_utils_NativeBudget_cancelNative(JNIEnv *, jclass, jlong handle) {
    auto *budget = reinterpret_cast<Budget *>(static_cast<intptr_t>(handle));
    budget->canceled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fongmi_quickjs_utils_NativeBudget_detachNative(JNIEnv *, jclass, jlong handle) {
    auto *budget = reinterpret_cast<Budget *>(static_cast<intptr_t>(handle));
    // Only the Java owner thread detaches, after evaluate/call and pending jobs have returned.
    budget->setInterrupt(budget->runtime, nullptr, nullptr);
    delete budget;
    activeBudgets.fetch_sub(1, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fongmi_quickjs_utils_NativeBudget_activeCount(JNIEnv *, jclass) {
    return activeBudgets.load(std::memory_order_relaxed);
}
