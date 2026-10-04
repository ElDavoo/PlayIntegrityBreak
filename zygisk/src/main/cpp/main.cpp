// PIB Zygisk module.
//
// Only runs in the Play Store main process. In preAppSpecialize (still with zygote privileges)
// it reads classes.dex from the module directory and initialises LSPlant; in
// postAppSpecialize it loads the dex and hands control to ZygiskEntry.main().

#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include <dobby.h>
#include <lsplant.hpp>

#include "elf_util.h"
#include "zygisk.hpp"

#define LOG_TAG "PIB-Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kTargetProcess = "com.android.vending";
constexpr const char *kDexFile = "classes.dex";
constexpr const char *kBridgeClass = "icu.nullptr.playintegritybreak.zygisk.LSPlantBridge";
constexpr const char *kEntryClass = "icu.nullptr.playintegritybreak.zygisk.ZygiskEntry";

// Kept for the whole process lifetime, since LSPlant may resolve symbols after Init.
ElfImg *libart = nullptr;


// The dex is mapped by a direct ByteBuffer, so it must outlive the class loader (forever).
std::vector<uint8_t> *dex_bytes = nullptr;

bool read_file(int dir_fd, const char *path, std::vector<uint8_t> &out) {
    int fd = openat(dir_fd, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("Cannot open %s: %s", path, strerror(errno));
        return false;
    }
    uint8_t buf[64 * 1024];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) out.insert(out.end(), buf, buf + n);
    close(fd);
    if (n < 0) {
        LOGE("Cannot read %s: %s", path, strerror(errno));
        return false;
    }
    return !out.empty();
}

bool init_lsplant(JNIEnv *env) {
    libart = new ElfImg("libart.so");
    if (!libart->valid()) {
        LOGE("Cannot read libart.so symbols");
        return false;
    }

    lsplant::InitInfo info{
            .inline_hooker = [](void *target, void *hooker) -> void * {
                void *backup = nullptr;
                return DobbyHook(target, hooker, &backup) == 0 ? backup : nullptr;
            },
            .inline_unhooker = [](void *func) -> bool {
                return DobbyDestroy(func) == 0;
            },
            .art_symbol_resolver = [](std::string_view symbol) -> void * {
                return libart->symbol(symbol);
            },
            .art_symbol_prefix_resolver = [](std::string_view prefix) -> void * {
                return libart->symbol_prefix(prefix);
            },
            .generated_class_name = "PIBHooker_",
            .generated_source_name = "PIB",
    };
    if (!lsplant::Init(env, info)) {
        LOGE("LSPlant init failed");
        return false;
    }
    return true;
}

jobject JNICALL bridge_hook(JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    return lsplant::Hook(env, target, hooker, callback);
}

jboolean JNICALL bridge_deoptimize(JNIEnv *env, jclass, jobject method) {
    return lsplant::Deoptimize(env, method) ? JNI_TRUE : JNI_FALSE;
}

bool clear_exception(JNIEnv *env, const char *what) {
    if (!env->ExceptionCheck()) return false;
    LOGE("Java exception during %s", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

jclass load_class(JNIEnv *env, jobject loader, const char *name) {
    jclass loader_class = env->GetObjectClass(loader);
    jmethodID load = env->GetMethodID(loader_class, "loadClass",
                                      "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jname = env->NewStringUTF(name);
    auto clazz = static_cast<jclass>(env->CallObjectMethod(loader, load, jname));
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(loader_class);
    if (clear_exception(env, name)) return nullptr;
    return clazz;
}

// Lets the injected dex reflect on framework internals (e.g. ActivityThread), like Xposed does.
void exempt_hidden_api(JNIEnv *env) {
    jclass runtime_class = env->FindClass("dalvik/system/VMRuntime");
    if (clear_exception(env, "VMRuntime lookup")) return;
    jmethodID get_runtime = env->GetStaticMethodID(
            runtime_class, "getRuntime", "()Ldalvik/system/VMRuntime;");
    jmethodID set_exemptions = env->GetMethodID(
            runtime_class, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (clear_exception(env, "VMRuntime methods")) return;
    jobject runtime = env->CallStaticObjectMethod(runtime_class, get_runtime);
    jobjectArray prefixes = env->NewObjectArray(1, env->FindClass("java/lang/String"),
                                                env->NewStringUTF("L"));
    env->CallVoidMethod(runtime, set_exemptions, prefixes);
    clear_exception(env, "setHiddenApiExemptions");
}

void start_java(JNIEnv *env) {
    exempt_hidden_api(env);

    jclass system_loader_class = env->FindClass("java/lang/ClassLoader");
    jmethodID get_system_loader = env->GetStaticMethodID(
            system_loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = env->CallStaticObjectMethod(system_loader_class, get_system_loader);

    jclass dex_loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID dex_loader_init = env->GetMethodID(
            dex_loader_class, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject buffer = env->NewDirectByteBuffer(dex_bytes->data(),
                                              static_cast<jlong>(dex_bytes->size()));
    jobject loader = env->NewObject(dex_loader_class, dex_loader_init, buffer, parent);
    if (clear_exception(env, "dex loading") || !loader) return;

    jclass bridge = load_class(env, loader, kBridgeClass);
    if (!bridge) return;
    const JNINativeMethod natives[] = {
            {"hook", "(Ljava/lang/reflect/Method;Ljava/lang/Object;Ljava/lang/reflect/Method;)"
                     "Ljava/lang/reflect/Method;",
             reinterpret_cast<void *>(bridge_hook)},
            {"deoptimize", "(Ljava/lang/reflect/Executable;)Z",
             reinterpret_cast<void *>(bridge_deoptimize)},
    };
    env->RegisterNatives(bridge, natives, sizeof(natives) / sizeof(natives[0]));
    if (clear_exception(env, "RegisterNatives")) return;

    jclass entry = load_class(env, loader, kEntryClass);
    if (!entry) return;
    jmethodID main = env->GetStaticMethodID(entry, "main", "()V");
    if (clear_exception(env, "entry lookup")) return;
    env->CallStaticVoidMethod(entry, main);
    clear_exception(env, "ZygiskEntry.main");
}

class PIBModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (!is_target(args)) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        int dir_fd = api_->getModuleDir();
        if (dir_fd < 0) {
            LOGE("Cannot get module directory");
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        auto dex = new std::vector<uint8_t>();
        bool ok = read_file(dir_fd, kDexFile, *dex);
        close(dir_fd);

        // LSPlant must be initialised before the hidden API policy of the app is applied.
        if (!ok || !init_lsplant(env_)) {
            delete dex;
            // Natives are not registered yet, so unloading is still safe.
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        dex_bytes = dex;
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!dex_bytes) return;
        LOGI("Injecting into %s", kTargetProcess);
        start_java(env_);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;

    bool is_target(zygisk::AppSpecializeArgs *args) {
        if (!args->nice_name) return false;
        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (!name) return false;
        bool match = strcmp(name, kTargetProcess) == 0;
        env_->ReleaseStringUTFChars(args->nice_name, name);
        return match;
    }
};

}  // namespace

REGISTER_ZYGISK_MODULE(PIBModule)
