// 華和梨（KAWARI）。SHIORI の load / request / unload は1つだけなので、華和梨のファクトリを直接呼んで ID ごとに分ける
#include "jni_common.h"

#include <string.h>

#include <string>

#include "config.h"
#include "shiori/kawari_shiori.h"

namespace {

long KawariLoad(char *dir, long len) {
    std::string path(dir, static_cast<size_t>(len));
    free(dir);
    unsigned int handle = TKawariShioriFactory::GetFactory().CreateInstance(path);
    return handle != 0 ? static_cast<long>(handle) : -1;
}

char *KawariRequest(long id, char *req, long *len) {
    std::string response = TKawariShioriFactory::GetFactory().RequestInstance(
        static_cast<unsigned int>(id), std::string(req, static_cast<size_t>(*len)));
    free(req);
    char *out = static_cast<char *>(malloc(response.size() + 1));
    if (out == nullptr) {
        *len = 0;
        return nullptr;
    }
    memcpy(out, response.c_str(), response.size() + 1);
    *len = static_cast<long>(response.size());
    return out;
}

bool KawariUnload(long id) { return TKawariShioriFactory::GetFactory().DisposeInstance(static_cast<unsigned int>(id)); }

const pono::ShioriEngine kKawari = {KawariLoad, KawariRequest, KawariUnload, true};

}  // namespace

PONO_DEFINE_SHIORI_JNI(KawariNative, kKawari)

// ゴーストがこの SHIORI を SAORI として読むとき（YAYA as SAORI など）
PONO_DEFINE_ENGINE_SAORI(kKawari)
