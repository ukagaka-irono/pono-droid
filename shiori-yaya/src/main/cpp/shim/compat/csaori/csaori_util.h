// csaori（Windows の SAORI の土台）の csaori_util.h の代わり（Android 用）。MahjongUtil などが使う分だけ。
// 文字列は元と同じく wchar_t（Android では UTF-32）。実装は shim/pono_csaori.cpp
#pragma once

#include <map>
#include <sstream>
#include <string>
#include <vector>

#define SAORIAPI extern "C"
#define SAORICDECL

typedef unsigned long DWORD;
typedef wchar_t char_t;
typedef std::basic_string<char_t> string_t;

typedef enum {
    CP_SJIS = 932,
    CP_EUCJP = 20932,
    CP_ISO2022JP = 50220,
    CP_UTF8 = 65001
} CODEPAGE;

// Windows の「OEM の既定の文字コード」。日本語の Windows と同じく CP932 とする
#define CP_OEMCP 932

namespace SAORI_FUNC {
template <typename _Type>
string_t numToString(_Type num) {
    return std::to_wstring(num);
}
string_t inline intToString(int num) { return numToString(num); }
string_t::size_type getLine(string_t &, const string_t &, string_t::size_type);
string_t getResultString(int);
string_t replaceAll(string_t string, const string_t &find, const string_t &replace);
std::string replaceAll(std::string string, const std::string &find, const std::string &replace);
std::string UnicodeToMultiByte(const wchar_t *Source, unsigned int CodePage = CP_OEMCP, DWORD Flags = 0);
inline std::string UnicodeToMultiByte(const string_t &Source, unsigned int CodePage = CP_OEMCP, DWORD Flags = 0) {
    return UnicodeToMultiByte(Source.c_str(), CodePage, Flags);
}
string_t MultiByteToUnicode(const char *Source, unsigned int CodePage = CP_OEMCP, DWORD Flags = 0);
inline string_t MultiByteToUnicode(const std::string &Source, unsigned int CodePage = CP_OEMCP, DWORD Flags = 0) {
    return MultiByteToUnicode(Source.c_str(), CodePage, Flags);
}
string_t CodePagetoString(unsigned int cp);
unsigned int StringtoCodePage(const char *str);
}  // namespace SAORI_FUNC
