// ninix-saori（Tatakinov さん。ninix-kagari 用の SAORI）の橋渡し。Linux の .so の <名前>_saori_load / request / unload
// （ID ごとに別の SAORI）を、pono_saori_guest.cpp からの呼び出しにつなぐ。名前は PONO_NINIX_SAORI（saori_curl など）
#include <stdlib.h>
#include <string.h>

#include <string>

#ifndef PONO_NINIX_SAORI
#error "define PONO_NINIX_SAORI"
#endif

#define PONO_CAT2(a, b) a##b
#define PONO_CAT(a, b) PONO_CAT2(a, b)

extern "C" {
long PONO_CAT(PONO_NINIX_SAORI, _saori_load)(char *path, long len);
int PONO_CAT(PONO_NINIX_SAORI, _saori_unload)(long id);
char *PONO_CAT(PONO_NINIX_SAORI, _saori_request)(long id, char *request, long *len);
}

namespace {

long g_id = 0;

char *CopyToMalloc(const std::string &s) {
    char *buf = static_cast<char *>(malloc(s.size() + 1));
    if (buf == nullptr) return nullptr;
    memcpy(buf, s.data(), s.size());
    buf[s.size()] = '\0';
    return buf;
}

}  // namespace

// どれも渡したバッファは向こうが free する
bool pono_guest_saori_load(const std::string &dir) {
    char *buf = CopyToMalloc(dir);
    if (buf == nullptr) return false;
    g_id = PONO_CAT(PONO_NINIX_SAORI, _saori_load)(buf, static_cast<long>(dir.size()));
    return g_id != 0;
}

std::string pono_guest_saori_request(const std::string &request) {
    char *buf = CopyToMalloc(request);
    if (buf == nullptr) return "SAORI/1.0 500 Internal Server Error\r\n\r\n";
    long len = static_cast<long>(request.size());
    char *res = PONO_CAT(PONO_NINIX_SAORI, _saori_request)(g_id, buf, &len);
    if (res == nullptr) return "SAORI/1.0 500 Internal Server Error\r\n\r\n";
    std::string out(res, len > 0 ? static_cast<size_t>(len) : 0);
    free(res);
    return out;
}

bool pono_guest_saori_unload() {
    int r = PONO_CAT(PONO_NINIX_SAORI, _saori_unload)(g_id);
    g_id = 0;
    return r != 0;
}
