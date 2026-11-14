// The small Win32 surface used by nashi's utility/interpreter code, backed by POSIX.
// nashi's DLL loader, Windows CRT replacement and Windows threads are not built.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cwchar>
#include <utility>

using UINT = unsigned int;
using DWORD = uint32_t;
using WORD = uint16_t;
using BOOL = int;
using HANDLE = FILE*;
constexpr BOOL TRUE = 1, FALSE = 0;
constexpr UINT CP_UTF8 = 65001, CP_ACP = 0;
#define INVALID_HANDLE_VALUE reinterpret_cast<HANDLE>(static_cast<intptr_t>(-1))
constexpr DWORD INVALID_FILE_ATTRIBUTES = 0xffffffffu;
constexpr DWORD GENERIC_READ = 0x80000000u, GENERIC_WRITE = 0x40000000u, FILE_APPEND_DATA = 4;
constexpr DWORD FILE_SHARE_READ = 1, FILE_SHARE_WRITE = 2, FILE_SHARE_DELETE = 4;
constexpr DWORD OPEN_EXISTING = 3, CREATE_ALWAYS = 2, OPEN_ALWAYS = 4;
constexpr DWORD FILE_ATTRIBUTE_NORMAL = 0x80, FILE_ATTRIBUTE_DIRECTORY = 0x10;
constexpr DWORD ERROR_FILE_NOT_FOUND = 2, ERROR_PATH_NOT_FOUND = 3, ERROR_SHARING_VIOLATION = 32, ERROR_LOCK_VIOLATION = 33;
constexpr DWORD MOVEFILE_REPLACE_EXISTING = 1, MOVEFILE_WRITE_THROUGH = 8, FILE_END = 2;
constexpr int GetFileExInfoStandard = 0;
union LARGE_INTEGER { int64_t QuadPart; struct { uint32_t LowPart; int32_t HighPart; }; };
struct FILETIME { DWORD dwLowDateTime, dwHighDateTime; };
struct SYSTEMTIME { WORD wYear, wMonth, wDayOfWeek, wDay, wHour, wMinute, wSecond, wMilliseconds; };
struct WIN32_FILE_ATTRIBUTE_DATA { DWORD dwFileAttributes; FILETIME ftCreationTime, ftLastAccessTime, ftLastWriteTime; DWORD nFileSizeHigh, nFileSizeLow; };

int MultiByteToWideChar(UINT, DWORD, const char*, int, wchar_t*, int);
int WideCharToMultiByte(UINT, DWORD, const wchar_t*, int, char*, int, const char*, BOOL*);
HANDLE CreateFileW(const wchar_t*, DWORD, DWORD, void*, DWORD, DWORD, HANDLE);
BOOL CloseHandle(HANDLE);
BOOL GetFileSizeEx(HANDLE, LARGE_INTEGER*);
BOOL ReadFile(HANDLE, void*, DWORD, DWORD*, void*);
BOOL WriteFile(HANDLE, const void*, DWORD, DWORD*, void*);
BOOL FlushFileBuffers(HANDLE);
DWORD SetFilePointer(HANDLE, long, long*, DWORD);
BOOL MoveFileExW(const wchar_t*, const wchar_t*, DWORD);
BOOL DeleteFileW(const wchar_t*);
DWORD GetFileAttributesW(const wchar_t*);
BOOL GetFileAttributesExW(const wchar_t*, int, WIN32_FILE_ATTRIBUTE_DATA*);
DWORD GetLastError();
// Match upstream's unsigned long timestamp fields on both ILP32 and LP64.
unsigned long GetTickCount();
DWORD GetCurrentProcessId();
BOOL QueryPerformanceCounter(LARGE_INTEGER*);
void GetLocalTime(SYSTEMTIME*);
void Sleep(DWORD);

template<size_t N, typename... A> int sprintf_s(char (&buf)[N], const char* fmt, A... args) {
    return std::snprintf(buf, N, fmt, args...);
}
template<size_t N> int strcpy_s(char (&buf)[N], const char* src) {
    if (std::strlen(src) >= N) { buf[0] = 0; return 1; }
    std::memcpy(buf, src, std::strlen(src) + 1); return 0;
}
