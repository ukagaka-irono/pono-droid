// nashi 2 uses the original block interpreter and event dispatcher, with POSIX OS/SAORI adapters.
#include "jni_common.h"
#include "shiori.h"
#include "util.h"
#include <memory>
#include <cstring>

namespace {
std::map<long, std::unique_ptr<nashi::Shiori>> instances;
long nextId = 0;
long Load(char* raw, long len) {
    std::unique_ptr<char, decltype(&free)> owned(raw, free);
    if (!raw || len <= 0) return -1;
    std::string dir(raw, size_t(len));
    if (dir.back() != '/') dir += '/';
    auto shiori = std::make_unique<nashi::Shiori>();
    if (!shiori->Load(nashi::Utf8ToWide(dir))) return -1;
    const long id = nextId++; instances[id] = std::move(shiori); return id;
}
char* Request(long id, char* raw, long* len) {
    std::unique_ptr<char, decltype(&free)> owned(raw, free);
    auto it = instances.find(id);
    if (it == instances.end() || !raw || !len || *len < 0) return nullptr;
    auto response = it->second->Request(std::string(raw, size_t(*len)));
    auto out = static_cast<char*>(malloc(response.size() + 1));
    if (!out) return nullptr;
    memcpy(out, response.c_str(), response.size() + 1); *len = response.size(); return out;
}
bool Unload(long id) {
    auto it = instances.find(id); if (it == instances.end()) return false;
    it->second->Unload(); instances.erase(it); return true;
}
const pono::ShioriEngine kNashi = {Load, Request, Unload, true};
}
PONO_DEFINE_SHIORI_JNI(NashiNative, kNashi)
