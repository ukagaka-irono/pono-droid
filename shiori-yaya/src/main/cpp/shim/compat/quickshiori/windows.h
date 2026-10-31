// quickshiori（と同梱の ukadll）が使う <windows.h> の代わり（Android 用）。使う分だけを同じ名前で用意する。
// 実装は shim/pono_quickshiori.cpp:
//   - GlobalAlloc / GlobalFree は malloc / free（POSIX 版の SHIORI と SAORI の約束）
//   - MultiByteToWideChar / WideCharToMultiByte は UTF-8 と CP932（CP_ACP・CP_OEMCP。日本語の Windows と同じ）。
//     wchar_t は Android では UTF-32
//   - LoadLibraryA / GetProcAddress / FreeLibrary は dlopen / dlsym / dlclose（サンドボックスを通る。開けるのは
//     ソースから作った SAORI と内蔵 SAORI だけ）
//   - OutputDebugString は logcat、GetLocalTime は localtime_r、GetFileAttributesA は stat
#pragma once

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

typedef int BOOL;
typedef unsigned short WORD;
typedef unsigned long DWORD;
typedef unsigned int UINT;
typedef void *HGLOBAL;
typedef void *HMODULE;
typedef void *FARPROC;
typedef const char *LPCSTR;
typedef char *LPSTR;
typedef const wchar_t *LPCWSTR;
typedef wchar_t *LPWSTR;

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#define __cdecl
#define __declspec(x) __attribute__((visibility("default")))

#define GMEM_FIXED 0
#define CP_ACP 0
#define CP_OEMCP 1
#define CP_UTF8 65001

#define INVALID_FILE_ATTRIBUTES ((DWORD)-1)
#define FILE_ATTRIBUTE_DIRECTORY 0x10
#define FILE_ATTRIBUTE_NORMAL 0x80

typedef struct {
    WORD wYear;
    WORD wMonth;
    WORD wDayOfWeek;
    WORD wDay;
    WORD wHour;
    WORD wMinute;
    WORD wSecond;
    WORD wMilliseconds;
} SYSTEMTIME;

#ifdef __cplusplus
extern "C" {
#endif

HGLOBAL GlobalAlloc(UINT flags, size_t bytes);
HGLOBAL GlobalFree(HGLOBAL mem);
int MultiByteToWideChar(UINT codepage, DWORD flags, const char *src, int srclen, wchar_t *dst, int dstlen);
int WideCharToMultiByte(UINT codepage, DWORD flags, const wchar_t *src, int srclen, char *dst, int dstlen,
                        const char *defchar, BOOL *useddefault);
void OutputDebugStringA(const char *text);
void OutputDebugStringW(const wchar_t *text);
void GetLocalTime(SYSTEMTIME *time);
DWORD GetFileAttributesA(const char *path);
HMODULE LoadLibraryA(const char *path);
FARPROC GetProcAddress(HMODULE module, const char *name);
BOOL FreeLibrary(HMODULE module);
DWORD GetLastError(void);

#ifdef __cplusplus
}
#endif
