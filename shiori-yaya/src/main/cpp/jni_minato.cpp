// 湊（minato。辞書を .mnt で書く Rust の SHIORI）の loadu / request / unload。
// 中身は src/main/rust/minato（元のソースを cargo で staticlib にしたもの）。湊は 1 つのプロセスに 1 体だけ持つので、
// 新しく load したら前の分は湊の中で片付く（loadu が前の状態を保存して捨てる）。古い ID への request は失敗にし、
// 古い ID の unload は何もしない
#include "jni_common.h"

// 元の lib.rs の入口。バッファは malloc したもの（src/main/rust/pono-winapi の GlobalAlloc / GlobalFree）
extern "C" int loadu(void *h, long len);
extern "C" void *request(void *h, long *len);
extern "C" int unload();

namespace {

long g_current = 0;

// ディレクトリは UTF-8 で渡すので、Shift_JIS として読む load ではなく loadu を呼ぶ
long MinatoLoad(char *dir, long len) {
    if (loadu(dir, len) == 0) return -1;
    return ++g_current;
}

char *MinatoRequest(long id, char *req, long *len) {
    if (id != g_current) {
        free(req);
        return nullptr;
    }
    return static_cast<char *>(request(req, len));
}

bool MinatoUnload(long id) {
    if (id != g_current) return true;
    g_current = 0;
    return unload() != 0;
}

const pono::ShioriEngine kMinato = {MinatoLoad, MinatoRequest, MinatoUnload, true};

}  // namespace

PONO_DEFINE_SHIORI_JNI(MinatoNative, kMinato)
