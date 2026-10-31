// pasta（辞書を .pasta と Lua で書く Rust の SHIORI）。Windows の load / request / unload は Windows でしか作られないので、
// どの OS でも書き出される shiori4 の「版 3 の口」（shiori_get_api(3)）を使う。中身は src/main/rust/pasta。
//
// 版 3 の決まり（shiori4_abi の include/shiori4_abi.h）:
//   load は絶対パス・末尾に区切りのある UTF-8 のディレクトリ。インスタンスごとに別の pasta（いくつでも持てる）
//   request は SHIORI/3.0 の要求の全文（UTF-8）を渡し、応答の全文を受け取る。応答のバイト列はホスト（ここ）が
//   copy_from_slice で作り、release で放す
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "jni_common.h"

namespace {

// shiori4_abi.h と同じレイアウト（版 3 で使う分）
typedef int32_t ShioriStatus;

struct ShioriBytes {
    const uint8_t *ptr;
    size_t len;
    void *owner;
    void (*release)(ShioriBytes *bytes);
};

struct ShioriHostApi {
    uint32_t struct_size;
    uint32_t abi_version;
    void *ctx;
    ShioriStatus (*request)(void *ctx, const uint8_t *req, size_t len, ShioriBytes *out);
    ShioriStatus (*post)(void *ctx, const uint8_t *msg, size_t len);
    void (*log)(void *ctx, int32_t level, const uint8_t *msg, size_t len);
    void (*copy_from_slice)(void *ctx, const uint8_t *ptr, size_t len, ShioriBytes *out);
};

struct ShioriApi {
    uint32_t struct_size;
    uint32_t abi_version;
    void *(*load)(const ShioriHostApi *host, const uint8_t *dir, size_t dir_len);
    void (*unload)(void *inst);
    ShioriStatus (*request)(void *inst, const uint8_t *req, size_t len, ShioriBytes *out);
    ShioriStatus (*notify)(void *inst, const uint8_t *req, size_t len);
};

static_assert(sizeof(ShioriBytes) == 4 * sizeof(void *), "ShioriBytes size");
static_assert(offsetof(ShioriHostApi, copy_from_slice) == 8 + 4 * sizeof(void *), "ShioriHostApi.copy_from_slice offset");
static_assert(sizeof(ShioriHostApi) == 8 + 5 * sizeof(void *), "ShioriHostApi size");
static_assert(offsetof(ShioriApi, notify) == 8 + 3 * sizeof(void *), "ShioriApi.notify offset");

constexpr uint32_t kAbiVersion3 = 3;

}  // namespace

extern "C" const ShioriApi *shiori_get_api(uint32_t requested);

namespace {

void ReleaseBytes(ShioriBytes *bytes) {
    free(const_cast<uint8_t *>(bytes->ptr));
    memset(bytes, 0, sizeof(*bytes));
}

// 応答を malloc したバッファに写す（写せなければ空のまま。SHIORI は out をそのまま返す）
void CopyFromSlice(void *, const uint8_t *ptr, size_t len, ShioriBytes *out) {
    uint8_t *buf = static_cast<uint8_t *>(malloc(len > 0 ? len : 1));
    if (buf == nullptr) {
        memset(out, 0, sizeof(*out));
        return;
    }
    if (len > 0) memcpy(buf, ptr, len);
    out->ptr = buf;
    out->len = len;
    out->owner = nullptr;
    out->release = ReleaseBytes;
}

// 版 3 のホストのテーブルは copy_from_slice だけを持つ
const ShioriHostApi kHost = {sizeof(ShioriHostApi), kAbiVersion3, nullptr, nullptr, nullptr, nullptr, CopyFromSlice};

const ShioriApi *Api() {
    static const ShioriApi *api = shiori_get_api(kAbiVersion3);
    return api;
}

std::map<long, void *> g_instances;
long g_next = 0;

long PastaLoad(char *dir, long len) {
    std::string path(dir, static_cast<size_t>(len > 0 ? len : 0));
    free(dir);
    const ShioriApi *api = Api();
    if (api == nullptr || api->abi_version != kAbiVersion3) return -1;
    // 末尾に区切りが要る
    if (path.empty() || path.back() != '/') path += '/';
    void *inst = api->load(&kHost, reinterpret_cast<const uint8_t *>(path.data()), path.size());
    if (inst == nullptr) return -1;
    long id = g_next++;
    g_instances[id] = inst;
    return id;
}

char *PastaRequest(long id, char *req, long *len) {
    std::map<long, void *>::iterator it = g_instances.find(id);
    size_t reqLen = static_cast<size_t>(*len > 0 ? *len : 0);
    if (it == g_instances.end()) {
        free(req);
        return nullptr;
    }
    ShioriBytes out = {nullptr, 0, nullptr, nullptr};
    ShioriStatus status = Api()->request(it->second, reinterpret_cast<const uint8_t *>(req), reqLen, &out);
    free(req);
    char *res = nullptr;
    if (status >= 0 && out.ptr != nullptr) {
        res = static_cast<char *>(malloc(out.len + 1));
        if (res != nullptr) {
            memcpy(res, out.ptr, out.len);
            res[out.len] = '\0';
            *len = static_cast<long>(out.len);
        }
    }
    if (out.release != nullptr) out.release(&out);
    return res;
}

bool PastaUnload(long id) {
    std::map<long, void *>::iterator it = g_instances.find(id);
    if (it == g_instances.end()) return false;
    Api()->unload(it->second);
    g_instances.erase(it);
    return true;
}

const pono::ShioriEngine kPasta = {PastaLoad, PastaRequest, PastaUnload, true};

}  // namespace

PONO_DEFINE_SHIORI_JNI(PastaNative, kPasta)
