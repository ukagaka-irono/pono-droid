// ゴーストに同梱される SAORI（nodoka.dll など）の代わりに、ソースから作った libsaori_<名前>.so の側に入れるもの。
// SHIORI の側（pono_sandbox.cpp の dlopen）がこのライブラリを開き、pono_guest_policy でサンドボックスの決まり（触ってよい
// ディレクトリ）を渡してから、pono_guest_load / request / unload を呼ぶ。このライブラリも同じ pono_sandbox.cpp を
// --wrap で入れているので、SAORI の中のファイル操作は SHIORI と同じ決まりで絞られる。
//
// SAORI の中身は、ソースごとの橋渡し（pono_saori_tatakinov.cpp など）が pono_guest_saori_* を作る。

#include <stdlib.h>
#include <string.h>

#include <string>

#include "shim/pono_sandbox.h"

// ソースごとの橋渡し。[dir] は DLL のあったディレクトリ（UTF-8、末尾は /）
bool pono_guest_saori_load(const std::string &dir);
std::string pono_guest_saori_request(const std::string &request);
bool pono_guest_saori_unload();

namespace {

// \n で区切ったディレクトリを1つずつ渡す
template <typename F>
void EachLine(const char *text, F f) {
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

bool g_loaded = false;

}  // namespace

#define PONO_EXPORT extern "C" __attribute__((visibility("default")))

// SHIORI の側のサンドボックスと同じ決まりにする（読み書きしてよい・読ませない・読むだけならよいディレクトリ）
PONO_EXPORT void pono_guest_policy(const char *allowed, const char *guarded, const char *readable) {
    EachLine(guarded, pono_sandbox_protect);
    EachLine(allowed, pono_sandbox_allow);
    EachLine(readable, pono_sandbox_allow_read);
}

// SAORI の load。[path] は malloc したもので、ここで free する。ID（1）を返し、失敗なら 0
PONO_EXPORT long pono_guest_load(char *path, long len) {
    std::string dir(path, static_cast<size_t>(len > 0 ? len : 0));
    free(path);
    // Windows の SAORI は、DLL のディレクトリの末尾に区切りが付いて渡される前提で書かれている
    if (dir.empty() || dir.back() != '/') dir += '/';
    if (!g_loaded) g_loaded = pono_guest_saori_load(dir);
    return g_loaded ? 1 : 0;
}

// SAORI の request。入力はここで free し、malloc した答えを返す（長さは [len] に）
PONO_EXPORT char *pono_guest_request(long, char *request, long *len) {
    std::string req(request, static_cast<size_t>(*len > 0 ? *len : 0));
    free(request);
    std::string res = g_loaded ? pono_guest_saori_request(req) : std::string("SAORI/1.0 500 Internal Server Error\r\n\r\n");
    char *out = static_cast<char *>(malloc(res.size() + 1));
    if (out == nullptr) {
        *len = 0;
        return nullptr;
    }
    memcpy(out, res.data(), res.size());
    out[res.size()] = '\0';
    *len = static_cast<long>(res.size());
    return out;
}

PONO_EXPORT int pono_guest_unload(long) {
    if (g_loaded) pono_guest_saori_unload();
    g_loaded = false;
    return 1;
}
