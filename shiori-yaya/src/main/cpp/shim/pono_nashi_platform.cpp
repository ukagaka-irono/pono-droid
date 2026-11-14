// Android port of the OS services called by nashi. File access goes through pono_sandbox.
#include "compat/nashi/windows.h"
#include "iconv.h"
#include <algorithm>
#include <cerrno>
#include <codecvt>
#include <locale>
#include <string>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace {
std::string Convert(const std::string& in, const char* to, const char* from) {
    iconv_t cd = iconv_open(to, from);
    if (cd == reinterpret_cast<iconv_t>(-1)) return {};
    std::string out;
    char* src = const_cast<char*>(in.data());
    size_t left = in.size();
    while (left) {
        char buf[256]; char* dst = buf; size_t room = sizeof(buf);
        size_t result = iconv(cd, &src, &left, &dst, &room);
        out.append(buf, sizeof(buf) - room);
        if (result == static_cast<size_t>(-1) && errno != E2BIG) { out += '?'; ++src; --left; }
    }
    iconv_close(cd); return out;
}
std::string Path(const wchar_t* path) {
    std::wstring_convert<std::codecvt_utf8<wchar_t>> codec;
    auto out = codec.to_bytes(path);
    std::replace(out.begin(), out.end(), '\\', '/');
    return out;
}
uint64_t MonoNanos() { timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t); return uint64_t(t.tv_sec) * 1000000000 + t.tv_nsec; }
}

int MultiByteToWideChar(UINT cp, DWORD, const char* src, int len, wchar_t* dst, int cap) {
    if (!src || len == 0 || len < -1 || cap < 0) return 0;
    try {
        std::string in(src, len < 0 ? std::strlen(src) + 1 : size_t(len));
        if (cp != CP_UTF8) in = Convert(in, "UTF-8", "CP932");
        std::wstring_convert<std::codecvt_utf8<wchar_t>> codec;
        auto out = codec.from_bytes(in);
        if (!dst || cap == 0) return static_cast<int>(out.size());
        if (out.size() > size_t(cap)) return 0;
        std::copy(out.begin(), out.end(), dst); return static_cast<int>(out.size());
    } catch (...) { return 0; }
}
int WideCharToMultiByte(UINT cp, DWORD, const wchar_t* src, int len, char* dst, int cap, const char*, BOOL* used) {
    if (used) *used = FALSE;
    if (!src || len == 0 || len < -1 || cap < 0) return 0;
    try {
        std::wstring_convert<std::codecvt_utf8<wchar_t>> codec;
        auto out = codec.to_bytes(src, src + (len < 0 ? std::wcslen(src) + 1 : size_t(len)));
        if (cp != CP_UTF8) out = Convert(out, "CP932", "UTF-8");
        if (!dst || cap == 0) return static_cast<int>(out.size());
        if (out.size() > size_t(cap)) return 0;
        std::copy(out.begin(), out.end(), dst); return static_cast<int>(out.size());
    } catch (...) { return 0; }
}
HANDLE CreateFileW(const wchar_t* path, DWORD access, DWORD, void*, DWORD creation, DWORD, HANDLE) {
    const char* mode = access == FILE_APPEND_DATA ? "ab" : creation == CREATE_ALWAYS ? "wb" : "rb";
    FILE* f = std::fopen(Path(path).c_str(), mode);
    return f ? f : INVALID_HANDLE_VALUE;
}
BOOL CloseHandle(HANDLE h) { return std::fclose(h) == 0; }
BOOL GetFileSizeEx(HANDLE h, LARGE_INTEGER* size) {
    struct stat st{}; if (fstat(fileno(h), &st) != 0 || st.st_size < 0) return FALSE;
    size->QuadPart = st.st_size; return TRUE;
}
BOOL ReadFile(HANDLE h, void* buf, DWORD size, DWORD* count, void*) { *count = std::fread(buf, 1, size, h); return !std::ferror(h); }
BOOL WriteFile(HANDLE h, const void* buf, DWORD size, DWORD* count, void*) { *count = std::fwrite(buf, 1, size, h); return *count == size; }
BOOL FlushFileBuffers(HANDLE h) { return std::fflush(h) == 0 && fsync(fileno(h)) == 0; }
DWORD SetFilePointer(HANDLE h, long offset, long*, DWORD) { return std::fseek(h, offset, SEEK_END) == 0 ? std::ftell(h) : 0xffffffffu; }
BOOL MoveFileExW(const wchar_t* from, const wchar_t* to, DWORD flags) {
    const auto target = Path(to);
    struct stat st{};
    if (!(flags & MOVEFILE_REPLACE_EXISTING) && lstat(target.c_str(), &st) == 0) { errno = EEXIST; return FALSE; }
    return std::rename(Path(from).c_str(), target.c_str()) == 0;
}
BOOL DeleteFileW(const wchar_t* path) { return std::remove(Path(path).c_str()) == 0; }
DWORD GetFileAttributesW(const wchar_t* path) {
    struct stat st{}; if (stat(Path(path).c_str(), &st) != 0) return INVALID_FILE_ATTRIBUTES;
    return S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
}
BOOL GetFileAttributesExW(const wchar_t* path, int, WIN32_FILE_ATTRIBUTE_DATA* data) {
    struct stat st{}; if (stat(Path(path).c_str(), &st) != 0) return FALSE;
    *data = {};
    uint64_t stamp = uint64_t(st.st_mtim.tv_sec) * 1000000000 + st.st_mtim.tv_nsec;
    data->ftLastWriteTime = {DWORD(stamp), DWORD(stamp >> 32)};
    data->nFileSizeLow = DWORD(st.st_size); data->nFileSizeHigh = DWORD(uint64_t(st.st_size) >> 32);
    data->dwFileAttributes = S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
    return TRUE;
}
DWORD GetLastError() { return errno == ENOENT ? ERROR_FILE_NOT_FOUND : errno == ENOTDIR ? ERROR_PATH_NOT_FOUND : errno == EBUSY ? ERROR_SHARING_VIOLATION : 5; }
unsigned long GetTickCount() { return static_cast<unsigned long>(MonoNanos() / 1000000); }
DWORD GetCurrentProcessId() { return getpid(); }
BOOL QueryPerformanceCounter(LARGE_INTEGER* value) { value->QuadPart = MonoNanos(); return TRUE; }
void GetLocalTime(SYSTEMTIME* value) {
    timespec now{}; clock_gettime(CLOCK_REALTIME, &now); tm local{}; localtime_r(&now.tv_sec, &local);
    *value = {WORD(local.tm_year + 1900), WORD(local.tm_mon + 1), WORD(local.tm_wday), WORD(local.tm_mday),
              WORD(local.tm_hour), WORD(local.tm_min), WORD(local.tm_sec), WORD(now.tv_nsec / 1000000)};
}
void Sleep(DWORD ms) { timespec remaining{time_t(ms / 1000), long(ms % 1000) * 1000000}; while (nanosleep(&remaining, &remaining) != 0 && errno == EINTR) {} }
