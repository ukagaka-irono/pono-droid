// Android adapter for nashi's SAORI interface. dlopen/dlsym use pono_sandbox's module registry.
#include "saori.h"
#include "util.h"
#include "pono_saori.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

namespace nashi {
namespace {
using LoadFn = int (*)(char*, long);
using RequestFn = char* (*)(char*, long*);
using UnloadFn = int (*)();
struct Module {
    void* handle = nullptr;
    RequestFn request = nullptr;
    UnloadFn unload = nullptr;
    ~Module() { if (unload) unload(); if (handle) dlclose(handle); }
};
std::string Normalize(std::string name) {
    for (char& c : name) { if (c == '\\') c = '/'; else if (c >= 'A' && c <= 'Z') c += 'a' - 'A'; }
    return name;
}
bool SafeRelative(const std::string& file) {
    return file.size() >= 5 && file.front() != '/' && file.find("..") == std::string::npos &&
        file.find(':') == std::string::npos && file.find('\0') == std::string::npos &&
        file.substr(file.size() - 4) == ".dll";
}
char* Dup(const std::string& s) {
    auto p = static_cast<char*>(malloc(s.size() + 1));
    if (p) memcpy(p, s.c_str(), s.size() + 1);
    return p;
}
}

struct Saori::Core {
    std::atomic<int> refs{1};
    std::mutex execMutex, doneMutex;
    std::condition_variable finished;
    std::string dir;
    std::map<std::string, std::unique_ptr<Module>> modules;
    std::vector<SaoriDone> done;
    int running = 0;
    bool detached = false;
};

namespace {
void Release(Saori::Core* core) { if (--core->refs == 0) delete core; }
SaoriResult ExecuteOn(Saori::Core* core, const std::string& file, const std::vector<std::string>& args) {
    std::lock_guard<std::mutex> lock(core->execMutex);
    SaoriResult result;
    const auto key = Normalize(file);
    if (!SafeRelative(key) || core->dir.empty()) {
        result.error = "SAORI のファイル名が使えません: " + file; return result;
    }
    auto& module = core->modules[key];
    if (!module) {
        module = std::make_unique<Module>();
        auto relative = file;
        std::replace(relative.begin(), relative.end(), '\\', '/');
        const auto path = core->dir + relative;
        module->handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (module->handle) {
            auto load = reinterpret_cast<LoadFn>(dlsym(module->handle, "load"));
            auto request = reinterpret_cast<RequestFn>(dlsym(module->handle, "request"));
            auto unload = reinterpret_cast<UnloadFn>(dlsym(module->handle, "unload"));
            const auto folder = path.substr(0, path.find_last_of('/') + 1);
            if (load && request && unload) {
                char* input = Dup(folder);
                if (input && load(input, static_cast<long>(folder.size()))) {
                    module->request = request; module->unload = unload;
                }
            }
        }
    }
    if (!module->request) { result.error = "Android で使用できない SAORI です: " + file; return result; }
    std::string request = "EXECUTE SAORI/1.0\r\nCharset: UTF-8\r\nSender: nashi\r\nSecurityLevel: local\r\n";
    for (size_t i = 0; i < args.size() && i < 32; ++i) {
        auto arg = args[i];
        for (char& c : arg) if (c == '\r' || c == '\n' || c == '\0') c = ' ';
        request += "Argument" + std::to_string(i) + ": " + arg + "\r\n";
    }
    request += "\r\n";
    char* input = Dup(request);
    if (!input) { result.error = "SAORI の要求を作成できません"; return result; }
    long len = static_cast<long>(request.size());
    std::unique_ptr<char, decltype(&free)> output(module->request(input, &len), free);
    if (!output || len <= 0 || len > (1 << 24)) { result.error = "SAORI の応答が不正です: " + file; return result; }
    std::string response(output.get(), size_t(len));
    std::map<std::string, std::string> headers;
    size_t pos = response.find('\n');
    if (response.compare(0, 10, "SAORI/1.0 ") != 0 || pos == std::string::npos) {
        result.error = "SAORI/1.0 の応答ではありません: " + file; return result;
    }
    result.status = atoi(response.c_str() + 10);
    while (pos < response.size()) {
        const size_t end = response.find('\n', ++pos);
        const auto line = Trim(response.substr(pos, end == std::string::npos ? end : end - pos));
        if (line.empty()) break;
        const auto colon = line.find(':');
        if (colon != std::string::npos) headers[Normalize(Trim(line.substr(0, colon)))] = Trim(line.substr(colon + 1));
        if (end == std::string::npos) break;
        pos = end;
    }
    auto decode = [&](const std::string& value) {
        const auto charset = Normalize(headers["charset"]);
        if (charset == "shift_jis" || charset == "shift-jis" || charset == "cp932" || charset == "windows-31j")
            return WideToUtf8(MbToWide(value, 932));
        return charset.empty() ? ToUtf8(value) : RepairUtf8(value);
    };
    result.result = decode(headers["result"]);
    for (int i = 0; i < 64; ++i) {
        auto it = headers.find("value" + std::to_string(i));
        if (it != headers.end()) { result.values.resize(size_t(i) + 1); result.values[i] = decode(it->second); }
    }
    result.ok = true; // Upstream exposes the status separately, including non-200 responses.
    return result;
}
}

Saori::Saori() : core_(new Core()) {}
Saori::~Saori() {
    { std::lock_guard<std::mutex> lock(core_->doneMutex); core_->detached = true; }
    UnloadAll(); Release(core_);
}
void Saori::SetBaseDir(const std::wstring& dir) {
    std::lock_guard<std::mutex> lock(core_->execMutex);
    core_->dir = WideToUtf8(dir);
    std::replace(core_->dir.begin(), core_->dir.end(), '\\', '/');
    if (!core_->dir.empty() && core_->dir.back() != '/') core_->dir += '/';
}
SaoriResult Saori::Execute(const std::string& file, const std::vector<std::string>& args) {
    try { return ExecuteOn(core_, file, args); }
    catch (...) { SaoriResult result; result.error = "SAORI の呼び出しで例外が発生しました: " + file; return result; }
}
bool Saori::ExecuteAsync(const std::string& file, const std::vector<std::string>& args, const std::string& into, int valueIndex) {
    if (!SafeRelative(Normalize(file))) return false;
    // Hold the Java handler before leaving the JNI request. Never use another ghost's later handler.
    auto context = std::shared_ptr<_jobject>(pono_saori_capture_context(), pono_saori_release_context);
    if (!context) return false;
    Core* core = core_;
    std::lock_guard<std::mutex> lock(core->doneMutex);
    if (core->detached || core->running >= kMaxJobs) return false;
    ++core->running; ++core->refs;
    try {
        std::thread([core, file, args, into, valueIndex, context] {
            const bool attached = pono_saori_set_context(context.get());
            SaoriDone done; done.file = file; done.into = into;
            try {
                bool detached;
                { std::lock_guard<std::mutex> lock(core->doneMutex); detached = core->detached; }
                if (!attached) done.error = "SAORI の呼び出し元を復元できません";
                else if (!detached) {
                    auto result = ExecuteOn(core, file, args);
                    done.error = result.error;
                    if (result.ok && valueIndex < 0) done.value = result.result;
                    else if (result.ok && size_t(valueIndex) < result.values.size()) done.value = result.values[valueIndex];
                }
            } catch (...) { done.error = "SAORI の呼び出しで例外が発生しました"; }
            {
                std::lock_guard<std::mutex> lock(core->doneMutex);
                --core->running;
                if (!core->detached) core->done.push_back(std::move(done));
                core->finished.notify_all();
            }
            Release(core);
            pono_saori_set_context(nullptr);
        }).detach();
    } catch (...) { --core->running; Release(core); return false; }
    return true;
}
void Saori::TakeDone(std::vector<SaoriDone>& out) {
    std::lock_guard<std::mutex> lock(core_->doneMutex); out.clear(); out.swap(core_->done);
}
int Saori::Running() { std::lock_guard<std::mutex> lock(core_->doneMutex); return core_->running; }
bool Saori::WaitAll(DWORD ms) {
    std::unique_lock<std::mutex> lock(core_->doneMutex);
    return core_->finished.wait_for(lock, std::chrono::milliseconds(ms), [&] { return core_->running == 0; });
}
void Saori::UnloadAll() {
    if (!WaitAll(5000)) return;
    std::lock_guard<std::mutex> lock(core_->execMutex); core_->modules.clear();
}
} // namespace nashi
