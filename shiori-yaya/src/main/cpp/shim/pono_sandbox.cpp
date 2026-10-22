// ゴーストの辞書は信頼できないコードなので、YAYA（と同梱の SQLite）が呼ぶ libc の関数を
// リンク時の -Wl,--wrap で横取りして、できることを絞る。サブモジュールは書き換えない。
//   - dlopen / fork / chdir: いつも失敗にする。LOADLIB / REQUESTLIB / EXECUTE で
//     ゴースト同梱のネイティブコードやシェルが動かないようにする（Android に SAORI は無いので困らない）
//   - パスを取る関数: ゴーストのディレクトリ（allow）の外への書き込みと、
//     アプリの領域（protect）の読み取りを EACCES にする。システムのファイルを読むのは許す
//     （タイムゾーンやロケールのため）。fd を取る関数は、開けた時点で検査済みなので触らない
#include "pono_sandbox.h"

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
    Denied("dlopen", name);
    return nullptr;
}

void *__wrap_dlsym(void *, const char *) {
    return nullptr;
}

pid_t __wrap_fork(void) {
    Denied("fork", "");
    errno = EPERM;
    return -1;
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
