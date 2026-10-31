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
#include <ctype.h>
#include <dirent.h>
#include <dlfcn.h>
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
int __real_unlinkat(int dirfd, const char *path, int flags);
int __real_fstatat(int dirfd, const char *path, struct stat *st, int flags);
int __real_symlink(const char *target, const char *linkpath);
int __real_remove(const char *path);
int __real_rename(const char *from, const char *to);
int __real_mkdir(const char *path, mode_t mode);
int __real_rmdir(const char *path);
ssize_t __real_readlink(const char *path, char *buf, size_t size);
int __real_utimes(const char *path, const struct timeval times[2]);
void *__real_dlopen(const char *name, int flags);
void *__real_dlsym(void *handle, const char *symbol);
}

namespace {

std::mutex g_lock;
// realpath 済み、末尾の / なし
std::vector<std::string> g_allowed;
std::vector<std::string> g_protected;
std::vector<std::string> g_readable;

void Denied(const char *what, const char *path) {
    __android_log_print(ANDROID_LOG_WARN, "pono", "sandbox: denied %s %s", what, path ? path : "(null)");
}

// 絶対パスにして、前から 1 段ずつ realpath でシンボリックリンクを解決する。.. は解決した後の親に戻る
// （/proc/self/root/.. や /data/user/0/.. は、字面で畳んだ所とは別の所を指すので、先に畳んではいけない）。
// まだ無い所から後ろは字面でつなぐ（無いディレクトリの下にリンクは無い）。
// realpath と getcwd は libc の中で完結するので、ここで横取りした関数は呼ばれない
std::string Canonical(const char *path) {
    std::string in;
    if (path[0] != '/') {
        char cwd[PATH_MAX];
        in = (getcwd(cwd, sizeof(cwd)) ? std::string(cwd) : std::string("/")) + "/" + path;
    } else {
        in = path;
    }
    std::string cur = "/";
    bool missing = false;
    char buf[PATH_MAX];
    size_t i = 0;
    while (i <= in.size()) {
        size_t j = in.find('/', i);
        if (j == std::string::npos) j = in.size();
        std::string seg = in.substr(i, j - i);
        i = j + 1;
        if (seg.empty() || seg == ".") continue;
        if (seg == "..") {
            size_t slash = cur.rfind('/');
            cur = slash == 0 ? "/" : cur.substr(0, slash);
            if (missing && realpath(cur.c_str(), buf)) {
                cur = buf;
                missing = false;
            }
            continue;
        }
        std::string next = (cur == "/" ? std::string() : cur) + "/" + seg;
        if (!missing && realpath(next.c_str(), buf)) {
            cur = buf;
        } else {
            cur = next;
            missing = true;
        }
    }
    return cur;
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
    for (const std::string &r : g_readable) {
        if (Under(canon, r)) return true;
    }
    for (const std::string &p : g_protected) {
        if (Under(canon, p)) {
            Denied(what, path);
            return false;
        }
    }
    return true;
}

// [path] が、読み書きしてよいディレクトリへ行く途中のディレクトリ（その祖先）か。stat などで中身ではなく
// あることだけを見るのは許す（pasta の glob はパターンの / から 1 段ずつ確かめるので、アプリの領域の
// ディレクトリ自体も見る）
bool OnPathToAllowed(const char *path) {
    if (path == nullptr) return false;
    std::string canon = Canonical(path);
    std::lock_guard<std::mutex> guard(g_lock);
    for (const std::string &a : g_allowed) {
        if (Under(a, canon)) return true;
    }
    return false;
}

bool WritesWith(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC | O_APPEND)) != 0;
}

}  // namespace

// 内蔵モジュール 1 つ分の「ライブラリ」。dlopen はこれのアドレスを返し、dlsym はモジュールごとの関数を返す
namespace {

const int kSaoriSlots = 32;

// 同じ名前のモジュールは同じ枠を使い、dlopen と dlclose の数で使っている数を数える。誰も使わなくなった枠は空けて使い回す
// （同じプロセスで前のゴーストが開いた分で枠が埋まり、後のゴーストの SAORI が黙って失敗しないように）
struct SaoriSlot {
    char name[64];
    int refs;
};

std::mutex g_saori_lock;
SaoriSlot g_saori_slots[kSaoriSlots];

SaoriSlot *SaoriSlotFor(const char *module) {
    std::lock_guard<std::mutex> guard(g_saori_lock);
    for (SaoriSlot &s : g_saori_slots) {
        if (s.refs > 0 && strcmp(s.name, module) == 0) {
            s.refs++;
            return &s;
        }
    }
    for (SaoriSlot &s : g_saori_slots) {
        if (s.refs == 0) {
            memset(s.name, 0, sizeof(s.name));
            strncpy(s.name, module, sizeof(s.name) - 1);
            s.refs = 1;
            return &s;
        }
    }
    return nullptr;
}

void SaoriSlotRelease(SaoriSlot *slot) {
    std::lock_guard<std::mutex> guard(g_saori_lock);
    if (slot->refs > 0) slot->refs--;
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

// 読み込んだことを本体に知らせる（GET Version を送り、答えは捨てる）。互換モジュールの houchi は、読み込んだだけで働き出す
void SaoriNotifyLoad(int n) {
    static const char kVersion[] = "GET Version SAORI/1.0\r\nCharset: UTF-8\r\nSender: pono-droid\r\n\r\n";
    long outLen = 0;
    char *out = pono_saori_direct(g_saori_slots[n].name, kVersion, static_cast<long>(sizeof(kVersion) - 1), &outLen);
    free(out);
}

// YAYA（POSIX）の <名前>_saori_load / unload / request（ID 付き）
template <int N> long SaoriLoadWithId(char *h, long) {
    free(h);
    SaoriNotifyLoad(N);
    return N + 1;
}
template <int N> int SaoriUnloadWithId(long) { return 1; }
template <int N> char *SaoriRequestWithId(long, char *h, long *len) { return SaoriRequest(N, h, len); }

// 華和梨などの load / unload / request
template <int N> int SaoriLoad(char *h, long) {
    free(h);
    SaoriNotifyLoad(N);
    return 1;
}
template <int N> int SaoriUnload() { return 1; }
template <int N> char *SaoriRequestPlain(char *h, long *len) { return SaoriRequest(N, h, len); }

#define PONO_SAORI_SLOTS(F) F(0), F(1), F(2), F(3), F(4), F(5), F(6), F(7), F(8), F(9), F(10), F(11), F(12), F(13), F(14), F(15), \
    F(16), F(17), F(18), F(19), F(20), F(21), F(22), F(23), F(24), F(25), F(26), F(27), F(28), F(29), F(30), F(31)
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

// ---- ソースから作った SAORI（libsaori_<名前>.so。pono_saori_guest.cpp を入れたもの） ----
// ゴーストに同梱の DLL の名前（拡張子を除き小文字）で見分け、その .so を開く。開いた .so には、このプロセスの
// サンドボックスの決まり（触ってよいディレクトリ）を渡す。.so の中も同じ pono_sandbox.cpp で絞られている

struct NativeSaoriLib {
    const char *dll;
    const char *lib;
};

const NativeSaoriLib kNativeSaoris[] = {
    {"nodoka", "libsaori_nodoka.so"},
    {"texasholdemutil", "libsaori_texasholdem.so"},
    {"budoux", "libsaori_budoux.so"},
    {"mahjongutil", "libsaori_mahjongutil.so"},
    {"calcs", "libsaori_calcs.so"},
    {"japanesegengou", "libsaori_japanesegengou.so"},
    {"resizedpngmini", "libsaori_resizedpngmini.so"},
    {"saori_qrcode", "libsaori_qrcode.so"},
    {"resizedpng", "libsaori_resizedpng.so"},
    {"piechart", "libsaori_piechart.so"},
    // SHIORI を SAORI として（YAYA as SAORI・ninix-saori の saori_yaya などと同じ）。中身は SHIORI の .so（jni_common.h の
    // PONO_DEFINE_ENGINE_SAORI）
    {"yaya", "libyaya.so"},
    {"aya", "libyaya.so"},
    {"aya5", "libyaya.so"},
    {"satori", "libsatori.so"},
    {"kawari", "libkawari.so"},
    {"kawari8", "libkawari.so"},
};

const int kNativeSlots = 8;

struct NativeSlot {
    const NativeSaoriLib *def;
    int refs;
    long id;
    long (*load)(char *, long);
    char *(*request)(long, char *, long *);
    int (*unload)(long);
};

NativeSlot g_native[kNativeSlots];

// [path] の DLL がソースから作った SAORI なら、その定め。違えば nullptr
const NativeSaoriLib *NativeFor(const char *path) {
    const char *base = path;
    for (const char *p = path; *p != '\0'; ++p) {
        if (*p == '/' || *p == '\\') base = p + 1;
    }
    std::string name(base);
    for (char &c : name) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
    if (name.size() > 4 && name.compare(name.size() - 4, 4, ".dll") == 0) name.erase(name.size() - 4);
    for (const NativeSaoriLib &n : kNativeSaoris) {
        if (name == n.dll) return &n;
    }
    return nullptr;
}

std::string JoinLines(const std::vector<std::string> &dirs) {
    std::string out;
    for (const std::string &d : dirs) {
        if (!out.empty()) out += '\n';
        out += d;
    }
    return out;
}

// .so を開き、決まりを渡して、使う関数を取り出す。同じものはもう開いている枠を使う
NativeSlot *NativeOpen(const NativeSaoriLib *def) {
    std::lock_guard<std::mutex> guard(g_saori_lock);
    for (NativeSlot &s : g_native) {
        if (s.refs > 0 && s.def == def) {
            s.refs++;
            return &s;
        }
    }
    NativeSlot *slot = nullptr;
    for (NativeSlot &s : g_native) {
        if (s.refs == 0) {
            slot = &s;
            break;
        }
    }
    if (slot == nullptr) return nullptr;
    void *lib = __real_dlopen(def->lib, RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "pono", "saori: cannot open %s", def->lib);
        return nullptr;
    }
    auto policy = reinterpret_cast<void (*)(const char *, const char *, const char *)>(__real_dlsym(lib, "pono_guest_policy"));
    auto load = reinterpret_cast<long (*)(char *, long)>(__real_dlsym(lib, "pono_guest_load"));
    auto request = reinterpret_cast<char *(*)(long, char *, long *)>(__real_dlsym(lib, "pono_guest_request"));
    auto unload = reinterpret_cast<int (*)(long)>(__real_dlsym(lib, "pono_guest_unload"));
    if (policy == nullptr || load == nullptr || request == nullptr || unload == nullptr) return nullptr;
    std::string allowed, guarded, readable;
    {
        std::lock_guard<std::mutex> g(g_lock);
        allowed = JoinLines(g_allowed);
        guarded = JoinLines(g_protected);
        readable = JoinLines(g_readable);
    }
    policy(allowed.c_str(), guarded.c_str(), readable.c_str());
    *slot = NativeSlot{def, 1, 0, load, request, unload};
    return slot;
}

int NativeIndex(void *handle) {
    NativeSlot *p = static_cast<NativeSlot *>(handle);
    return p >= g_native && p < g_native + kNativeSlots ? static_cast<int>(p - g_native) : -1;
}

// 華和梨などの load / unload / request と、YAYA（POSIX）の <名前>_saori_load など（ID 付き）
template <int N> int NativeLoad(char *h, long len) {
    g_native[N].id = g_native[N].load(h, len);
    return g_native[N].id > 0 ? 1 : 0;
}
template <int N> long NativeLoadWithId(char *h, long len) { return NativeLoad<N>(h, len) ? N + 1 : 0; }
template <int N> int NativeUnload() { return g_native[N].unload(g_native[N].id); }
template <int N> int NativeUnloadWithId(long) { return NativeUnload<N>(); }
template <int N> char *NativeRequest(char *h, long *len) { return g_native[N].request(g_native[N].id, h, len); }
template <int N> char *NativeRequestWithId(long, char *h, long *len) { return NativeRequest<N>(h, len); }

#define PONO_NATIVE_SLOTS(F) F(0), F(1), F(2), F(3), F(4), F(5), F(6), F(7)
#define PONO_N_LOAD(n) reinterpret_cast<void *>(&NativeLoad<n>)
#define PONO_N_LOAD_ID(n) reinterpret_cast<void *>(&NativeLoadWithId<n>)
#define PONO_N_UNLOAD(n) reinterpret_cast<void *>(&NativeUnload<n>)
#define PONO_N_UNLOAD_ID(n) reinterpret_cast<void *>(&NativeUnloadWithId<n>)
#define PONO_N_REQUEST(n) reinterpret_cast<void *>(&NativeRequest<n>)
#define PONO_N_REQUEST_ID(n) reinterpret_cast<void *>(&NativeRequestWithId<n>)
void *const kNativeLoad[kNativeSlots] = {PONO_NATIVE_SLOTS(PONO_N_LOAD)};
void *const kNativeLoadWithId[kNativeSlots] = {PONO_NATIVE_SLOTS(PONO_N_LOAD_ID)};
void *const kNativeUnload[kNativeSlots] = {PONO_NATIVE_SLOTS(PONO_N_UNLOAD)};
void *const kNativeUnloadWithId[kNativeSlots] = {PONO_NATIVE_SLOTS(PONO_N_UNLOAD_ID)};
void *const kNativeRequest[kNativeSlots] = {PONO_NATIVE_SLOTS(PONO_N_REQUEST)};
void *const kNativeRequestWithId[kNativeSlots] = {PONO_NATIVE_SLOTS(PONO_N_REQUEST_ID)};

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

void pono_sandbox_allow_read(const char *dir) {
    std::string canon = Canonical(dir);
    std::lock_guard<std::mutex> guard(g_lock);
    for (const std::string &r : g_readable) {
        if (r == canon) return;
    }
    g_readable.push_back(canon);
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
    // ソースから作った SAORI（nodoka.dll など）は、その .so を開く
    if (name != nullptr) {
        if (const NativeSaoriLib *def = NativeFor(name)) {
            if (NativeSlot *slot = NativeOpen(def)) return slot;
        }
    }
    char module[64];
    if (name != nullptr && pono_saori_module_name(name, module, sizeof(module))) {
        SaoriSlot *slot = SaoriSlotFor(module);
        if (slot != nullptr) return slot;
    }
    Denied("dlopen", name);
    return nullptr;
}

void *__wrap_dlsym(void *handle, const char *symbol) {
    int k = NativeIndex(handle);
    if (k >= 0 && symbol != nullptr) {
        if (strcmp(symbol, "load") == 0) return kNativeLoad[k];
        if (strcmp(symbol, "unload") == 0) return kNativeUnload[k];
        if (strcmp(symbol, "request") == 0) return kNativeRequest[k];
        if (EndsWith(symbol, "_saori_load")) return kNativeLoadWithId[k];
        if (EndsWith(symbol, "_saori_unload")) return kNativeUnloadWithId[k];
        if (EndsWith(symbol, "_saori_request")) return kNativeRequestWithId[k];
        return nullptr;
    }
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

// 開けるのは内蔵モジュールだけ。閉じたら枠の使っている数を減らす
int __wrap_dlclose(void *handle) {
    // ソースから作った SAORI の .so は閉じずに残す（同じプロセスでまた使うことがある）。枠の使っている数だけ減らす
    int k = NativeIndex(handle);
    if (k >= 0) {
        std::lock_guard<std::mutex> guard(g_saori_lock);
        if (g_native[k].refs > 0) g_native[k].refs--;
        return 0;
    }
    int n = SaoriSlotIndex(handle);
    if (n < 0) return -1;
    SaoriSlotRelease(&g_saori_slots[n]);
    return 0;
}

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
    if (!OnPathToAllowed(path) && !Allowed(path, false, "stat")) {
        errno = EACCES;
        return -1;
    }
    return __real_stat(path, st);
}

int __wrap_lstat(const char *path, struct stat *st) {
    if (!OnPathToAllowed(path) && !Allowed(path, false, "lstat")) {
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

// Rust の std（pasta）が使う。openat と同じく、ディレクトリの fd からの相対パスは確かめられないので通さない
int __wrap_unlinkat(int dirfd, const char *path, int flags) {
    if (path == nullptr || (path[0] != '/' && dirfd != AT_FDCWD) || !Allowed(path, true, "unlinkat")) {
        if (path != nullptr && path[0] != '/' && dirfd != AT_FDCWD) Denied("unlinkat (relative to fd)", path);
        errno = EACCES;
        return -1;
    }
    return __real_unlinkat(dirfd, path, flags);
}

int __wrap_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    if (path == nullptr || (path[0] != '/' && dirfd != AT_FDCWD) || (!OnPathToAllowed(path) && !Allowed(path, false, "fstatat"))) {
        if (path != nullptr && path[0] != '/' && dirfd != AT_FDCWD) Denied("fstatat (relative to fd)", path);
        errno = EACCES;
        return -1;
    }
    return __real_fstatat(dirfd, path, st, flags);
}

// リンクを作る場所だけを確かめる。リンクの先は、開くときに（Canonical で先をたどって）確かめる
int __wrap_symlink(const char *target, const char *linkpath) {
    if (!Allowed(linkpath, true, "symlink")) {
        errno = EACCES;
        return -1;
    }
    return __real_symlink(target, linkpath);
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
