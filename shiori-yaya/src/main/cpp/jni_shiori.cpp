// YAYA の multi_load / multi_request / multi_unload を Kotlin から呼ぶための JNI ラッパー。
// バッファの所有権は YAYA の POSIX 版の約束に合わせる:
//   load: 渡したバッファは YAYA が free する
//   request: 失敗時は YAYA が入力を free することがある。成功時は入力も出力も呼び出し側が free する
#include <jni.h>
#include <stdlib.h>

#include "aya5.h"

namespace {

char *CopyToMalloc(JNIEnv *env, jbyteArray array, jsize *len) {
    *len = env->GetArrayLength(array);
    char *buf = static_cast<char *>(malloc(*len + 1));
    env->GetByteArrayRegion(array, 0, *len, reinterpret_cast<jbyte *>(buf));
    buf[*len] = '\0';
    return buf;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_net_pono_shiori_yaya_YayaNative_load(JNIEnv *env, jclass, jbyteArray dir) {
    jsize len;
    char *buf = CopyToMalloc(env, dir, &len);
    return multi_load(buf, len);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_net_pono_shiori_yaya_YayaNative_request(JNIEnv *env, jclass, jlong id, jbyteArray req) {
    jsize len;
    char *in = CopyToMalloc(env, req, &len);
    long resLen = len;
    char *res = multi_request(static_cast<long>(id), in, &resLen);
    if (res == NULL) {
        return NULL;
    }
    free(in);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(resLen));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(resLen), reinterpret_cast<jbyte *>(res));
    free(res);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_pono_shiori_yaya_YayaNative_unload(JNIEnv *, jclass, jlong id) {
    return multi_unload(static_cast<long>(id)) ? JNI_TRUE : JNI_FALSE;
}
