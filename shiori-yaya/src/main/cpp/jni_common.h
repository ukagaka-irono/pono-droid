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
#include "shim/pono_saori.h"

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

    jlong Load(JNIEnv *env, jbyteArray dir, jbyteArray allowedDir, jbyteArray protectedDir, jbyteArray readableDirs) {
        // エンジンはスレッドセーフではないので、呼び出しを1つずつにする
        std::lock_guard<std::mutex> guard(lock_);
        pono_saori_attach(env);
        std::string allowed = ToString(env, allowedDir);
        std::string guarded = ToString(env, protectedDir);
        if (!guarded.empty()) pono_sandbox_protect(guarded.c_str());
        // 読むだけならよいディレクトリ（改行区切り。プラグインのときだけ）
        std::string readable = ToString(env, readableDirs);
        for (size_t i = 0; i < readable.size();) {
            size_t j = readable.find('\n', i);
            if (j == std::string::npos) j = readable.size();
            std::string one = readable.substr(i, j - i);
            if (!one.empty()) pono_sandbox_allow_read(one.c_str());
            i = j + 1;
        }
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
        pono_saori_attach(env);
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

namespace pono {

// SHIORI を SAORI として使うとき（ゴーストが yaya.dll・satori.dll などを SAORI として読む。YAYA as SAORI や
// ninix-saori の saori_yaya などと同じ）。shim/pono_sandbox.cpp の dlopen がこのライブラリを開き、
// pono_saori_guest.cpp と同じ pono_guest_* を呼ぶ。SAORI の要求は、そのままエンジンの request に渡す。
// SHIORI 側（ShioriJni）とは別の鍵にする（同じエンジンのゴーストが自分を SAORI として呼ぶと、ShioriJni の鍵を持ったまま来る）
class EngineSaori {
public:
    explicit EngineSaori(const ShioriEngine &engine) : engine_(engine) {}

    // SHIORI の側のサンドボックスと同じ決まりにする（\n で区切ったディレクトリ）
    static void Policy(const char *allowed, const char *guarded, const char *readable) {
        EachLine(guarded, pono_sandbox_protect);
        EachLine(allowed, pono_sandbox_allow);
        EachLine(readable, pono_sandbox_allow_read);
    }

    // [path] は malloc したもの（エンジンが free する）。ID（1 から）を返し、失敗なら 0
    long Load(char *path, long len) {
        std::lock_guard<std::mutex> guard(lock_);
        long id;
        try {
            id = engine_.load(path, len);
        } catch (...) {
            id = -1;
        }
        return id >= 0 ? id + 1 : 0;
    }

    // 入力はここで（またはエンジンが）free し、malloc した答えを返す
    char *Request(long id, char *request, long *len) {
        std::lock_guard<std::mutex> guard(lock_);
        char *res;
        try {
            res = engine_.request(id - 1, request, len);
        } catch (...) {
            res = nullptr;
        }
        if (res != nullptr && !engine_.requestFreesInput) free(request);
        return res;
    }

    int Unload(long id) {
        std::lock_guard<std::mutex> guard(lock_);
        try {
            return engine_.unload(id - 1) ? 1 : 0;
        } catch (...) {
            return 0;
        }
    }

private:
    template <typename F>
    static void EachLine(const char *text, F f) {
        if (text == nullptr) return;
        std::string s(text);
        size_t i = 0;
        while (i <= s.size()) {
            size_t j = s.find('\n', i);
            if (j == std::string::npos) j = s.size();
            std::string one = s.substr(i, j - i);
            if (!one.empty()) f(one.c_str());
            i = j + 1;
        }
    }

    ShioriEngine engine_;
    std::mutex lock_;
};

}  // namespace pono

// SHIORI を SAORI として読むときの入口（pono_guest_*）を作る
#define PONO_DEFINE_ENGINE_SAORI(ENGINE)                                                                      \
    static pono::EngineSaori g_engine_saori(ENGINE);                                                            \
    extern "C" __attribute__((visibility("default"))) void pono_guest_policy(const char *allowed,               \
                                                                            const char *guarded,               \
                                                                            const char *readable) {            \
        pono::EngineSaori::Policy(allowed, guarded, readable);                                                  \
    }                                                                                                           \
    extern "C" __attribute__((visibility("default"))) long pono_guest_load(char *path, long len) {              \
        return g_engine_saori.Load(path, len);                                                                  \
    }                                                                                                           \
    extern "C" __attribute__((visibility("default"))) char *pono_guest_request(long id, char *req, long *len) { \
        return g_engine_saori.Request(id, req, len);                                                            \
    }                                                                                                           \
    extern "C" __attribute__((visibility("default"))) int pono_guest_unload(long id) {                         \
        return g_engine_saori.Unload(id);                                                                       \
    }

// Kotlin の net.pono.shiori.yaya.<CLASS>（load / request / unload を持つ object）の入口を作る
#define PONO_DEFINE_SHIORI_JNI(CLASS, ENGINE)                                                                       \
    static pono::ShioriJni g_shiori_jni(ENGINE);                                                                    \
    extern "C" JNIEXPORT jlong JNICALL Java_net_pono_shiori_yaya_##CLASS##_load(                                    \
        JNIEnv *env, jobject, jbyteArray dir, jbyteArray allowedDir, jbyteArray protectedDir, jbyteArray readable) { \
        return g_shiori_jni.Load(env, dir, allowedDir, protectedDir, readable);                                    \
    }                                                                                                               \
    extern "C" JNIEXPORT jbyteArray JNICALL Java_net_pono_shiori_yaya_##CLASS##_request(                            \
        JNIEnv *env, jobject, jlong handle, jbyteArray req) {                                                       \
        return g_shiori_jni.Request(env, handle, req);                                                             \
    }                                                                                                               \
    extern "C" JNIEXPORT jboolean JNICALL Java_net_pono_shiori_yaya_##CLASS##_unload(JNIEnv *, jobject, jlong handle) { \
        return g_shiori_jni.Unload(handle);                                                                        \
    }
