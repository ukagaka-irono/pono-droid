// tukinami さんの Rust の SAORI の橋渡し。Rust の側（src/main/rust/pono_entry.rs）が出す pono_rust_saori_* を、
// pono_saori_guest.cpp からの呼び出しにつなぐ

#include <stddef.h>

#include <string>

extern "C" {
bool pono_rust_saori_load(const char *dir, size_t len);
bool pono_rust_saori_unload();
char *pono_rust_saori_request(const char *request, size_t len, size_t *out_len);
void pono_rust_saori_free(char *data, size_t len);
}

bool pono_guest_saori_load(const std::string &dir) { return pono_rust_saori_load(dir.data(), dir.size()); }

std::string pono_guest_saori_request(const std::string &request) {
    size_t len = 0;
    char *res = pono_rust_saori_request(request.data(), request.size(), &len);
    std::string out(res, len);
    pono_rust_saori_free(res, len);
    return out;
}

bool pono_guest_saori_unload() { return pono_rust_saori_unload(); }
