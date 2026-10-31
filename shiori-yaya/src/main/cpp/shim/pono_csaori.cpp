// csaori（Windows の SAORI の土台。csaori_base.cpp・csaori_util.cpp）の、Android で要る分を書き直したもの。
// 要求の読み方・答えの書き方は csaori と同じにし、文字コードの変換は pono_iconv（CP932・EUC-JP ⇄ UTF-8）を使う。
// pono_saori_guest.cpp からの load / request / unload を、SAORI ごとの CreateInstance で作ったクラスにつなぐ。

#include <stdlib.h>
#include <wctype.h>

#include <algorithm>
#include <string>

#include "csaori.h"
#include "iconv.h"

CSAORIBase *pSaori = nullptr;
extern CSAORIBase *CreateInstance(void);

namespace {

int CompareNoCase(const wchar_t *a, const wchar_t *b, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        wint_t x = towlower(static_cast<wint_t>(a[i]));
        wint_t y = towlower(static_cast<wint_t>(b[i]));
        if (x != y) return x < y ? -1 : 1;
        if (x == 0) return 0;
    }
    return 0;
}

bool EqualsNoCase(const string_t &a, const wchar_t *b) {
    std::wstring bs(b);
    return a.size() == bs.size() && CompareNoCase(a.c_str(), b, a.size()) == 0;
}

std::string Utf32ToUtf8(const wchar_t *s) {
    std::string out;
    for (; *s != 0; ++s) {
        uint32_t c = static_cast<uint32_t>(*s);
        if (c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) c = 0xFFFD;
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

// 壊れた並びは U+FFFD にする
string_t Utf8ToUtf32(const std::string &s) {
    string_t out;
    size_t i = 0;
    while (i < s.size()) {
        unsigned char b = static_cast<unsigned char>(s[i]);
        int n = b < 0x80 ? 0 : (b & 0xE0) == 0xC0 ? 1 : (b & 0xF0) == 0xE0 ? 2 : (b & 0xF8) == 0xF0 ? 3 : -1;
        if (n < 0 || (n > 0 && i + static_cast<size_t>(n) >= s.size())) {
            out += static_cast<wchar_t>(0xFFFD);
            ++i;
            continue;
        }
        uint32_t c = n == 0 ? b : n == 1 ? (b & 0x1F) : n == 2 ? (b & 0x0F) : (b & 0x07);
        bool ok = true;
        for (int k = 1; k <= n; ++k) {
            unsigned char t = static_cast<unsigned char>(s[i + k]);
            if ((t & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            c = (c << 6) | (t & 0x3F);
        }
        if (!ok) {
            out += static_cast<wchar_t>(0xFFFD);
            ++i;
            continue;
        }
        out += static_cast<wchar_t>(c);
        i += n + 1;
    }
    return out;
}

// pono_iconv で [from] → [to]（どちらかは UTF-8）。変換できなければ空
std::string Convert(const std::string &in, const char *to, const char *from) {
    iconv_t cd = iconv_open(to, from);
    if (cd == reinterpret_cast<iconv_t>(-1)) return std::string();
    std::string src = in;
    std::string out(src.size() * 3 + 4, '\0');
    char *ip = &src[0];
    size_t il = src.size();
    char *op = &out[0];
    size_t ol = out.size();
    size_t r = iconv(cd, &ip, &il, &op, &ol);
    iconv_close(cd);
    if (r == static_cast<size_t>(-1)) return std::string();
    out.resize(out.size() - ol);
    return out;
}

const char *IconvName(unsigned int cp) {
    switch (cp) {
        case CP_EUCJP:
            return "EUC-JP";
        case CP_UTF8:
            return "UTF-8";
        default:
            // ISO-2022-JP は扱えないので、CP932 として読む
            return "CP932";
    }
}

}  // namespace

namespace SAORI_FUNC {

string_t::size_type getLine(string_t &line, const string_t &src, string_t::size_type pos) {
    string_t::size_type end = src.find(L"\r\n", pos);
    if (end == string_t::npos) {
        line = src.substr(pos);
        return string_t::npos;
    }
    line = src.substr(pos, end - pos);
    return end + 2;
}

string_t getResultString(int code) {
    switch (code) {
        case 200:
            return L"OK";
        case 204:
            return L"No Content";
        case 400:
            return L"Bad Request";
        default:
            return L"Internal Server Error";
    }
}

template <typename S>
static S ReplaceAllImpl(S s, const S &find, const S &replace) {
    if (find.empty()) return s;
    typename S::size_type pos = 0;
    while ((pos = s.find(find, pos)) != S::npos) {
        s.replace(pos, find.size(), replace);
        pos += replace.size();
    }
    return s;
}

string_t replaceAll(string_t string, const string_t &find, const string_t &replace) { return ReplaceAllImpl(string, find, replace); }

std::string replaceAll(std::string string, const std::string &find, const std::string &replace) { return ReplaceAllImpl(string, find, replace); }

std::string UnicodeToMultiByte(const wchar_t *source, unsigned int codePage, DWORD) {
    std::string utf8 = Utf32ToUtf8(source);
    if (codePage == CP_UTF8) return utf8;
    return Convert(utf8, IconvName(codePage), "UTF-8");
}

string_t MultiByteToUnicode(const char *source, unsigned int codePage, DWORD) {
    std::string in(source);
    if (codePage != CP_UTF8) in = Convert(in, "UTF-8", IconvName(codePage));
    return Utf8ToUtf32(in);
}

string_t CodePagetoString(unsigned int cp) {
    switch (cp) {
        case CP_EUCJP:
            return L"EUC-JP";
        case CP_ISO2022JP:
            return L"ISO-2022-JP";
        case CP_UTF8:
            return L"UTF-8";
        default:
            return L"Shift_JIS";
    }
}

unsigned int StringtoCodePage(const char *str) {
    std::string s;
    for (; *str != '\0' && *str != '\r' && *str != '\n'; ++str) s += static_cast<char>(tolower(static_cast<unsigned char>(*str)));
    while (!s.empty() && s.back() == ' ') s.pop_back();
    if (s == "utf-8") return CP_UTF8;
    if (s == "euc-jp" || s == "x-euc-jp") return CP_EUCJP;
    if (s == "iso-2022-jp") return CP_ISO2022JP;
    return CP_SJIS;
}

}  // namespace SAORI_FUNC

// 「EXECUTE SAORI/1.0」の1行目と、Argument[n]・ID・その他のヘッダ（SecurityLevel・Sender・Charset は名前をそろえる）
bool CSAORIInput::parseString(const string_t &src) {
    const string_t &atag = base.s_saori_argument();
    string_t line;
    string_t::size_type pos = SAORI_FUNC::getLine(line, src, 0);
    string_t::size_type def = line.find(base.s_saori_def());
    if (def == string_t::npos) return false;
    cmd = line.substr(0, def);
    args.clear();
    opts.clear();
    id.clear();
    while (pos != string_t::npos) {
        pos = SAORI_FUNC::getLine(line, src, pos);
        string_t::size_type colon = line.find(L": ");
        if (colon == string_t::npos || colon == 0) continue;
        string_t k = line.substr(0, colon);
        string_t v = line.substr(colon + 2);
        if (k.size() > atag.size() && CompareNoCase(k.c_str(), atag.c_str(), atag.size()) == 0) {
            const wchar_t *digits = k.c_str() + atag.size();
            if (!iswdigit(static_cast<wint_t>(*digits))) continue;
            long ord = wcstol(digits, nullptr, 10);
            if (ord < 0 || ord > 4096) continue;
            if (args.size() <= static_cast<size_t>(ord)) args.resize(static_cast<size_t>(ord) + 1);
            args[static_cast<size_t>(ord)] = v;
        } else if (EqualsNoCase(k, L"ID")) {
            id = v;
        } else {
            if (EqualsNoCase(k, L"securitylevel")) k = L"SecurityLevel";
            else if (EqualsNoCase(k, L"sender")) k = L"Sender";
            else if (EqualsNoCase(k, L"charset")) k = L"Charset";
            opts[k] = v;
        }
    }
    return true;
}

void CSAORIOutput::setResultEmpty() {
    if (result_code == SAORIRESULT_OK || result_code == SAORIRESULT_FORCE_OK) {
        result_code = SAORIRESULT_NO_CONTENT;
        result.clear();
        values.clear();
    }
}

string_t CSAORIOutput::toString() {
    if (result_code == SAORIRESULT_FORCE_OK) {
        result_code = SAORIRESULT_OK;
    } else if (result.empty() && values.empty() && opts.empty() && result_code == SAORIRESULT_OK) {
        result_code = SAORIRESULT_NO_CONTENT;
    }
    string_t dest = base.s_saori_version() + L" " + std::to_wstring(static_cast<int>(result_code)) + L" " +
                    SAORI_FUNC::getResultString(result_code) + L"\r\n";
    dest += L"Charset: " + SAORI_FUNC::CodePagetoString(codepage) + L"\r\n";
    dest += base.s_saori_result() + L": " + result + L"\r\n";
    for (const auto &o : opts) dest += o.first + L": " + o.second + L"\r\n";
    for (size_t i = 0; i < values.size(); ++i) {
        // 値の中の改行は 0x01 にする（ヘッダを壊さない）
        string_t v = SAORI_FUNC::replaceAll(values[i], L"\r\n", L"\x01");
        v = SAORI_FUNC::replaceAll(v, L"\r", L"\x01");
        v = SAORI_FUNC::replaceAll(v, L"\n", L"\x01");
        dest += base.s_saori_value() + std::to_wstring(i) + L": " + v + L"\r\n";
    }
    dest += L"\r\n";
    return dest;
}

std::string CSAORIBase::request(const std::string &rq) {
    call_id = call_id + 1 >= 0x7fffffffU ? 1 : call_id + 1;
    // Charset ヘッダで文字コードを決める（無ければ Shift_JIS）
    std::string lower = rq;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return static_cast<char>(tolower(c)); });
    unsigned int cp = CP_SJIS;
    std::string::size_type at = lower.find("\ncharset: ");
    if (at != std::string::npos) cp = SAORI_FUNC::StringtoCodePage(lower.c_str() + at + 10);

    CSAORIInput in(*this);
    in.codepage = cp;
    in.opts[L"SecurityLevel"] = L"Local";
    bool parsed = in.parseString(SAORI_FUNC::MultiByteToUnicode(rq, cp));
    CSAORIOutput out(*this);
    out.codepage = cp;
    out.result_code = SAORIRESULT_INTERNAL_SERVER_ERROR;
    if (parsed) {
        if (in.cmd == L"GET Version") {
            out.result_code = SAORIRESULT_FORCE_OK;
        } else if (EqualsNoCase(in.cmd, L"EXECUTE") || EqualsNoCase(in.cmd, L"GET") || EqualsNoCase(in.cmd, L"NOTIFY")) {
            const string_t &sec = in.opts[L"SecurityLevel"];
            exec_before(in, out);
            if (sec.empty() || EqualsNoCase(sec, L"local")) {
                exec(in, out);
            } else if (!exec_insecure(in, out)) {
                out.result_code = SAORIRESULT_INTERNAL_SERVER_ERROR;
            }
            exec_after(in, out);
            if (EqualsNoCase(in.cmd, L"NOTIFY")) out.setResultEmpty();
        } else {
            out.result_code = SAORIRESULT_BAD_REQUEST;
        }
    }
    return SAORI_FUNC::UnicodeToMultiByte(out.toString(), out.codepage);
}

void CSAORIBase::setModulePath(const std::string &str, bool isUTF8) {
    module_path = SAORI_FUNC::MultiByteToUnicode(str, isUTF8 ? CP_UTF8 : CP_OEMCP);
}

// 相対パスは SAORI のフォルダからにする
std::string CSAORIBase::checkAndModifyPath(const std::string &path) {
    if (!path.empty() && path[0] == '/') return path;
    return SAORI_FUNC::UnicodeToMultiByte(module_path, CP_UTF8) + path;
}

string_t CSAORIBase::checkAndModifyPathW(const string_t &path) {
    if (!path.empty() && path[0] == L'/') return path;
    return module_path + path;
}

// ---- csaori.cpp の分（CSAORI の決まった文字列。CUSTOM_CREATE_INSTANCE のときの空の exec / load / unload） ----

const string_t &CSAORI::s_saori_version(void) const {
    static const string_t s = L"SAORI/1.0";
    return s;
}

const string_t &CSAORI::s_saori_def(void) const {
    static const string_t s = L" SAORI/";
    return s;
}

const string_t &CSAORI::s_saori_argument(void) const {
    static const string_t s = L"Argument";
    return s;
}

const string_t &CSAORI::s_saori_value(void) const {
    static const string_t s = L"Value";
    return s;
}

const string_t &CSAORI::s_saori_result(void) const {
    static const string_t s = L"Result";
    return s;
}

void CSAORI::exec(const CSAORIInput &, CSAORIOutput &) {}

bool CSAORI::unload() { return true; }

bool CSAORI::load() { return true; }

// ---- pono_saori_guest.cpp からの橋渡し ----

bool pono_guest_saori_load(const std::string &dir) {
    if (pSaori == nullptr) pSaori = CreateInstance();
    if (pSaori == nullptr) return false;
    pSaori->setModulePath(dir, true);
    return pSaori->load();
}

std::string pono_guest_saori_request(const std::string &request) {
    if (pSaori == nullptr) return "SAORI/1.0 500 Internal Server Error\r\n\r\n";
    return pSaori->request(request);
}

bool pono_guest_saori_unload() {
    if (pSaori == nullptr) return true;
    bool ok = pSaori->unload();
    delete pSaori;
    pSaori = nullptr;
    return ok;
}
