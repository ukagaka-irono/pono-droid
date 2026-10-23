// YAYA の multi_load / multi_request / multi_unload（ID は 1 から）
#include "jni_common.h"

#include "aya5.h"

namespace {

long YayaLoad(char *dir, long len) {
    long id = multi_load(dir, len);
    return id > 0 ? id : -1;
}

char *YayaRequest(long id, char *req, long *len) { return multi_request(id, req, len); }

bool YayaUnload(long id) { return multi_unload(id) != 0; }

// YAYA の request は、成功したときは入力を free しない
const pono::ShioriEngine kYaya = {YayaLoad, YayaRequest, YayaUnload, false};

}  // namespace

PONO_DEFINE_SHIORI_JNI(YayaNative, kYaya)
