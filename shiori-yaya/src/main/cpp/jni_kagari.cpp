// Kagari（辞書を Lua で書く SHIORI）の kagari_load / kagari_request / kagari_unload（ID は 0 から）
#include "jni_common.h"

#include "kagari_unix.h"

namespace {

long KagariLoad(char *dir, long len) { return kagari_load(dir, len); }

char *KagariRequest(long id, char *req, long *len) { return kagari_request(static_cast<int>(id), req, len); }

bool KagariUnload(long id) { return kagari_unload(static_cast<int>(id)) >= 0; }

const pono::ShioriEngine kKagari = {KagariLoad, KagariRequest, KagariUnload, true};

}  // namespace

PONO_DEFINE_SHIORI_JNI(KagariNative, kKagari)
