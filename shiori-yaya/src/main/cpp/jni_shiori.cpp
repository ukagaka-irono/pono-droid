// YAYA の multi_load / multi_request / multi_unload を Kotlin から呼ぶための JNI ラッパー。
// バッファの所有権は YAYA の POSIX 版の約束に合わせる:
//   load: 渡したバッファは YAYA が free する
//   request: 失敗時は YAYA が入力を free することがある。成功時は入力も出力も呼び出し側が free する
// load のときに、このゴーストが触ってよいディレクトリを pono_sandbox に教える。
#include <jni.h>
#include <stdlib.h>

#include <map>
#include <mutex>
#include <string>

#include "aya5.h"
#include "shim/pono_sandbox.h"

namespace {

// YAYA はスレッドセーフではない（VM の一覧が static）ので、呼び出しを1つずつにする
std::mutex g_lock;
std::map<long, std::string> g_allowedDirs;

char *CopyToMalloc(JNIEnv *env, jbyteArray array, jsize *len) {
    *len = env->GetArrayLength(array);
    char *buf = static_cast<char *>(malloc(static_cast<size_t>(*len) + 1));
    if (buf == nullptr) return nullptr;
    env->GetByteArrayRegion(array, 0, *len, reinterpret_cast<jbyte *>(buf));
    buf[*len] = '\0';
    return buf;
}

std::string ToString(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) return std::string();
    jsize len = env->GetArrayLength(array);
    std::string s(static_cast<size_t>(len), '\0');
    if (len > 0) env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_net_pono_shiori_yaya_YayaNative_load(JNIEnv *env, jclass, jbyteArray dir, jbyteArray allowedDir, jbyteArray protectedDir) {
    std::lock_guard<std::mutex> guard(g_lock);
    std::string allowed = ToString(env, allowedDir);
    std::string guarded = ToString(env, protectedDir);
    if (!guarded.empty()) pono_sandbox_protect(guarded.c_str());
    if (!allowed.empty()) pono_sandbox_allow(allowed.c_str());

    jsize len;
    char *buf = CopyToMalloc(env, dir, &len);
    if (buf == nullptr) return 0;
    long id;
    try {
        id = multi_load(buf, len);
    } catch (...) {
        id = 0;
    }
    if (id > 0) {
        g_allowedDirs[id] = allowed;
    } else if (!allowed.empty()) {
        pono_sandbox_forget(allowed.c_str());
    }
    return id;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_pono_shiori_yaya_YayaNative_request(JNIEnv *env, jclass, jlong id, jbyteArray req) {
    std::lock_guard<std::mutex> guard(g_lock);
    jsize len;
    char *in = CopyToMalloc(env, req, &len);
    if (in == nullptr) return nullptr;
    long resLen = len;
    char *res;
    try {
        res = multi_request(static_cast<long>(id), in, &resLen);
    } catch (...) {
        res = nullptr;
    }
    if (res == nullptr) {
        return nullptr;
    }
    free(in);
    if (resLen < 0 || resLen > 0x7FFFFFFFL) {
        free(res);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(resLen));
    if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(resLen), reinterpret_cast<jbyte *>(res));
    free(res);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_pono_shiori_yaya_YayaNative_unload(JNIEnv *, jclass, jlong id) {
    std::lock_guard<std::mutex> guard(g_lock);
    bool ok;
    try {
        ok = multi_unload(static_cast<long>(id));
    } catch (...) {
        ok = false;
    }
    std::map<long, std::string>::iterator it = g_allowedDirs.find(static_cast<long>(id));
    if (it != g_allowedDirs.end()) {
        if (!it->second.empty()) pono_sandbox_forget(it->second.c_str());
        g_allowedDirs.erase(it);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
