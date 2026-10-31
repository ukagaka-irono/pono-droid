// quickshiori（JavaScript の SHIORI。QuickJS-ng）の loadu / request / unload。中身はサブモジュール quickshiori を
// Windows の部分だけ shim/compat/quickshiori/windows.h に替えて作ったもの。quickshiori は 1 つのプロセスに 1 体だけ持つ
// （2 回目の loadu は何もしない）ので、新しく load するときは前の分を unload してから読む。古い ID への request は失敗にし、
// 古い ID の unload は何もしない
#include "jni_common.h"

// 元の quickshiori.cpp の入口。バッファは malloc したもの（GlobalAlloc / GlobalFree）
extern "C" int loadu(void *h, long len);
extern "C" void *request(void *h, long *len);
extern "C" int unload();

namespace {

long g_current = 0;

// ディレクトリは UTF-8 で渡すので、CP932 として読む load ではなく loadu を呼ぶ
long QuickShioriLoad(char *dir, long len) {
    if (g_current != 0) {
        unload();
        g_current = 0;
    }
    if (loadu(dir, len) == 0) return -1;
    return ++g_current;
}

char *QuickShioriRequest(long id, char *req, long *len) {
    if (id != g_current) {
        free(req);
        return nullptr;
    }
    return static_cast<char *>(request(req, len));
}

bool QuickShioriUnload(long id) {
    if (id != g_current) return true;
    g_current = 0;
    return unload() != 0;
}

const pono::ShioriEngine kQuickShiori = {QuickShioriLoad, QuickShioriRequest, QuickShioriUnload, true};

}  // namespace

PONO_DEFINE_SHIORI_JNI(QuickShioriNative, kQuickShiori)
