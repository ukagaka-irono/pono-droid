// 里々（SATORI）の satori_load / satori_request / satori_unload（ID ごとに別の里々）
#include "jni_common.h"

extern "C" int satori_load(char *i_data, long i_data_len);
extern "C" char *satori_request(int id, char *i_data, long *io_data_len);
extern "C" int satori_unload(int id);

namespace {

long SatoriLoad(char *dir, long len) {
    int id = satori_load(dir, len);
    return id > 0 ? id : -1;
}

char *SatoriRequest(long id, char *req, long *len) { return satori_request(static_cast<int>(id), req, len); }

bool SatoriUnload(long id) { return satori_unload(static_cast<int>(id)) != 0; }

const pono::ShioriEngine kSatori = {SatoriLoad, SatoriRequest, SatoriUnload, true};

}  // namespace

PONO_DEFINE_SHIORI_JNI(SatoriNative, kSatori)
