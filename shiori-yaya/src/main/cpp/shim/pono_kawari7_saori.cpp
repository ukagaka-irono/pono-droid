// All dynamic loading passes through pono_sandbox's registered module adapters.
#include "saori/saori_linux.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <map>
#include <memory>

namespace {
char* Copy(const std::string& s) {
    char* p = static_cast<char*>(malloc(s.size() + 1));
    if (p) memcpy(p, s.c_str(), s.size() + 1);
    return p;
}
}

struct TSaoriBindingLnx::State {
    void* handle = nullptr;
    char* (*request)(char*, long*) = nullptr;
    int (*unload)() = nullptr;
    unsigned int refs = 0;
    std::string path;
    static std::map<std::string, State*> modules;
    ~State() { if (unload) unload(); if (handle) dlclose(handle); }
};
std::map<std::string, TSaoriBindingLnx::State*> TSaoriBindingLnx::State::modules;

TSaoriBindingLnx* TSaoriBindingLnx::Bind(const std::string& name) {
    auto path = name;
    std::replace(path.begin(), path.end(), '\\', '/');
    auto found = State::modules.find(path);
    if (found != State::modules.end()) {
        ++found->second->refs;
        return new TSaoriBindingLnx(found->second);
    }
    std::unique_ptr<State> s(new State);
    s->path = path;
    s->handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!s->handle) return nullptr;
    auto load = reinterpret_cast<int (*)(char*, long)>(dlsym(s->handle, "load"));
    s->request = reinterpret_cast<char* (*)(char*, long*)>(dlsym(s->handle, "request"));
    auto unload = reinterpret_cast<int (*)()>(dlsym(s->handle, "unload"));
    if (!load || !s->request || !unload) return nullptr;
    auto dir = path.substr(0, path.find_last_of('/') + 1);
    char* input = Copy(dir);
    if (!input || !load(input, static_cast<long>(dir.size()))) return nullptr;
    s->unload = unload;
    s->refs = 1;
    State::modules.emplace(path, s.get());
    return new TSaoriBindingLnx(s.release());
}

std::string TSaoriBindingLnx::Request(const std::string& request) {
    char* input = Copy(request);
    if (!input) return "";
    long length = static_cast<long>(request.size());
    std::unique_ptr<char, decltype(&free)> response(state->request(input, &length), free);
    if (!response || length < 0 || length > (1 << 24)) return "";
    return std::string(response.get(), static_cast<size_t>(length));
}
unsigned int TSaoriBindingLnx::GetLoadCount() { return state->refs; }
TSaoriBindingLnx::~TSaoriBindingLnx() {
    if (--state->refs == 0) { State::modules.erase(state->path); delete state; }
}
