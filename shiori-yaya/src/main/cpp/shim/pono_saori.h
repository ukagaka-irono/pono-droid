// 内蔵 SAORI（SAORI over DirectSSTP の internal_*）を本体に渡す入口。pono_saori.cpp を参照。
#pragma once

#include <jni.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// SAORI/1.0 の要求（[len] バイト）を本体の内蔵モジュールに渡し、応答を malloc して返す（長さは [outLen]）。
// [module] は Module ヘッダが無いときのモジュール名（NULL でよい）。本体に渡せなければ NULL
char *pono_saori_direct(const char *module, const char *req, long len, long *outLen);

// 本体に渡せるか（Kotlin から SHIORI を呼んでいる途中か）
int pono_saori_direct_available(void);

// SAORI のファイル名（パスや .dll 付きでもよい）が内蔵モジュールなら、そのモジュール名（小文字）を [out] に入れて 1 を返す
int pono_saori_module_name(const char *path, char *out, size_t outSize);

#ifdef __cplusplus
}

// Kotlin の SaoriBridge を覚える。jni_common.h が load / request のたびに呼ぶ（2回目からは何もしない）
void pono_saori_attach(JNIEnv *env);
#endif
