// SHIORI（YAYA・里々・華和梨・Kagari）の load / request / unload を Kotlin から呼ぶための JNI の共通部分。
// エンジンごとに jni_<名前>.cpp でこのヘッダを読み、PONO_DEFINE_SHIORI_JNI で入口を作る。
//
// バッファの所有権は POSIX 版の SHIORI の約束に合わせる:
//   load: 渡したバッファはエンジンが free する
//   request: [requestFreesInput] が true のエンジンは入力を必ず free する。
//            false のエンジン（YAYA）は成功時は free しないので、こちらで free する（失敗時は YAYA が free することがある）
//   出力はエンジンが malloc したものを、こちらで free する
// load のときに、このゴーストが触ってよいディレクトリを pono_sandbox に教える。
#pragma once

#include <jni.h>
#include <stdlib.h>

#include <map>
#include <mutex>
#include <string>

#include "shim/pono_sandbox.h"

namespace pono {

struct ShioriEngine {
    // 戻り値は 0 以上の ID（失敗は負）。ID 0 を使うエンジンもあるので、Kotlin には +1 して返す
    long (*load)(char *dir, long len);
    char *(*request)(long id, char *req, long *len);
    bool (*unload)(long id);
    bool requestFreesInput;
};

class ShioriJni {
public:
    explicit ShioriJni(const ShioriEngine &engine) : engine_(engine) {}

    jlong Load(JNIEnv *env, jbyteArray dir, jbyteArray allowedDir, jbyteArray protectedDir) {
        // エンジンはスレッドセーフではないので、呼び出しを1つずつにする
        std::lock_guard<std::mutex> guard(lock_);
        std::string allowed = ToString(env, allowedDir);
        std::string guarded = ToString(env, protectedDir);
        if (!guarded.empty()) pono_sandbox_protect(guarded.c_str());
        if (!allowed.empty()) pono_sandbox_allow(allowed.c_str());

        jsize len;
        char *buf = CopyToMalloc(env, dir, &len);
        if (buf == nullptr) return 0;
        long id;
        try {
            id = engine_.load(buf, len);
        } catch (...) {
            id = -1;
        }
        if (id >= 0) {
            allowedDirs_[id] = allowed;
            return static_cast<jlong>(id) + 1;
        }
        if (!allowed.empty()) pono_sandbox_forget(allowed.c_str());
        return 0;
    }

    jbyteArray Request(JNIEnv *env, jlong handle, jbyteArray req) {
        std::lock_guard<std::mutex> guard(lock_);
        jsize len;
        char *in = CopyToMalloc(env, req, &len);
        if (in == nullptr) return nullptr;
        long resLen = len;
        char *res;
        try {
            res = engine_.request(static_cast<long>(handle - 1), in, &resLen);
        } catch (...) {
            res = nullptr;
        }
        if (res == nullptr) return nullptr;
        if (!engine_.requestFreesInput) free(in);
        if (resLen < 0 || resLen > 0x7FFFFFFFL) {
            free(res);
            return nullptr;
        }
        jbyteArray out = env->NewByteArray(static_cast<jsize>(resLen));
        if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(resLen), reinterpret_cast<jbyte *>(res));
        free(res);
        return out;
    }

    jboolean Unload(jlong handle) {
        std::lock_guard<std::mutex> guard(lock_);
        long id = static_cast<long>(handle - 1);
        bool ok;
        try {
            ok = engine_.unload(id);
        } catch (...) {
            ok = false;
        }
        std::map<long, std::string>::iterator it = allowedDirs_.find(id);
        if (it != allowedDirs_.end()) {
            if (!it->second.empty()) pono_sandbox_forget(it->second.c_str());
            allowedDirs_.erase(it);
        }
        return ok ? JNI_TRUE : JNI_FALSE;
    }

private:
    static char *CopyToMalloc(JNIEnv *env, jbyteArray array, jsize *len) {
        *len = env->GetArrayLength(array);
        char *buf = static_cast<char *>(malloc(static_cast<size_t>(*len) + 1));
        if (buf == nullptr) return nullptr;
        env->GetByteArrayRegion(array, 0, *len, reinterpret_cast<jbyte *>(buf));
        buf[*len] = '\0';
        return buf;
    }

    static std::string ToString(JNIEnv *env, jbyteArray array) {
        if (array == nullptr) return std::string();
        jsize len = env->GetArrayLength(array);
        std::string s(static_cast<size_t>(len), '\0');
        if (len > 0) env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(&s[0]));
        return s;
    }

    ShioriEngine engine_;
    std::mutex lock_;
    std::map<long, std::string> allowedDirs_;
};

}  // namespace pono

// Kotlin の net.pono.shiori.yaya.<CLASS>（load / request / unload を持つ object）の入口を作る
#define PONO_DEFINE_SHIORI_JNI(CLASS, ENGINE)                                                                       \
    static pono::ShioriJni g_shiori_jni(ENGINE);                                                                    \
    extern "C" JNIEXPORT jlong JNICALL Java_net_pono_shiori_yaya_##CLASS##_load(                                    \
        JNIEnv *env, jobject, jbyteArray dir, jbyteArray allowedDir, jbyteArray protectedDir) {                     \
        return g_shiori_jni.Load(env, dir, allowedDir, protectedDir);                                              \
    }                                                                                                               \
    extern "C" JNIEXPORT jbyteArray JNICALL Java_net_pono_shiori_yaya_##CLASS##_request(                            \
        JNIEnv *env, jobject, jlong handle, jbyteArray req) {                                                       \
        return g_shiori_jni.Request(env, handle, req);                                                             \
    }                                                                                                               \
    extern "C" JNIEXPORT jboolean JNICALL Java_net_pono_shiori_yaya_##CLASS##_unload(JNIEnv *, jobject, jlong handle) { \
        return g_shiori_jni.Unload(handle);                                                                        \
    }
