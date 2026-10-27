// 内蔵 SAORI（SAORI over DirectSSTP の internal_*）。
//
// ゴーストの辞書が内蔵モジュールを呼ぶと（YAYA の FUNCTIONEX('internal_crypto', ...)・里々の（internal_crypto、…））、
// SAORI/1.0 の要求をそのまま Kotlin の net.pono.shiori.yaya.SaoriBridge.request に渡し、本体（アプリのプロセス）で答える。
// 呼ばれるのは SHIORI の load / request の途中（Kotlin から来た JNI の呼び出しの中）なので、同じスレッドの JNIEnv を使う。
#include "pono_saori.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include <mutex>
#include <string>

namespace {

std::mutex g_lock;
JavaVM *g_vm = nullptr;
jclass g_bridge = nullptr;
jmethodID g_request = nullptr;

JNIEnv *CurrentEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}

}  // namespace

void pono_saori_attach(JNIEnv *env) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_bridge != nullptr) return;
    env->GetJavaVM(&g_vm);
    jclass c = env->FindClass("net/pono/shiori/yaya/SaoriBridge");
    if (c == nullptr) {
        env->ExceptionClear();
        return;
    }
    g_request = env->GetStaticMethodID(c, "request", "([BLjava/lang/String;)[B");
    if (g_request == nullptr) {
        env->ExceptionClear();
    } else {
        g_bridge = static_cast<jclass>(env->NewGlobalRef(c));
    }
    env->DeleteLocalRef(c);
}

extern "C" int pono_saori_direct_available(void) { return g_bridge != nullptr && CurrentEnv() != nullptr ? 1 : 0; }

extern "C" char *pono_saori_direct(const char *module, const char *req, long len, long *outLen) {
    JNIEnv *env = CurrentEnv();
    if (env == nullptr || g_bridge == nullptr || req == nullptr || len < 0 || len > 0x7FFFFFFFL) return nullptr;
    jbyteArray in = env->NewByteArray(static_cast<jsize>(len));
    if (in == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(in, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(req));
    jstring name = module != nullptr ? env->NewStringUTF(module) : nullptr;
    jbyteArray out = static_cast<jbyteArray>(env->CallStaticObjectMethod(g_bridge, g_request, in, name));
    env->DeleteLocalRef(in);
    if (name != nullptr) env->DeleteLocalRef(name);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (out != nullptr) env->DeleteLocalRef(out);
        return nullptr;
    }
    if (out == nullptr) return nullptr;
    jsize n = env->GetArrayLength(out);
    char *buf = static_cast<char *>(malloc(static_cast<size_t>(n) + 1));
    if (buf != nullptr) {
        env->GetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte *>(buf));
        buf[n] = '\0';
        *outLen = n;
    }
    env->DeleteLocalRef(out);
    return buf;
}

extern "C" int pono_saori_module_name(const char *path, char *out, size_t outSize) {
    if (path == nullptr) return 0;
    const char *base = path;
    for (const char *p = path; *p != '\0'; ++p) {
        if (*p == '/' || *p == '\\') base = p + 1;
    }
    std::string name(base);
    for (char &c : name) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
    if (name.size() > 4 && name.compare(name.size() - 4, 4, ".dll") == 0) name.erase(name.size() - 4);
    if (name.size() <= 9 || name.compare(0, 9, "internal_") != 0) return 0;
    // モジュール名に使える文字だけ（ほかの文字の名前は、ゴーストに同梱の DLL として扱い、読まない）
    for (char c : name) {
        if (!isalnum(static_cast<unsigned char>(c)) && c != '_') return 0;
    }
    if (name.size() + 1 > outSize) return 0;
    memcpy(out, name.c_str(), name.size() + 1);
    return 1;
}
