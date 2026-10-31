// quickshiori（JavaScript の SHIORI。QuickJS-ng）を Android で動かすための部品。
//   - shim/compat/quickshiori/windows.h の関数の実装
//   - JS の import "./ukadll.dll"・"./sqlite3.so" などのネイティブのモジュール（Windows では別の DLL）は、
//     libquickshiori.so に入れたものを返す（js_module_loader を -Wl,--wrap で横取り）。ほかの .so は
//     quickjs-libc が dlopen しようとするが、サンドボックスが止める
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <sys/stat.h>
#include <time.h>
#include <wchar.h>

#include <cctype>
#include <string>

#include "compat/quickshiori/windows.h"
#include "iconv.h"
#include "quickjs.h"

namespace {

// UTF-8 ⇄ CP932（pono_iconv）。変換できないところは '?' にする（Windows の既定と同じ）
std::string Iconv(const std::string &in, const char *to, const char *from) {
    iconv_t cd = iconv_open(to, from);
    if (cd == reinterpret_cast<iconv_t>(-1)) return in;
    std::string out;
    size_t i = 0;
    while (i < in.size()) {
        char buf[256];
        char *src = const_cast<char *>(in.data()) + i;
        size_t left = in.size() - i;
        char *dst = buf;
        size_t room = sizeof(buf);
        size_t r = iconv(cd, &src, &left, &dst, &room);
        out.append(buf, sizeof(buf) - room);
        size_t used = (in.size() - i) - left;
        i += used;
        if (r == static_cast<size_t>(-1) && errno != E2BIG) {
            out += '?';
            i += 1;
        }
    }
    iconv_close(cd);
    return out;
}

bool IsUtf8(UINT codepage) { return codepage == CP_UTF8; }

// UTF-8 を 1 文字ずつ UTF-32 にする（壊れたバイトは U+FFFD）
std::u32string DecodeUtf8(const std::string &s) {
    std::u32string out;
    for (size_t i = 0; i < s.size();) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        int n = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 0;
        if (n == 0 || i + n > s.size()) {
            out += U'�';
            i += 1;
            continue;
        }
        char32_t cp = n == 1 ? c : c & (0x7F >> n);
        for (int k = 1; k < n; ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        out += cp;
        i += n;
    }
    return out;
}

std::string EncodeUtf8(const wchar_t *s, size_t n) {
    std::string out;
    for (size_t i = 0; i < n; ++i) {
        uint32_t c = static_cast<uint32_t>(s[i]);
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    return out;
}

}  // namespace

extern "C" {

HGLOBAL GlobalAlloc(UINT, size_t bytes) { return malloc(bytes > 0 ? bytes : 1); }

HGLOBAL GlobalFree(HGLOBAL mem) {
    free(mem);
    return nullptr;
}

// srclen が -1 なら NUL まで（NUL も数える）。dstlen が 0 なら要る長さを返す
int MultiByteToWideChar(UINT codepage, DWORD, const char *src, int srclen, wchar_t *dst, int dstlen) {
    if (src == nullptr) return 0;
    std::string bytes(src, srclen < 0 ? strlen(src) + 1 : static_cast<size_t>(srclen));
    std::string utf8 = IsUtf8(codepage) ? bytes : Iconv(bytes, "UTF-8", "CP932");
    std::u32string wide = DecodeUtf8(utf8);
    if (dstlen == 0) return static_cast<int>(wide.size());
    if (static_cast<size_t>(dstlen) < wide.size()) return 0;
    for (size_t i = 0; i < wide.size(); ++i) dst[i] = static_cast<wchar_t>(wide[i]);
    return static_cast<int>(wide.size());
}

int WideCharToMultiByte(UINT codepage, DWORD, const wchar_t *src, int srclen, char *dst, int dstlen, const char *,
                        BOOL *useddefault) {
    if (src == nullptr) return 0;
    std::string utf8 = EncodeUtf8(src, srclen < 0 ? wcslen(src) + 1 : static_cast<size_t>(srclen));
    std::string bytes = IsUtf8(codepage) ? utf8 : Iconv(utf8, "CP932", "UTF-8");
    if (useddefault != nullptr) *useddefault = FALSE;
    if (dstlen == 0) return static_cast<int>(bytes.size());
    if (static_cast<size_t>(dstlen) < bytes.size()) return 0;
    memcpy(dst, bytes.data(), bytes.size());
    return static_cast<int>(bytes.size());
}

void OutputDebugStringA(const char *text) { __android_log_print(ANDROID_LOG_DEBUG, "quickshiori", "%s", text); }

void OutputDebugStringW(const wchar_t *text) {
    std::string utf8 = EncodeUtf8(text, wcslen(text));
    __android_log_print(ANDROID_LOG_DEBUG, "quickshiori", "%s", utf8.c_str());
}

void GetLocalTime(SYSTEMTIME *t) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    t->wYear = static_cast<WORD>(tm.tm_year + 1900);
    t->wMonth = static_cast<WORD>(tm.tm_mon + 1);
    t->wDayOfWeek = static_cast<WORD>(tm.tm_wday);
    t->wDay = static_cast<WORD>(tm.tm_mday);
    t->wHour = static_cast<WORD>(tm.tm_hour);
    t->wMinute = static_cast<WORD>(tm.tm_min);
    t->wSecond = static_cast<WORD>(tm.tm_sec);
    t->wMilliseconds = static_cast<WORD>(ts.tv_nsec / 1000000);
}

DWORD GetFileAttributesA(const char *path) {
    struct stat st;
    if (path == nullptr || stat(path, &st) != 0) return INVALID_FILE_ATTRIBUTES;
    return S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
}

// 区切りの \ は / にする
HMODULE LoadLibraryA(const char *path) {
    if (path == nullptr) return nullptr;
    std::string p(path);
    for (char &c : p) {
        if (c == '\\') c = '/';
    }
    return dlopen(p.c_str(), RTLD_NOW);
}

FARPROC GetProcAddress(HMODULE module, const char *name) { return dlsym(module, name); }

BOOL FreeLibrary(HMODULE module) { return dlclose(module) == 0; }

DWORD GetLastError(void) { return static_cast<DWORD>(errno); }

// ---- ネイティブの JS モジュール ----

JSModuleDef *pono_qs_init_ukadll(JSContext *ctx, const char *module_name);  // module/ukadll（名前を変えてある）
JSModuleDef *js_init_module_sqlite3(JSContext *ctx, const char *module_name);  // 3rd/qjs-ng-sqlite3
JSModuleDef *__real_js_module_loader(JSContext *ctx, const char *module_name, void *opaque, JSValueConst attributes);

JSModuleDef *__wrap_js_module_loader(JSContext *ctx, const char *module_name, void *opaque, JSValueConst attributes) {
    std::string base(module_name);
    size_t slash = base.find_last_of("/\\");
    if (slash != std::string::npos) base = base.substr(slash + 1);
    for (char &c : base) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
    // ukadll.d.ts の名前（'ukadll'・'ukadll.dll'）と、qjs-ng-sqlite3 の README・test.js の名前
    if (base == "ukadll" || base == "ukadll.dll" || base == "ukadll.so") return pono_qs_init_ukadll(ctx, module_name);
    if (base == "sqlite3.dll" || base == "sqlite3.so" || base == "quickjs_sqlite3.dll" || base == "quickjs_sqlite3.so") {
        return js_init_module_sqlite3(ctx, module_name);
    }
    return __real_js_module_loader(ctx, module_name, opaque, attributes);
}

}  // extern "C"
