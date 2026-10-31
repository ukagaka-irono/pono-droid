// Tatakinov さんの SAORI（mahjong_nodoka・TexasHoldemUtil・saori_budoux）の橋渡し。どれも Windows の dll.cc が
// __load / __request / __unload を呼ぶだけなので、それをそのまま pono_saori_guest.cpp からの呼び出しにつなぐ。
// Windows の MultiByteToWideChar で書かれた conv（CP932 ⇄ UTF-8）も、ここで pono_iconv を使って作る。

#include <string>

#include "iconv.h"

bool __load(std::string path);
bool __unload();
std::string __request(std::string request);

bool pono_guest_saori_load(const std::string &dir) { return __load(dir); }

std::string pono_guest_saori_request(const std::string &request) { return __request(request); }

bool pono_guest_saori_unload() { return __unload(); }

// conv(文字列, 変換先のコードページ, 変換元のコードページ)。65001 は UTF-8、932 は CP932（Shift_JIS）。
// 扱えない組み合わせや変換できない文字があれば、元の Windows 版と同じく空を返す
std::string conv(std::string input, int to, int from) {
    if (to == from) return input;
    const char *toName = to == 65001 ? "UTF-8" : to == 932 ? "CP932" : nullptr;
    const char *fromName = from == 65001 ? "UTF-8" : from == 932 ? "CP932" : nullptr;
    if (toName == nullptr || fromName == nullptr) return std::string();
    iconv_t cd = iconv_open(toName, fromName);
    if (cd == reinterpret_cast<iconv_t>(-1)) return std::string();
    std::string out(input.size() * 3 + 4, '\0');
    char *in = &input[0];
    size_t inLeft = input.size();
    char *o = &out[0];
    size_t outLeft = out.size();
    size_t r = iconv(cd, &in, &inLeft, &o, &outLeft);
    iconv_close(cd);
    if (r == static_cast<size_t>(-1) || inLeft != 0) return std::string();
    out.resize(out.size() - outLeft);
    return out;
}
