// ゴーストの辞書は信頼できないコードなので、SHIORI（YAYA・里々・華和梨・Kagari の Lua）が呼ぶ libc の関数を
// リンク時の -Wl,--wrap で横取りして、できることを絞る。サブモジュールは書き換えない。
//   - dlopen / fork / exec / system / popen / chdir: いつも失敗にする。LOADLIB / REQUESTLIB / EXECUTE、
//     里々・華和梨の SAORI、Lua の C モジュールや os.execute で、ゴースト同梱のネイティブコードやシェルが
//     動かないようにする（Android に SAORI は無いので困らない）。ただし本体の内蔵モジュール（internal_*）の名前の
//     dlopen は、本体に要求を渡す関数（pono_saori.cpp）を返す（YAYA の LOADLIB / FUNCTIONEX と華和梨の SAORI から使える）
//   - パスを取る関数: ゴーストのディレクトリ（allow）の外への書き込みと、
//     アプリの領域（protect）の読み取りを EACCES にする。システムのファイルを読むのは許す
//     （タイムゾーンやロケールのため）。fd を取る関数は、開けた時点で検査済みなので触らない
#include "pono_sandbox.h"
#include "pono_saori.h"

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <vector>

extern "C" {
int __real_open(const char *path, int flags, ...);
int __real___open_2(const char *path, int flags);
FILE *__real_fopen(const char *path, const char *mode);
FILE *__real_freopen(const char *path, const char *mode, FILE *stream);
int __real_openat(int dirfd, const char *path, int flags, ...);
int __real___openat_2(int dirfd, const char *path, int flags);
int __real_truncate(const char *path, off_t length);
int __real_mkstemp(char *path);
DIR *__real_opendir(const char *path);
int __real_stat(const char *path, struct stat *st);
int __real_lstat(const char *path, struct stat *st);
int __real_access(const char *path, int mode);
int __real_unlink(const char *path);
int __real_remove(const char *path);
int __real_rename(const char *from, const char *to);
int __real_mkdir(const char *path, mode_t mode);
int __real_rmdir(const char *path);
ssize_t __real_readlink(const char *path, char *buf, size_t size);
int __real_utimes(const char *path, const struct timeval times[2]);
}

namespace {

std::mutex g_lock;
// realpath 済み、末尾の / なし
std::vector<std::string> g_allowed;
std::vector<std::string> g_protected;

void Denied(const char *what, const char *path) {
    __android_log_print(ANDROID_LOG_WARN, "pono", "sandbox: denied %s %s", what, path ? path : "(null)");
}

// . と .. を字面で畳んで絶対パスにする
std::string Normalize(const char *path) {
    std::string in;
    if (path[0] != '/') {
        char cwd[PATH_MAX];
        in = (getcwd(cwd, sizeof(cwd)) ? std::string(cwd) : std::string("/")) + "/" + path;
    } else {
        in = path;
    }
    std::vector<std::string> parts;
    size_t i = 0;
    while (i <= in.size()) {
        size_t j = in.find('/', i);
        if (j == std::string::npos) j = in.size();
        std::string seg = in.substr(i, j - i);
        if (seg == "..") {
            if (!parts.empty()) parts.pop_back();
        } else if (!seg.empty() && seg != ".") {
            parts.push_back(seg);
        }
        i = j + 1;
    }
    std::string out;
    for (const std::string &p : parts) out += "/" + p;
    return out.empty() ? "/" : out;
}

// シンボリックリンクも解決する。まだ無いファイルは、ある所までを realpath して残りをつなぐ
// （realpath は libc の中で完結するので、ここで横取りした関数は呼ばれない）
std::string Canonical(const char *path) {
    std::string norm = Normalize(path);
    std::string head = norm;
    std::string tail;
    char buf[PATH_MAX];
    while (true) {
        if (realpath(head.c_str(), buf)) {
            std::string r = buf;
            if (tail.empty()) return r;
            return (r == "/" ? std::string() : r) + tail;
        }
        size_t slash = head.rfind('/');
        if (head == "/" || slash == std::string::npos) return norm;
        tail = head.substr(slash) + tail;
        head = slash == 0 ? "/" : head.substr(0, slash);
    }
}

bool Under(const std::string &path, const std::string &root) {
    if (root == "/") return true;
    return path == root ||
           (path.size() > root.size() && path.compare(0, root.size(), root) == 0 && path[root.size()] == '/');
}

bool Allowed(const char *path, bool write, const char *what) {
    if (path == nullptr) return true;  // libc に EFAULT を返させる
    std::string canon = Canonical(path);
    std::lock_guard<std::mutex> guard(g_lock);
    for (const std::string &a : g_allowed) {
        if (Under(canon, a)) return true;
    }
    if (write) {
        Denied(what, path);
        return false;
    }
    for (const std::string &p : g_protected) {
        if (Under(canon, p)) {
            Denied(what, path);
            return false;
        }
    }
    return true;
}

bool WritesWith(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
}

}  // namespace

// 内蔵モジュール 1 つ分の「ライブラリ」。dlopen はこれのアドレスを返し、dlsym はモジュールごとの関数を返す
namespace {

const int kSaoriSlots = 16;

struct SaoriSlot {
    char name[64];
};

std::mutex g_saori_lock;
SaoriSlot g_saori_slots[kSaoriSlots];
int g_saori_used = 0;

SaoriSlot *SaoriSlotFor(const char *module) {
    std::lock_guard<std::mutex> guard(g_saori_lock);
    for (int i = 0; i < g_saori_used; ++i) {
        if (strcmp(g_saori_slots[i].name, module) == 0) return &g_saori_slots[i];
    }
    if (g_saori_used >= kSaoriSlots) return nullptr;
    SaoriSlot *slot = &g_saori_slots[g_saori_used++];
    strncpy(slot->name, module, sizeof(slot->name) - 1);
    return slot;
}

int SaoriSlotIndex(void *handle) {
    SaoriSlot *p = static_cast<SaoriSlot *>(handle);
    return p >= g_saori_slots && p < g_saori_slots + kSaoriSlots ? static_cast<int>(p - g_saori_slots) : -1;
}

// SAORI の約束どおり、入力はこちらで free し、応答は malloc したものを返す（呼んだ側が free する）
char *SaoriRequest(int n, char *h, long *len) {
    long outLen = 0;
    char *out = pono_saori_direct(g_saori_slots[n].name, h, *len, &outLen);
    free(h);
    if (out == nullptr) {
        static const char kFailure[] = "SAORI/1.0 500 Internal Server Error\r\nCharset: UTF-8\r\n\r\n";
        out = strdup(kFailure);
        outLen = out != nullptr ? static_cast<long>(sizeof(kFailure) - 1) : 0;
    }
    *len = outLen;
    return out;
}

// YAYA（POSIX）の <名前>_saori_load / unload / request（ID 付き）
template <int N> long SaoriLoadWithId(char *h, long) {
    free(h);
    return N + 1;
}
template <int N> int SaoriUnloadWithId(long) { return 1; }
template <int N> char *SaoriRequestWithId(long, char *h, long *len) { return SaoriRequest(N, h, len); }

// 華和梨などの load / unload / request
template <int N> int SaoriLoad(char *h, long) {
    free(h);
    return 1;
}
template <int N> int SaoriUnload() { return 1; }
template <int N> char *SaoriRequestPlain(char *h, long *len) { return SaoriRequest(N, h, len); }

#define PONO_SAORI_SLOTS(F) F(0), F(1), F(2), F(3), F(4), F(5), F(6), F(7), F(8), F(9), F(10), F(11), F(12), F(13), F(14), F(15)
#define PONO_LOAD_ID(n) reinterpret_cast<void *>(&SaoriLoadWithId<n>)
#define PONO_UNLOAD_ID(n) reinterpret_cast<void *>(&SaoriUnloadWithId<n>)
#define PONO_REQUEST_ID(n) reinterpret_cast<void *>(&SaoriRequestWithId<n>)
#define PONO_LOAD(n) reinterpret_cast<void *>(&SaoriLoad<n>)
#define PONO_UNLOAD(n) reinterpret_cast<void *>(&SaoriUnload<n>)
#define PONO_REQUEST(n) reinterpret_cast<void *>(&SaoriRequestPlain<n>)
void *const kLoadWithId[kSaoriSlots] = {PONO_SAORI_SLOTS(PONO_LOAD_ID)};
void *const kUnloadWithId[kSaoriSlots] = {PONO_SAORI_SLOTS(PONO_UNLOAD_ID)};
void *const kRequestWithId[kSaoriSlots] = {PONO_SAORI_SLOTS(PONO_REQUEST_ID)};
void *const kLoad[kSaoriSlots] = {PONO_SAORI_SLOTS(PONO_LOAD)};
void *const kUnload[kSaoriSlots] = {PONO_SAORI_SLOTS(PONO_UNLOAD)};
void *const kRequest[kSaoriSlots] = {PONO_SAORI_SLOTS(PONO_REQUEST)};

bool EndsWith(const char *s, const char *tail) {
    size_t a = strlen(s), b = strlen(tail);
    return a >= b && strcmp(s + a - b, tail) == 0;
}

}  // namespace

extern "C" {

void pono_sandbox_allow(const char *dir) {
    std::string canon = Canonical(dir);
    std::lock_guard<std::mutex> guard(g_lock);
    g_allowed.push_back(canon);
}

void pono_sandbox_forget(const char *dir) {
    std::string canon = Canonical(dir);
    std::lock_guard<std::mutex> guard(g_lock);
    for (size_t i = 0; i < g_allowed.size(); ++i) {
        if (g_allowed[i] == canon) {
            g_allowed.erase(g_allowed.begin() + i);
            return;
        }
    }
}

void pono_sandbox_protect(const char *dir) {
    std::string canon = Canonical(dir);
    std::lock_guard<std::mutex> guard(g_lock);
    for (const std::string &p : g_protected) {
        if (p == canon) return;
    }
    g_protected.push_back(canon);
}

// ---- ネイティブコードとプロセス ----

void *__wrap_dlopen(const char *name, int) {
    char module[64];
    if (name != nullptr && pono_saori_module_name(name, module, sizeof(module))) {
        SaoriSlot *slot = SaoriSlotFor(module);
        if (slot != nullptr) return slot;
    }
    Denied("dlopen", name);
    return nullptr;
}

void *__wrap_dlsym(void *handle, const char *symbol) {
    int n = SaoriSlotIndex(handle);
    if (n < 0 || symbol == nullptr) return nullptr;
    if (strcmp(symbol, "load") == 0) return kLoad[n];
    if (strcmp(symbol, "unload") == 0) return kUnload[n];
    if (strcmp(symbol, "request") == 0) return kRequest[n];
    if (EndsWith(symbol, "_saori_load")) return kLoadWithId[n];
    if (EndsWith(symbol, "_saori_unload")) return kUnloadWithId[n];
    if (EndsWith(symbol, "_saori_request")) return kRequestWithId[n];
    return nullptr;
}

// 開けるのは内蔵モジュールだけなので、閉じるものも無い
int __wrap_dlclose(void *handle) { return SaoriSlotIndex(handle) >= 0 ? 0 : -1; }

pid_t __wrap_fork(void) {
    Denied("fork", "");
    errno = EPERM;
    return -1;
}

pid_t __wrap_vfork(void) {
    Denied("vfork", "");
    errno = EPERM;
    return -1;
}

// Lua の os.execute や華和梨の system などから。シェルは動かさない（-1 は「シェルを起動できなかった」）
int __wrap_system(const char *command) {
    // command が NULL なら「シェルがあるか」の問い合わせ。無いと答える
    if (command == nullptr) return 0;
    Denied("system", command);
    errno = EPERM;
    return -1;
}

FILE *__wrap_popen(const char *command, const char *) {
    Denied("popen", command);
    errno = EPERM;
    return nullptr;
}

int __wrap_execv(const char *path, char *const[]) {
    Denied("execv", path);
    errno = EPERM;
    return -1;
}

int __wrap_execve(const char *path, char *const[], char *const[]) {
    Denied("execve", path);
    errno = EPERM;
    return -1;
}

int __wrap_execvp(const char *file, char *const[]) {
    Denied("execvp", file);
    errno = EPERM;
    return -1;
}

int __wrap_execl(const char *path, const char *, ...) {
    Denied("execl", path);
    errno = EPERM;
    return -1;
}

int __wrap_execlp(const char *file, const char *, ...) {
    Denied("execlp", file);
    errno = EPERM;
    return -1;
}

int __wrap_execle(const char *path, const char *, ...) {
    Denied("execle", path);
    errno = EPERM;
    return -1;
}

int __wrap_posix_spawn(pid_t *, const char *path, const void *, const void *, char *const[], char *const[]) {
    Denied("posix_spawn", path);
    return EPERM;
}

int __wrap_posix_spawnp(pid_t *, const char *file, const void *, const void *, char *const[], char *const[]) {
    Denied("posix_spawnp", file);
    return EPERM;
}

int __wrap_chdir(const char *path) {
    Denied("chdir", path);
    errno = EPERM;
    return -1;
}

// ---- ファイル ----

int __wrap_open(const char *path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (!Allowed(path, WritesWith(flags), "open")) {
        errno = EACCES;
        return -1;
    }
    return __real_open(path, flags, mode);
}

// FORTIFY が引数2つの open をこちらに置き換える
int __wrap___open_2(const char *path, int flags) {
    if (!Allowed(path, WritesWith(flags), "open")) {
        errno = EACCES;
        return -1;
    }
    return __real___open_2(path, flags);
}

FILE *__wrap_fopen(const char *path, const char *mode) {
    bool write = mode != nullptr && strpbrk(mode, "wa+") != nullptr;
    if (!Allowed(path, write, "fopen")) {
        errno = EACCES;
        return nullptr;
    }
    return __real_fopen(path, mode);
}

FILE *__wrap_freopen(const char *path, const char *mode, FILE *stream) {
    bool write = mode != nullptr && strpbrk(mode, "wa+") != nullptr;
    if (path != nullptr && !Allowed(path, write, "freopen")) {
        errno = EACCES;
        return nullptr;
    }
    return __real_freopen(path, mode, stream);
}

// openat はディレクトリの fd からの相対パスを取れる。どこを指すか確かめられないので、絶対パスのときだけ通す
int __wrap_openat(int dirfd, const char *path, int flags, ...) {
    int mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (path == nullptr || (path[0] != '/' && dirfd != AT_FDCWD) || !Allowed(path, WritesWith(flags), "openat")) {
        if (path != nullptr && path[0] != '/' && dirfd != AT_FDCWD) Denied("openat (relative to fd)", path);
        errno = EACCES;
        return -1;
    }
    return __real_openat(dirfd, path, flags, mode);
}

int __wrap___openat_2(int dirfd, const char *path, int flags) {
    if (path == nullptr || (path[0] != '/' && dirfd != AT_FDCWD) || !Allowed(path, WritesWith(flags), "openat")) {
        errno = EACCES;
        return -1;
    }
    return __real___openat_2(dirfd, path, flags);
}

int __wrap_truncate(const char *path, off_t length) {
    if (!Allowed(path, true, "truncate")) {
        errno = EACCES;
        return -1;
    }
    return __real_truncate(path, length);
}

// 一時ファイルは /data/local/tmp などアプリの外に作られうるので、ゴーストのディレクトリの中でだけ許す
int __wrap_mkstemp(char *path) {
    if (!Allowed(path, true, "mkstemp")) {
        errno = EACCES;
        return -1;
    }
    return __real_mkstemp(path);
}

// 名前の無い一時ファイル（Lua の io.tmpfile）。場所を選べないので作らない
FILE *__wrap_tmpfile(void) {
    Denied("tmpfile", "");
    errno = EACCES;
    return nullptr;
}

DIR *__wrap_opendir(const char *path) {
    if (!Allowed(path, false, "opendir")) {
        errno = EACCES;
        return nullptr;
    }
    return __real_opendir(path);
}

int __wrap_stat(const char *path, struct stat *st) {
    if (!Allowed(path, false, "stat")) {
        errno = EACCES;
        return -1;
    }
    return __real_stat(path, st);
}

int __wrap_lstat(const char *path, struct stat *st) {
    if (!Allowed(path, false, "lstat")) {
        errno = EACCES;
        return -1;
    }
    return __real_lstat(path, st);
}

int __wrap_access(const char *path, int mode) {
    if (!Allowed(path, (mode & W_OK) != 0, "access")) {
        errno = EACCES;
        return -1;
    }
    return __real_access(path, mode);
}

ssize_t __wrap_readlink(const char *path, char *buf, size_t size) {
    if (!Allowed(path, false, "readlink")) {
        errno = EACCES;
        return -1;
    }
    return __real_readlink(path, buf, size);
}

int __wrap_unlink(const char *path) {
    if (!Allowed(path, true, "unlink")) {
        errno = EACCES;
        return -1;
    }
    return __real_unlink(path);
}

int __wrap_remove(const char *path) {
    if (!Allowed(path, true, "remove")) {
        errno = EACCES;
        return -1;
    }
    return __real_remove(path);
}

int __wrap_rename(const char *from, const char *to) {
    if (!Allowed(from, true, "rename") || !Allowed(to, true, "rename")) {
        errno = EACCES;
        return -1;
    }
    return __real_rename(from, to);
}

int __wrap_mkdir(const char *path, mode_t mode) {
    if (!Allowed(path, true, "mkdir")) {
        errno = EACCES;
        return -1;
    }
    return __real_mkdir(path, mode);
}

int __wrap_rmdir(const char *path) {
    if (!Allowed(path, true, "rmdir")) {
        errno = EACCES;
        return -1;
    }
    return __real_rmdir(path);
}

int __wrap_utimes(const char *path, const struct timeval times[2]) {
    if (!Allowed(path, true, "utimes")) {
        errno = EACCES;
        return -1;
    }
    return __real_utimes(path, times);
}

}  // extern "C"
